package com.hippo.ehviewer.smb

import com.hippo.ehviewer.jni.smbClose
import com.hippo.ehviewer.jni.smbCreate
import com.hippo.ehviewer.jni.smbDelete
import com.hippo.ehviewer.jni.smbInvalidate
import com.hippo.ehviewer.jni.smbList
import com.hippo.ehviewer.jni.smbMkdir
import com.hippo.ehviewer.jni.smbOpen
import com.hippo.ehviewer.jni.smbOpenMemfd
import com.hippo.ehviewer.jni.smbRead
import com.hippo.ehviewer.jni.smbReleaseMemfd
import com.hippo.ehviewer.jni.smbRename
import com.hippo.ehviewer.jni.smbSetCacheLimitMb
import com.hippo.ehviewer.jni.smbStat
import com.hippo.ehviewer.jni.smbTest
import com.hippo.ehviewer.jni.smbWrite
import com.hippo.ehviewer.jni.smbWriteClose
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Kotlin boundary for the Rust SMB client. All calls are blocking JNI calls. */
object SmbRepository {
    data class Entry(val name: String, val isDirectory: Boolean, val size: Long)
    data class Stat(val isDirectory: Boolean, val size: Long, val modifiedMillis: Long)

    private fun credentials(location: SmbLocation): SmbCredentials = SmbCredentialStore.get(location) ?: SmbCredentials("", "", "")

    private inline fun <T> withTarget(location: SmbLocation, block: (SmbCredentials) -> T): T = block(credentials(location))

    suspend fun test(location: SmbLocation, credentials: SmbCredentials): List<Entry> = withContext(Dispatchers.IO) {
        val raw = smbList(location.host, location.port, location.share, location.subPath, credentials.user, credentials.password, credentials.domain)
        parseEntries(raw)
    }

    suspend fun list(location: SmbLocation): List<Entry> = withContext(Dispatchers.IO) {
        withTarget(location) { credentials ->
            parseEntries(smbList(location.host, location.port, location.share, location.subPath, credentials.user, credentials.password, credentials.domain))
        }
    }

    suspend fun stat(location: SmbLocation): Stat = withContext(Dispatchers.IO) {
        withTarget(location) { credentials ->
            val raw = smbStat(location.host, location.port, location.share, location.subPath, credentials.user, credentials.password, credentials.domain)
            require(raw.size >= 3) { "Invalid SMB stat result" }
            Stat(raw[0] != 0L, raw[1], raw[2])
        }
    }

    /**
     * [stat], but `null` when the path does not exist (or cannot be read).
     *
     * One round trip, and it does not enumerate the children. A gallery holds
     * thousands of images, so testing for one small marker file with a listing
     * costs megabytes; this costs a packet.
     */
    suspend fun statOrNull(location: SmbLocation): Stat? = runCatching { stat(location) }.getOrNull()

    /** Opens a remote file for the ContentProvider and returns an opaque handle + size. */
    suspend fun open(location: SmbLocation): SmbHandle = withContext(Dispatchers.IO) {
        withTarget(location) { credentials ->
            val raw = smbOpen(location.host, location.port, location.share, location.subPath, credentials.user, credentials.password, credentials.domain)
            require(raw.size >= 2) { "Invalid SMB open result" }
            SmbHandle(raw[0], raw[1])
        }
    }

    /**
     * Open a remote file through the in-memory (memfd) cache, returning a real
     * file descriptor that the decode layer can `mmap` like a local file. Returns
     * `null` when the platform/build does not support it, so the caller can fall
     * back to the streaming pipe. The returned [SmbMemfdHandle.key] must be
     * passed to [releaseMemfd] once the descriptor is consumed.
     */
    suspend fun openMemfd(location: SmbLocation): SmbMemfdHandle? = withContext(Dispatchers.IO) {
        withTarget(location) { credentials ->
            val raw = smbOpenMemfd(
                location.host,
                location.port,
                location.share,
                location.subPath,
                credentials.user,
                credentials.password,
                credentials.domain,
            )
            if (raw.size >= 3 && raw[0] >= 0) SmbMemfdHandle(raw[0].toInt(), raw[1], raw[2]) else null
        }
    }

    /** Release a reference obtained from [openMemfd]. */
    fun releaseMemfd(key: Long) = smbReleaseMemfd(key)

    /** Configure the SMB in-memory (memfd) cache cap, in MiB. */
    fun setCacheLimitMb(mb: Int) = smbSetCacheLimitMb(mb)

