package com.hippo.ehviewer.util

import android.content.ContentResolver
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.core.provider.DocumentsContractCompat
import arrow.core.Either
import com.ehviewer.core.files.list
import com.ehviewer.core.files.metadataOrNull
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
        if (DocumentsContract.isDocumentUri(context, this)) {
            val (type, path) = DocumentsContract.getDocumentId(this).split(":", limit = 2).also {
                if (it.size < 2) return toString()
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

        return toString()
    }

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

/**
 * The folder a location URI names, or null when it names no document.
 *
 * Two URIs can name the same folder: the picker hands back a document URI, and a
 * location added elsewhere may be a tree URI for the same place. Comparing the
 * strings then says "different" about one folder, which is how the same location
 * ends up in the list twice and how a permission gets released while the other
 * entry still needs it. The document id is the folder.
 */
fun documentIdOf(location: String): String? = runCatching {
    val uri = Uri.parse(location)
    when {
        DocumentsContract.isTreeUri(uri) -> DocumentsContract.getTreeDocumentId(uri)
        DocumentsContract.isDocumentUri(appCtx, uri) -> DocumentsContract.getDocumentId(uri)
        else -> null
    }
}.getOrNull()

/** One child of a directory on the device: its name, what it is, how long. */
data class LocalEntry(val name: String, val isDirectory: Boolean, val size: Long)

/**
 * The children of [this], with their lengths, in one query.
 *
 * `FileSystem.list` answers names only, so a length then costs a query per
 * child, and a directory of two thousand pages costs two thousand of them. The
 * provider already holds all three columns side by side, so they come back
 * together here.
 *
 * A plain directory is not a document, and is read off the file system instead.
 */
fun Path.listLocalEntries(): List<LocalEntry> {
    val uri = toUri()
    if (uri.scheme == ContentResolver.SCHEME_FILE) {
        return list().mapNotNull { child ->
            child.metadataOrNull()?.let { LocalEntry(child.name, it.isDirectory, it.size ?: 0L) }
        }
    }
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, DocumentsContract.getDocumentId(uri))
    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
    )
    return appCtx.contentResolver.query(children, projection, null, null, null)?.use { c ->
        buildList(c.count) {
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val directory = c.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR
                add(LocalEntry(name, directory, if (directory) 0L else c.getLong(2)))
            }
        }
    }.orEmpty()
}

fun Path.sha1() = openFileDescriptor("r").use { nativeSha1(it.fd) }
fun Path.utf8() = read { readString() }
