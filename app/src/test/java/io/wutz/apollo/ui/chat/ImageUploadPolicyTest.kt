package io.wutz.apollo.ui.chat

import io.wutz.apollo.data.model.Attachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ImageUploadPolicyTest {
    @Test
    fun targetSizeKeepsAspectAndOnlyShrinks() {
        assertEquals(2048 to 1536, ImageUploadPolicy.targetSize(4000, 3000))
        assertEquals(1152 to 2048, ImageUploadPolicy.targetSize(2250, 4000))
        assertNull(ImageUploadPolicy.targetSize(2048, 1000))
        assertNull(ImageUploadPolicy.targetSize(0, 100))
    }

    @Test
    fun onlyLargeOrHeifStillImagesAreReencoded() {
        assertTrue(ImageUploadPolicy.shouldReencode("image/jpeg", 4000, 3000, animated = false))
        assertFalse(ImageUploadPolicy.shouldReencode("image/jpeg", 1600, 1200, animated = false))
        assertTrue(ImageUploadPolicy.shouldReencode("image/heic", 1600, 1200, animated = false))
        assertFalse(ImageUploadPolicy.shouldReencode("image/webp", 4000, 3000, animated = true))
        assertFalse(ImageUploadPolicy.shouldReencode("image/gif", 4000, 3000, animated = false))
        assertFalse(ImageUploadPolicy.shouldReencode("image/svg+xml", 4000, 3000, animated = false))
    }

    @Test
    fun transparencyKeepsPng() {
        assertEquals(UploadImageFormat.PNG, ImageUploadPolicy.outputFormat("image/png", hasAlpha = true))
        assertEquals(UploadImageFormat.JPEG, ImageUploadPolicy.outputFormat("image/png", hasAlpha = false))
        assertEquals(UploadImageFormat.JPEG, ImageUploadPolicy.outputFormat("image/jpeg", hasAlpha = true))
        assertEquals("IMG_1.jpg", ImageUploadPolicy.renamed("IMG_1.HEIC", UploadImageFormat.JPEG))
        assertEquals("photo.png", ImageUploadPolicy.renamed("photo", UploadImageFormat.PNG))
    }

    @Test
    fun detectsAnimatedWebp() {
        fun header(flags: Int) =
            "RIFF\u0000\u0000\u0000\u0000WEBPVP8X".toByteArray(Charsets.ISO_8859_1) +
                byteArrayOf(10, 0, 0, 0, flags.toByte(), 0, 0, 0)
        assertTrue(ImageUploadPolicy.isAnimatedWebp(header(0x02)))
        assertFalse(ImageUploadPolicy.isAnimatedWebp(header(0x10)))
        assertFalse(ImageUploadPolicy.isAnimatedWebp("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray()))
    }

    @Test
    fun detectsApng() {
        fun png(vararg chunks: String): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
            chunks.forEach { type ->
                out.write(byteArrayOf(0, 0, 0, 2))
                out.write(type.toByteArray())
                out.write(byteArrayOf(1, 2, 0, 0, 0, 0))
            }
            return out.toByteArray()
        }
        assertTrue(ImageUploadPolicy.isAnimatedPng(ByteArrayInputStream(png("IHDR", "acTL", "IDAT"))))
        assertFalse(ImageUploadPolicy.isAnimatedPng(ByteArrayInputStream(png("IHDR", "IDAT", "acTL"))))
        assertFalse(ImageUploadPolicy.isAnimatedPng(ByteArrayInputStream("GIF89a".toByteArray())))
    }

    @Test
    fun downscalablePhotosMayStartAboveTheFrameLimit() {
        val photo = Attachment(uri = "content://p", name = "p.jpg", mimeType = "image/jpeg", size = 20L * 1024 * 1024)
        assertEquals(MAX_SOURCE_IMAGE_BYTES, photo.maxSourceBytes())
        assertEquals(MAX_CHAT_ATTACHMENT_BYTES, photo.copy(sendOriginal = true).maxSourceBytes())
        assertEquals(MAX_CHAT_ATTACHMENT_BYTES, photo.copy(name = "a.gif", mimeType = "image/gif").maxSourceBytes())
        assertEquals(
            MAX_CHAT_ATTACHMENT_BYTES,
            photo.copy(name = "a.pdf", mimeType = "application/pdf").maxSourceBytes(),
        )
    }
}