    fun read(handle: Long, buffer: ByteBuffer, fileOffset: Long, bufferOffset: Int, length: Int): Int = smbRead(handle, buffer, fileOffset, bufferOffset, length)

    /**
     * Reads a whole remote file into memory, or `null` when it cannot be opened
     * or is larger than [maxBytes].
     *
     * This is the suspend counterpart of the provider's pipe: it does its own
     * work on the IO dispatcher instead of blocking one thread while another
     * thread is asked to do that work. A caller may therefore fan out over many
     * files at once without exhausting the shared pool -- the failure mode that
     * `ContentResolver` reads suffer once enough directories are scanned
     * concurrently.
     */
    suspend fun readAll(location: SmbLocation, maxBytes: Int): ByteArray? = withContext(Dispatchers.IO) {
        val handle = runCatching { open(location) }.getOrNull() ?: return@withContext null
        try {
            if (handle.size < 0 || handle.size > maxBytes) return@withContext null
            val size = handle.size.toInt()
            // The native side copies through GetDirectBufferAddress, so the
            // buffer must be direct; a heap buffer would be rejected.
            val buffer = ByteBuffer.allocateDirect(size)
            var offset = 0
            while (offset < size) {
                val n = read(handle.value, buffer, offset.toLong(), offset, size - offset)
                if (n <= 0) break
                offset += n
            }
            ByteArray(offset).also {
                buffer.position(0)
                buffer.get(it)
            }
        } finally {
            close(handle.value)
        }
    }

    /** Creates (truncating) the remote file and returns an opaque write handle. */
    suspend fun create(location: SmbLocation): SmbWriteHandle = withContext(Dispatchers.IO) {
        withTarget(location) { credentials ->
            val handle = smbCreate(
                location.host,
                location.port,
                location.share,
                location.subPath,
                credentials.user,
                credentials.password,
                credentials.domain,
            )
            SmbWriteHandle(handle)
        }
    }

    /** Writes the bytes to the writer opened by [create]. */
    fun write(handle: Long, data: ByteArray) {
        val buf = ByteBuffer.allocateDirect(data.size)
        buf.put(data)
        buf.flip()
        smbWrite(handle, buf, data.size)
    }

    /** Flushes and closes the writer, returning the total bytes written. */
    fun writeClose(handle: Long): Long = smbWriteClose(handle)

    fun close(handle: Long) = smbClose(handle)

    /** Creates a directory on the share. */
    suspend fun mkdir(location: SmbLocation): Unit = withContext(Dispatchers.IO) {
        withTarget(location) { credentials ->
            smbMkdir(
                location.host,
                location.port,
                location.share,
                location.subPath,
                credentials.user,
                credentials.password,
                credentials.domain,
            )
        }
    }

    /** Deletes a file or (empty) directory on the share. */
    suspend fun delete(location: SmbLocation): Unit = withContext(Dispatchers.IO) {
        withTarget(location) { credentials ->
            smbDelete(
                location.host,
                location.port,
                location.share,
                location.subPath,
                credentials.user,
                credentials.password,
                credentials.domain,
            )
        }
    }

    /** Renames `from` to `to` within the same share. */
    suspend fun rename(from: SmbLocation, to: SmbLocation): Unit = withContext(Dispatchers.IO) {
        val credentials = credentials(from)
        smbRename(
            from.host,
            from.port,
            from.share,
            from.subPath,
            credentials.user,
            credentials.password,
            credentials.domain,
            to.subPath,
        )
    }

    fun invalidateSessions() = smbInvalidate()

    private fun parseEntries(raw: Array<String>): List<Entry> = raw.mapNotNull { encoded ->
        // `<dir>:<size>:<name>` -- the name is everything after the second
        // colon, so it may contain colons itself.
        if (encoded.length < 4 || encoded[1] != ':') return@mapNotNull null
        val separator = encoded.indexOf(':', startIndex = 2)
        if (separator < 0) return@mapNotNull null
        Entry(
            name = encoded.substring(separator + 1),
            isDirectory = encoded[0] == '1',
            size = encoded.substring(2, separator).toLongOrNull() ?: 0L,
        )
    }
}

data class SmbHandle(val value: Long, val size: Long)

data class SmbMemfdHandle(val fd: Int, val size: Long, val key: Long)

data class SmbWriteHandle(val value: Long)
