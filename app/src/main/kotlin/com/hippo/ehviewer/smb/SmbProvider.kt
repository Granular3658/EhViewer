package com.hippo.ehviewer.smb

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Private provider used to make a read-only SMB stream consumable by Coil and
 * ContentResolver. The write side of the pipe is driven by a worker so binder
 * and image-decoder threads never block while waiting for the network.
 */
class SmbProvider : ContentProvider() {
    private val executor = Executors.newCachedThreadPool()

    /**
     * `.../smb/<location>` lists the children of a directory,
     * `.../stat/<location>` describes the location itself.
     *
     * The location is taken as the whole remainder of the path rather than as a
     * fixed number of segments: it contains slashes itself, and whether a
     * builder encodes them depends on the platform.
     */
    private fun location(uri: Uri): SmbLocation {
        // Built with `appendEncodedPath(Uri.encode(...))`, so read the encoded
        // form and decode exactly once. `Uri.path` is already decoded, so a
        // second decode would corrupt paths that contain a literal '%'.
        val encoded = uri.encodedPath.orEmpty().trimStart('/')
        val kind = encoded.substringBefore('/')
        require(kind == "smb" || kind == "stat") { "Invalid SMB URI: $uri" }
        val location = encoded.removePrefix("$kind/")
        return requireNotNull(SmbLocation.parse(Uri.decode(location))) { "Invalid SMB location: $uri" }
    }

    private fun Uri.isStatRequest() = encodedPath.orEmpty().trimStart('/').substringBefore('/') == "stat"

    override fun onCreate() = true

    override fun getType(uri: Uri): String {
        val name = location(uri).subPath.substringAfterLast('/', "")
        return android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val location = location(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = MatrixCursor(columns)
        // An unreachable share or a missing path is "no metadata", not a crash:
        // callers use this to test existence.
        val stat = runCatching { runBlocking { SmbRepository.stat(location) } }.getOrNull()
            ?: return row
        if (stat.isDirectory && !uri.isStatRequest()) {
            runCatching { runBlocking { SmbRepository.list(location) } }.getOrDefault(emptyList()).forEach { entry ->
                row.addRow(columns.map { column ->
                    when (column) {
                        OpenableColumns.DISPLAY_NAME, DocumentsContract.Document.COLUMN_DISPLAY_NAME -> entry.name
                        OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_SIZE -> entry.size
                        DocumentsContract.Document.COLUMN_MIME_TYPE -> if (entry.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"
                        else -> null
                    }
                }.map { it as Any? }.toTypedArray())
            }
        } else {
            row.addRow(columns.map { column ->
                when (column) {
                    OpenableColumns.DISPLAY_NAME, DocumentsContract.Document.COLUMN_DISPLAY_NAME -> location.subPath.substringAfterLast('/').ifEmpty { location.share }
                    OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_SIZE -> stat.size
                    DocumentsContract.Document.COLUMN_MIME_TYPE -> if (stat.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else getType(uri)
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED -> stat.modifiedMillis
                    else -> null
                }
            }.map { it as Any? }.toTypedArray())
        }
        return row
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r" || mode == "rt") { "SMB locations are read-only" }
        val (readSide, writeSide) = ParcelFileDescriptor.createPipe()
        executor.execute {
            var handle: SmbHandle? = null
            try {
                val opened = runBlocking { SmbRepository.open(location(uri)) }
                handle = opened
                ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { output ->
                    val buffer = java.nio.ByteBuffer.allocateDirect(64 * 1024)
                    var offset = 0L
                    while (offset < opened.size) {
                        val requested = minOf(buffer.capacity().toLong(), opened.size - offset).toInt()
                        buffer.clear()
                        val read = SmbRepository.read(opened.value, buffer, offset, 0, requested)
                        if (read <= 0) break
                        val bytes = ByteArray(read)
                        buffer.position(0)
                        buffer.get(bytes)
                        output.write(bytes)
                        offset += read
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "SMB stream failed: $uri", t)
            } finally {
                handle?.let { smbHandle ->
                    runCatching { SmbRepository.close(smbHandle.value) }
                        .onFailure { Log.e(TAG, "Failed to close SMB handle: $uri", it) }
                }
                runCatching { writeSide.close() }
            }
        }
        return readSide
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw IOException("SMB locations are read-only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw IOException("SMB locations are read-only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw IOException("SMB locations are read-only")

    private companion object {
        const val TAG = "SmbProvider"
    }
}
