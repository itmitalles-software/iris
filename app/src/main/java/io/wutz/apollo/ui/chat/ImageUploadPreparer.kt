package io.wutz.apollo.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.Log
import io.wutz.apollo.data.model.Attachment
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.InputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Largest picked photo accepted before downscaling. The 10 MiB frame limit
 * ([MAX_CHAT_ATTACHMENT_BYTES]) still applies to the bytes that are actually sent.
 */
internal const val MAX_SOURCE_IMAGE_BYTES = 40L * 1024L * 1024L

/** Size limit for the picked file itself: photos that will be downscaled may start larger. */
internal fun Attachment.maxSourceBytes(): Long =
    if (isImage && !sendOriginal && !isGif) MAX_SOURCE_IMAGE_BYTES else MAX_CHAT_ATTACHMENT_BYTES

/** Output encoding for a re-encoded upload image. */
internal enum class UploadImageFormat(
    val mimeType: String,
    val extension: String,
) {
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
}

/**
 * Decides how a picked photo travels to the gateway. Camera photos are 3-8 MB at full resolution
 * and travel as Base64 over the socket; models scale them down anyway. By default an image is
 * re-encoded to at most [MAX_LONG_EDGE] px on the long edge as JPEG quality [JPEG_QUALITY]
 * (PNG when it has transparency). GIFs and animated WebP/PNG keep their original bytes so the
 * animation survives, and small JPEG/PNG/WebP files are sent unchanged.
 */
internal object ImageUploadPolicy {
    const val MAX_LONG_EDGE = 2048
    const val JPEG_QUALITY = 85

    private val REENCODABLE = setOf("image/jpeg", "image/jpg", "image/png", "image/webp", "image/heic", "image/heif")
    private val HEIF = setOf("image/heic", "image/heif")

    /** Size that fits [width] x [height] into [maxEdge] on the long edge, or null when it already fits. */
    fun targetSize(
        width: Int,
        height: Int,
        maxEdge: Int = MAX_LONG_EDGE,
    ): Pair<Int, Int>? {
        if (width <= 0 || height <= 0) return null
        val longEdge = max(width, height)
        if (longEdge <= maxEdge) return null
        val scale = maxEdge.toDouble() / longEdge
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    /**
     * True when the image at [mimeType] with these bounds should be re-encoded: it is larger than
     * [MAX_LONG_EDGE], or it is HEIF (which many model providers do not accept).
     */
    fun shouldReencode(
        mimeType: String,
        width: Int,
        height: Int,
        animated: Boolean,
    ): Boolean {
        val mime = mimeType.substringBefore(';').trim().lowercase()
        if (animated || mime !in REENCODABLE || width <= 0 || height <= 0) return false
        return targetSize(width, height) != null || mime in HEIF
    }

    /** PNG keeps transparency; everything else becomes JPEG. */
    fun outputFormat(
        sourceMimeType: String,
        hasAlpha: Boolean,
    ): UploadImageFormat {
        val mime = sourceMimeType.substringBefore(';').trim().lowercase()
        return if (hasAlpha && (mime == "image/png" || mime == "image/webp")) {
            UploadImageFormat.PNG
        } else {
            UploadImageFormat.JPEG
        }
    }

    /** [name] with its extension replaced by [format]'s. */
    fun renamed(
        name: String,
        format: UploadImageFormat,
    ): String {
        val base = name.substringBeforeLast('.', name).ifBlank { "image" }
        return "$base.${format.extension}"
    }

    /** True for a WebP whose VP8X header sets the animation flag. */
    fun isAnimatedWebp(header: ByteArray): Boolean {
        if (header.size < 21) return false

        fun ascii(
            offset: Int,
            text: String,
        ) = text.indices.all { header[offset + it] == text[it].code.toByte() }
        return ascii(0, "RIFF") && ascii(8, "WEBP") && ascii(12, "VP8X") &&
            (header[20].toInt() and ANIMATION_FLAG) != 0
    }

    /** True for an APNG: an `acTL` chunk appears before the first `IDAT`. Consumes [input]. */
    fun isAnimatedPng(input: InputStream): Boolean {
        val signature = ByteArray(8)
        if (input.readFully(signature) < 8 || !signature.contentEquals(PNG_SIGNATURE)) return false
        val chunkHeader = ByteArray(8)
        while (input.readFully(chunkHeader) == 8) {
            val length =
                ((chunkHeader[0].toLong() and 0xff) shl 24) or ((chunkHeader[1].toLong() and 0xff) shl 16) or
                    ((chunkHeader[2].toLong() and 0xff) shl 8) or (chunkHeader[3].toLong() and 0xff)
            when (String(chunkHeader, 4, 4, Charsets.US_ASCII)) {
                "acTL" -> return true
                "IDAT", "IEND" -> return false
            }
            if (!input.skipFully(length + CRC_BYTES)) return false
        }
        return false
    }

    private fun InputStream.readFully(buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        return total
    }

    private fun InputStream.skipFully(count: Long): Boolean {
        var remaining = count
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else if (read() < 0) {
                return false
            } else {
                remaining--
            }
        }
        return true
    }

    private const val ANIMATION_FLAG = 0x02
    private const val CRC_BYTES = 4L
    private val PNG_SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
}

/** A re-encoded copy of a picked image: [file] holds the bytes, [attachment] the adjusted name and type. */
internal class PreparedUploadImage(
    val attachment: Attachment,
    val file: File,
)

