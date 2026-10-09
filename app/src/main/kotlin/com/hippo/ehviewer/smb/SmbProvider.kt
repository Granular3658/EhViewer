package com.hippo.ehviewer.smb

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking

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
                row.addRow(
                    columns.map { column ->
                        when (column) {
                            OpenableColumns.DISPLAY_NAME, DocumentsContract.Document.COLUMN_DISPLAY_NAME -> entry.name
                            OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_SIZE -> entry.size
                            DocumentsContract.Document.COLUMN_MIME_TYPE -> if (entry.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"
                            else -> null
                        }
                    }.map { it as Any? }.toTypedArray(),
                )
            }
        } else {
            row.addRow(
                columns.map { column ->
                    when (column) {
                        OpenableColumns.DISPLAY_NAME, DocumentsContract.Document.COLUMN_DISPLAY_NAME -> location.subPath.substringAfterLast('/').ifEmpty { location.share }
                        OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_SIZE -> stat.size
                        DocumentsContract.Document.COLUMN_MIME_TYPE -> if (stat.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else getType(uri)
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED -> stat.modifiedMillis
                        else -> null
                    }
                }.map { it as Any? }.toTypedArray(),
            )
        }
        return row
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val location = location(uri)
        if (mode == "r" || mode == "rt") {
            // Prefer the in-memory (ashmem) cache: it yields a real, mmap-able
            // file descriptor, so the decode layer avoids both the one-shot pipe
            // (which cannot be mmap'd) and per-image direct buffers (which used to
            // OOM). Falls back to the pipe below when unsupported or on failure.
            val ashmem = runCatching { runBlocking { SmbRepository.openAshmem(location) } }.getOrNull()
            Log.d(TAG, "openFile ashmem for ${location.subPath}: ${if (ashmem != null) "OK fd=${ashmem.fd} key=${ashmem.key} size=${ashmem.size}" else "NULL -> pipe fallback"}")
            if (ashmem != null) {
                val base = ParcelFileDescriptor.adoptFd(ashmem.fd)
                return object : ParcelFileDescriptor(base) {
                    override fun close() {
                        try {
                            super.close()
                        } finally {
                            runCatching { SmbRepository.releaseAshmem(ashmem.key) }
                        }
                    }
                }
            }
            val (readSide, writeSide) = ParcelFileDescriptor.createPipe()
            executor.execute {
                var handle: SmbHandle? = null
                try {
                    val opened = runBlocking { SmbRepository.open(location) }
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
        } else if (mode.contains('w', ignoreCase = true)) {
            val (readSide, writeSide) = ParcelFileDescriptor.createPipe()
            // Write mode: the caller writes into `writeSide`; we drain `readSide`
            // on a worker thread and forward the bytes to the Rust SMB writer.
            // That forwarding is asynchronous, so a caller that closes the
            // descriptor normally returns *before* the bytes are flushed to the
            // server. Wrap the descriptor so closing it blocks until the SMB
            // write has truly finished — otherwise an immediate read (hash
            // check), rename, or decode would see an empty file or hit
            // STATUS_SHARING_VIOLATION, since the underlying SMB handle is opened
            // with exclusive share access until it is closed.
            val finished = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>(null)
            executor.execute {
                var handle: SmbWriteHandle? = null
                try {
                    handle = runBlocking { SmbRepository.create(location) }
                    ParcelFileDescriptor.AutoCloseInputStream(readSide).use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var n: Int
                        while (input.read(buffer).also { n = it } != -1) {
                            if (n > 0) SmbRepository.write(handle.value, buffer.copyOf(n))
                        }
                    }
                    runBlocking { SmbRepository.writeClose(handle.value) }
                } catch (t: Throwable) {
                    failure.set(t)
                    Log.e(TAG, "SMB write failed: $uri", t)
                } finally {
                    handle?.let { smbHandle ->
                        runCatching { runBlocking { SmbRepository.writeClose(smbHandle.value) } }
                    }
                    runCatching { readSide.close() }
                    runCatching { writeSide.close() }
                    finished.countDown()
                }
            }
            return object : ParcelFileDescriptor(writeSide) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        finished.await(120, TimeUnit.SECONDS)
                        failure.get()?.let { throw IOException("SMB write failed: $uri", it) }
                    }
                }
            }
        } else {
            throw IOException("Unsupported SMB open mode: $mode")
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw IOException("SMB locations are read-only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw IOException("SMB locations are read-only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw IOException("SMB locations are read-only")

    /**
     * Filesystem-level SMB operations routed here from [AndroidFileSystem] so the
     * core module can stay free of the app's Rust bindings. `mkdir`/`delete`
     * take the location as `arg`; `rename` takes `from`/`to` in [extras] (the
     * same share, so only the destination sub-path differs).
     */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = when (method) {
        "mkdir" -> {
            val location = SmbLocation.parse(arg!!)
            checkNotNull(location) { "Invalid SMB location: $arg" }
            runBlocking { SmbRepository.mkdir(location) }
            Bundle.EMPTY
        }
        "delete" -> {
            val location = SmbLocation.parse(arg!!)
            checkNotNull(location) { "Invalid SMB location: $arg" }
            runBlocking { SmbRepository.delete(location) }
            Bundle.EMPTY
        }
        "rename" -> {
            val e = extras!!
            val from = SmbLocation.parse(e.getString("from")!!)
            val to = SmbLocation.parse(e.getString("to")!!)
            checkNotNull(from) { "Invalid SMB location: ${e.getString("from")}" }
            checkNotNull(to) { "Invalid SMB location: ${e.getString("to")}" }
            runBlocking { SmbRepository.rename(from, to) }
            Bundle.EMPTY
        }
        else -> super.call(method, arg, extras)
    }

    private companion object {
        const val TAG = "SmbProvider"
    }
}
