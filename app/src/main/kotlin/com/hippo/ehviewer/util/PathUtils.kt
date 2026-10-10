package com.hippo.ehviewer.util

import android.content.ContentResolver
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.core.provider.DocumentsContractCompat
import arrow.core.Either
import com.ehviewer.core.files.openFileDescriptor
import com.ehviewer.core.files.read
import com.ehviewer.core.files.toUri
import com.hippo.ehviewer.jni.sha1 as nativeSha1
import com.hippo.ehviewer.smb.SmbLocation
import kotlinx.io.readString
import okio.Path
import splitties.init.appCtx

val Uri.displayPath: String?
    get() {
        if (scheme == ContentResolver.SCHEME_FILE) {
            return path
        }

        val context = appCtx
        // A tree URI -- what the folder picker hands back -- is not a document
        // URI, so it used to fall through to toString() and render as
        // `.../tree/primary%3AEhViewer`, the %3A being an encoded colon.
        val documentId = when {
            DocumentsContract.isDocumentUri(context, this) -> DocumentsContract.getDocumentId(this)
            DocumentsContractCompat.isTreeUri(this) -> DocumentsContract.getTreeDocumentId(this)
            else -> null
        }
        if (documentId != null) {
            val (type, path) = documentId.split(":", limit = 2).also {
                if (it.size < 2) return Uri.decode(toString())
            }
            if (authority == "com.android.externalstorage.documents") {
                if (type == "primary") {
                    return Environment.getExternalStorageDirectory().path + "/" + path
                }
            }

            context.externalCacheDirs.forEach {
                val cachePath = it.path
                val index = cachePath.indexOf(type)
                if (index != -1) {
                    return cachePath.substring(0, index + type.length) + "/" + path
                }
            }
        }

        // Nothing matched. Decode the percent-escapes anyway so no %3A shows up.
        return Uri.decode(toString())
    }

/**
 * A short label for a configured download location.
 *
 * SMB locations are shown by their share name: what gets stored is an
 * `smb://host:port/share` URL, and `Path.toUri()` wraps that in the provider's
 * own `content://` URI, which is not something to put in a summary.
 */
fun downloadLocationLabel(uriString: String): String = SmbLocation.parse(uriString)?.share ?: Uri.parse(uriString).displayPath.orEmpty()

/**
 * The compact label shown next to a download's own labels, identifying where it
 * was saved: `primary:EhwViewer`, `primary:Download/EhViewer`,
 * `smb:ehviewer/comics`. The last two path segments are kept -- enough to tell
 * two locations apart without printing a whole URL -- and the prefix says what
 * kind of location it is. A SAF path's leaf is the percent-encoded document id,
 * so it has to be decoded before it is shown (`primary%3AEhwViewer`).
 */
fun downloadSourceLabel(location: Path): String {
    val smb = SmbLocation.parse(location.toString())
    if (smb != null) {
        return "smb:" + lastTwoSegments(listOf(smb.share) + smb.subPath.split('/'))
    }
    val documentId = Uri.decode(location.name)
    val parts = documentId.split(":", limit = 2)
    // A plain file path has no document id, only a volume prefix on a SAF one.
    if (parts.size < 2) return lastTwoSegments(location.segments)
    return parts[0] + ":" + lastTwoSegments(parts[1].split('/'))
}

private fun lastTwoSegments(segments: List<String>): String = segments.filter(String::isNotEmpty).takeLast(2).joinToString("/")

val Path.displayName: String
    get() {
        val uri = toUri()
        // The Path is constructed by us if the URI is a tree URI, so we don't need to query
        if (uri.scheme != ContentResolver.SCHEME_FILE && !DocumentsContractCompat.isTreeUri(uri)) {
            Either.catch {
                val proj = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                appCtx.contentResolver.query(uri, proj, null, null, null)?.use { c ->
                    if (c.moveToNext()) return c.getString(0)
                }
            }
        }
        return name
    }

fun Path.sha1() = openFileDescriptor("r").use { nativeSha1(it.fd) }
fun Path.utf8() = read { readString() }