/**
 * Applies [ImageUploadPolicy] to a picked image. Returns null whenever the original should be
 * sent: the user chose "Send original", the image is small or animated, or decoding failed (an
 * undecodable image is still sent as before rather than being dropped).
 */
internal object ImageUploadPreparer {
    private const val TAG = "ImageUploadPreparer"

    fun prepare(
        context: Context,
        attachment: Attachment,
    ): PreparedUploadImage? {
        if (!attachment.isImage || attachment.sendOriginal || attachment.isGif) return null
        var output: File? = null
        return try {
            val uri = Uri.parse(attachment.uri)
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            // decodeStream returns null when it only reads bounds; the result is in [bounds].
            val opened = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) != null }
            if (opened == null) return null
            val mime = bounds.outMimeType ?: attachment.mimeType
            val animated =
                when (mime.lowercase()) {
                    "image/webp" -> {
                        resolver.openInputStream(uri)?.use { ImageUploadPolicy.isAnimatedWebp(it.readNBytesCompat(32)) }
                    }

                    "image/png" -> {
                        resolver.openInputStream(uri)?.use { ImageUploadPolicy.isAnimatedPng(it.buffered()) }
                    }

                    else -> {
                        false
                    }
                } ?: return null
            if (!ImageUploadPolicy.shouldReencode(mime, bounds.outWidth, bounds.outHeight, animated)) return null
            val target = ImageUploadPolicy.targetSize(bounds.outWidth, bounds.outHeight)
            val bitmap = decode(context, uri, bounds.outWidth, bounds.outHeight, target) ?: return null
            val format = ImageUploadPolicy.outputFormat(mime, bitmap.hasAlpha())
            output = File.createTempFile("chat-upload-", ".${format.extension}", context.cacheDir)
            val written =
                try {
                    output.outputStream().buffered().use { stream ->
                        bitmap.compress(
                            if (format ==
                                UploadImageFormat.PNG
                            ) {
                                Bitmap.CompressFormat.PNG
                            } else {
                                Bitmap.CompressFormat.JPEG
                            },
                            ImageUploadPolicy.JPEG_QUALITY,
                            stream,
                        )
                    }
                } finally {
                    bitmap.recycle()
                }
            val heif = mime.lowercase() == "image/heic" || mime.lowercase() == "image/heif"
            // A re-encode that grew the file only makes sense when the source format needs converting.
            if (!written || (!heif && attachment.size > 0 && output.length() >= attachment.size)) {
                output.delete()
                return null
            }
            PreparedUploadImage(
                attachment =
                    attachment.copy(
                        name = ImageUploadPolicy.renamed(attachment.name, format),
                        mimeType = format.mimeType,
                        size = output.length(),
                    ),
                file = output,
            )
        } catch (e: CancellationException) {
            output?.delete()
            throw e
        } catch (e: Exception) {
            output?.delete()
            Log.w(TAG, "Sending the original image, downscaling failed: ${e.javaClass.simpleName}")
            null
        } catch (e: OutOfMemoryError) {
            output?.delete()
            Log.w(TAG, "Sending the original image, not enough memory to downscale")
            null
        }
    }

    private fun decode(
        context: Context,
        uri: Uri,
        width: Int,
        height: Int,
        target: Pair<Int, Int>?,
    ): Bitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder applies the EXIF orientation and decodes HEIF.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                ImageUploadPolicy.targetSize(info.size.width, info.size.height)?.let { (w, h) ->
                    decoder.setTargetSize(w, h)
                }
            }
        } else {
            decodeLegacy(context, uri, width, height, target)
        }

    /** API 26/27: subsample while decoding, scale to the exact target, then apply the EXIF orientation. */
    private fun decodeLegacy(
        context: Context,
        uri: Uri,
        width: Int,
        height: Int,
        target: Pair<Int, Int>?,
    ): Bitmap? {
        val resolver = context.contentResolver
        var sample = 1
        if (target != null) {
            while (width / (sample * 2) >= target.first && height / (sample * 2) >= target.second) sample *= 2
        }
        val decoded =
            resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return null
        val scaled =
            if (target != null && (decoded.width != target.first || decoded.height != target.second)) {
                Bitmap.createScaledBitmap(decoded, target.first, target.second, true).also {
                    if (it !== decoded) decoded.recycle()
                }
            } else {
                decoded
            }
        val orientation =
            resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        val matrix = orientationMatrix(orientation) ?: return scaled
        return Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, matrix, true).also {
            if (it !== scaled) scaled.recycle()
        }
    }

    private fun orientationMatrix(orientation: Int): Matrix? =
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> Matrix().apply { postRotate(90f) }
            ExifInterface.ORIENTATION_ROTATE_180 -> Matrix().apply { postRotate(180f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> Matrix().apply { postRotate(270f) }
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> Matrix().apply { postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> Matrix().apply { postScale(1f, -1f) }
            ExifInterface.ORIENTATION_TRANSPOSE -> Matrix().apply { postRotate(90f) }.apply { postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> Matrix().apply { postRotate(270f) }.apply { postScale(-1f, 1f) }
            else -> null
        }

    private fun InputStream.readNBytesCompat(count: Int): ByteArray {
        val buffer = ByteArray(count)
        var total = 0
        while (total < count) {
            val read = read(buffer, total, count - total)
            if (read < 0) break
            total += read
        }
        return buffer.copyOf(total)
    }
}
