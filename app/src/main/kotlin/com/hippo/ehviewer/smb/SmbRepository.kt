package com.hippo.ehviewer.smb

import com.hippo.ehviewer.jni.smbClearCache
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
import com.hippo.ehviewer.jni.smbShares
import com.hippo.ehviewer.jni.smbStat
import com.hippo.ehviewer.jni.smbTest
import com.hippo.ehviewer.jni.smbWrite
import com.hippo.ehviewer.jni.smbWriteClose
import com.hippo.ehviewer.jni.wsdScanFinished
import com.hippo.ehviewer.jni.wsdScanPoll
import com.hippo.ehviewer.jni.wsdScanStart
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The server refused the session, so it wants credentials or better ones.
 *
 * Raised from the native side's mark in the error message: the status code is
 * only visible there, and a JNI exception carries a message and nothing else.
 * A caller shows the credentials dialog for this, and the ordinary "cannot
 * list" message for anything else.
 */
class SmbAuthRequired(message: String) : Exception(message)

private const val AUTH_REQUIRED_MARK = "smb-auth-required"

/**
 * How often a scan is read while it runs.
 *
 * Short enough that a host appearing feels immediate, long enough that the
 * reading is nothing next to the scanning.
 */
private const val POLL_INTERVAL_MILLIS = 200L

/** Kotlin boundary for the Rust SMB client. All calls are blocking JNI calls. */
object SmbRepository {
    data class Entry(val name: String, val isDirectory: Boolean, val size: Long)
    data class Stat(val isDirectory: Boolean, val size: Long, val modifiedMillis: Long)

    /**
     * Translate the native side's mark into a type a caller can match on.
     *
     * Only a message crosses the JNI boundary, so the mark is looked for here,
     * once, instead of every caller parsing the server's words for itself.
     */
    private inline fun <T> translatingAuth(block: () -> T): T = try {
        block()
    } catch (error: Throwable) {
        if (error.message?.contains(AUTH_REQUIRED_MARK) == true) {
            throw SmbAuthRequired(error.message.orEmpty())
        }
        throw error
    }

    /**
     * The credentials for a location: its own, or its host's.
     *
     * A location is keyed by host, port and share, but a credential entered for
     * the host covers everything on it -- a server authenticates the session,
     * not the share -- and that is where one lands when the server refused
     * before a share was named. Without the fallback, a password corrected at
     * the host level would leave every share on that host using the one it
     * replaced.
     */
    private fun credentials(location: SmbLocation): SmbCredentials = SmbCredentialStore.get(location)
        ?: SmbCredentialStore.find(location.host, location.port)
        ?: SmbCredentials("", "", "")

    private inline fun <T> withTarget(location: SmbLocation, block: (SmbCredentials) -> T): T = block(credentials(location))

    suspend fun test(location: SmbLocation, credentials: SmbCredentials): List<Entry> = withContext(Dispatchers.IO) {
        translatingAuth {
            val raw = smbList(location.host, location.port, location.share, location.subPath, credentials.user, credentials.password, credentials.domain)
            parseEntries(raw).sortedWith(listingOrder)
        }
    }

    suspend fun list(location: SmbLocation): List<Entry> = withContext(Dispatchers.IO) {
        withTarget(location) { credentials ->
            translatingAuth {
                parseEntries(smbList(location.host, location.port, location.share, location.subPath, credentials.user, credentials.password, credentials.domain))
                    .sortedWith(listingOrder)
            }
        }
    }

    /**
     * Scan the local network, reporting what it has as it finds it.
     *
     * [onProgress] is called with the hosts so far, from the IO dispatcher, and
     * the returned list is the finished one. A scan cannot be made fast: the
     * windows are what a responder is allowed to take. It does not have to be a
     * blank screen either, which is what this is for -- a host that answered in
     * the first milliseconds is on screen while the rest of the window runs out.
     */
    suspend fun discoverHosts(address: String, onProgress: (List<String>) -> Unit): List<String> = withContext(Dispatchers.IO) {
        wsdScanStart(address)
        // A loop with a `break` rather than a `return@withContext`: the value the
        // block hands back is its last expression, and a `while` is not one.
        var lines: List<String> = emptyList()
        while (true) {
            lines = wsdScanPoll().toList()
            onProgress(lines)
            if (wsdScanFinished()) break
            delay(POLL_INTERVAL_MILLIS)
        }
        lines
    }

    /**
     * The disk shares a server offers. Takes no share, because asking what
     * shares exist is what a caller does when it has no share name yet.
     */
    suspend fun shares(host: String, port: Int): List<String> = withContext(Dispatchers.IO) {
        translatingAuth {
            val credentials = SmbCredentialStore.find(host, port) ?: SmbCredentials("", "", "")
            smbShares(host, port, credentials.user, credentials.password, credentials.domain).toList()
        }
    }

    /**
     * Check credentials against a server, without needing a share to type.
     *
     * The share list is asked for over `IPC$`, the share every server has, so
     * this answers "are these credentials accepted" -- which is the question a
     * refusal raises, and one that can be asked with nothing but a name and a
     * password. When the refusal happened at a known directory, that directory
     * is asked for instead: it is the thing that actually failed, and a session
     * the server accepts is not the same as a share it will open.
     */
    suspend fun testCredentials(
        host: String,
        port: Int,
        location: SmbLocation?,
        credentials: SmbCredentials,
    ): Int = withContext(Dispatchers.IO) {
        translatingAuth {
            if (location == null) {
                smbShares(host, port, credentials.user, credentials.password, credentials.domain).size
            } else {
                smbList(location.host, location.port, location.share, location.subPath, credentials.user, credentials.password, credentials.domain).size
            }
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

    /** Drop everything the SMB cache is holding. */
    fun clearCache() = smbClearCache()

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

/**
 * The order a listing is shown and walked in.
 *
 * A share answers in its own directory order, and on a filesystem with a
 * hashed directory that order is by hash: a real gallery came back as
 * `21, 3, 24, 4, 5, 7, ...`. An archive does not have this problem because
 * libarchive sorts its entries, so a listing that is not sorted makes the two
 * look like different kinds of thing when they are the same pictures.
 *
 * Directories first, then by name, with digit runs compared as numbers so that
 * page 2 comes before page 10.
 */
private val listingOrder = Comparator<SmbRepository.Entry> { a, b ->
    when {
        a.isDirectory != b.isDirectory -> if (a.isDirectory) -1 else 1
        else -> compareNatural(a.name, b.name)
    }
}

private fun compareNatural(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        if (a[i].isDigit() && b[j].isDigit()) {
            val startA = i
            val startB = j
            while (i < a.length && a[i].isDigit()) i++
            while (j < b.length && b[j].isDigit()) j++
            // Leading zeroes carry no value, so the shorter remainder is the
            // smaller number and equal lengths compare digit by digit.
            val digitsA = a.substring(startA, i).trimStart('0')
            val digitsB = b.substring(startB, j).trimStart('0')
            if (digitsA.length != digitsB.length) return digitsA.length - digitsB.length
            val byDigits = digitsA.compareTo(digitsB)
            if (byDigits != 0) return byDigits
        } else {
            val byChar = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
            if (byChar != 0) return byChar
            i++
            j++
        }
    }
    return (a.length - i) - (b.length - j)
}

data class SmbHandle(val value: Long, val size: Long)

data class SmbMemfdHandle(val fd: Int, val size: Long, val key: Long)

data class SmbWriteHandle(val value: Long)
