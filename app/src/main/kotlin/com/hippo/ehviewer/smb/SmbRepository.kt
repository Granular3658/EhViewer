package com.hippo.ehviewer.smb

import com.hippo.ehviewer.jni.smbClose
import com.hippo.ehviewer.jni.smbInvalidate
import com.hippo.ehviewer.jni.smbList
import com.hippo.ehviewer.jni.smbOpen
import com.hippo.ehviewer.jni.smbRead
import com.hippo.ehviewer.jni.smbStat
import com.hippo.ehviewer.jni.smbTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/** Kotlin boundary for the Rust SMB client. All calls are blocking JNI calls. */
object SmbRepository {
    data class Entry(val name: String, val isDirectory: Boolean, val size: Long)
    data class Stat(val isDirectory: Boolean, val size: Long, val modifiedMillis: Long)

    private fun credentials(location: SmbLocation): SmbCredentials =
        SmbCredentialStore.get(location) ?: SmbCredentials("", "", "")

    private inline fun <T> withTarget(location: SmbLocation, block: (SmbCredentials) -> T): T =
        block(credentials(location))

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

    /** Opens a remote file for the ContentProvider and returns an opaque handle + size. */
    suspend fun open(location: SmbLocation): SmbHandle = withContext(Dispatchers.IO) {
        withTarget(location) { credentials ->
            val raw = smbOpen(location.host, location.port, location.share, location.subPath, credentials.user, credentials.password, credentials.domain)
            require(raw.size >= 2) { "Invalid SMB open result" }
            SmbHandle(raw[0], raw[1])
        }
    }

    fun read(handle: Long, buffer: ByteBuffer, fileOffset: Long, bufferOffset: Int, length: Int): Int =
        smbRead(handle, buffer, fileOffset, bufferOffset, length)

    fun close(handle: Long) = smbClose(handle)

    fun invalidateSessions() = smbInvalidate()

    private fun parseEntries(raw: Array<String>): List<Entry> = raw.mapNotNull { encoded ->
        if (encoded.length < 2 || encoded[1] != ':') return@mapNotNull null
        Entry(encoded.substring(2), encoded[0] == '1', 0L)
    }
}

data class SmbHandle(val value: Long, val size: Long)
