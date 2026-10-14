package io.wutz.apollo.share

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.IntentCompat
import io.wutz.apollo.data.model.Attachment
import java.util.concurrent.atomic.AtomicReference

/**
 * Content another app shared into Apollo through the Android share sheet
 * (`ACTION_SEND` / `ACTION_SEND_MULTIPLE`). It is staged in a new chat's
 * composer and never sent automatically.
 */
data class SharedContent(
    val text: String?,
    val attachments: List<Attachment>,
) {
    val isEmpty: Boolean get() = text.isNullOrBlank() && attachments.isEmpty()
}

/**
 * One-slot hand-off between [io.wutz.apollo.MainActivity], which receives the
 * share intent, and the chat screen, which stages it once a new chat exists, or
 * once [sessionId]'s chat is open for a direct share to a session shortcut.
 * A newer share replaces an unconsumed older one.
 */
object ShareInbox {
    private data class Pending(
        val content: SharedContent,
        val sessionId: String?,
    )

    private val pending = AtomicReference<Pending?>(null)

    fun offer(
        content: SharedContent,
        sessionId: String? = null,
    ) {
        pending.set(Pending(content, sessionId))
    }

    /** Content for a new chat; a share aimed at a session stays for [consumeFor]. */
    fun consume(): SharedContent? = take { it.sessionId == null }

    /** Content shared straight into [sessionId] through its share-sheet shortcut. */
    fun consumeFor(sessionId: String): SharedContent? = take { it.sessionId == sessionId }

    fun clear() {
        pending.set(null)
    }

    private fun take(matches: (Pending) -> Boolean): SharedContent? {
        while (true) {
            val current = pending.get() ?: return null
            if (!matches(current)) return null
            if (pending.compareAndSet(current, null)) return current.content
        }
    }
}

/** Metadata lookup for a shared URI; injectable so parsing is unit-testable. */
fun interface SharedUriResolver {
    /** Returns the display name, size (0 when unknown) and MIME type, or null when unreadable. */
    fun describe(uri: Uri): Triple<String, Long, String>?
}

object ShareIntentParser {
    private const val TAG = "ShareIntentParser"
    private const val FALLBACK_MIME = "application/octet-stream"

    /**
     * Parse a share intent into [SharedContent], or null when the intent is
     * not a share or carries nothing usable.
     *
     * Only `content://` URIs from other apps are accepted. `file://` URIs and
     * URIs served by Apollo's own FileProvider ([ownAuthority]) are dropped so
     * a malicious sender cannot make Apollo upload its private files.
     */
    fun parse(
        intent: Intent,
        ownAuthority: String,
        resolver: SharedUriResolver,
    ): SharedContent? {
        val uris =
            when (intent.action) {
                Intent.ACTION_SEND -> {
                    listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
                }

                Intent.ACTION_SEND_MULTIPLE -> {
                    IntentCompat
                        .getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                        .orEmpty()
                }

                else -> {
                    return null
                }
            }
        val attachments =
            uris
                .filter { isAcceptedUri(it, ownAuthority) }
                .distinct()
                .mapNotNull { uri ->
                    resolver.describe(uri)?.let { (name, size, mime) ->
                        Attachment(uri = uri.toString(), name = name, mimeType = mime, size = size)
                    }
                }
        // EXTRA_TEXT/EXTRA_SUBJECT are CharSequence extras; getStringExtra drops styled (Spanned) text.
        val text =
            composeText(
                intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString(),
                intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
            )
        return SharedContent(text = text, attachments = attachments).takeUnless { it.isEmpty }
    }

    internal fun isAcceptedUri(
        uri: Uri,
        ownAuthority: String,
    ): Boolean {
        val authority = uri.authority ?: return false
        // A "<userId>@" prefix still resolves to the same provider (ContentProvider
        // getAuthorityWithoutUserId), so compare without it.
        return uri.scheme == ContentResolver.SCHEME_CONTENT && authority.substringAfterLast('@') != ownAuthority
    }

    internal fun composeText(
        subject: String?,
        text: String?,
    ): String? {
        val parts = listOf(subject, text).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.distinct()
        return parts.joinToString("\n\n").takeIf { it.isNotEmpty() }
    }

    /** [SharedUriResolver] backed by the system [ContentResolver]. */
    fun contentResolverDescriber(contentResolver: ContentResolver): SharedUriResolver =
        SharedUriResolver { uri ->
            runCatching {
                var name = uri.lastPathSegment ?: "file"
                var size = 0L
                contentResolver
                    .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (nameIdx >= 0 && !cursor.isNull(nameIdx)) name = cursor.getString(nameIdx)
                            if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
                        }
                    }
                val mime = contentResolver.getType(uri) ?: FALLBACK_MIME
                // Some providers expose no display name (only an id path segment); give
                // the agent a usable file extension in that case.
                if ('.' !in name) {
                    MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { name = "$name.$it" }
                }
                Triple(name, size, mime)
            }.onFailure { error ->
                Log.w(TAG, "Skipping unreadable shared attachment", error)
            }.getOrNull()
        }
}
