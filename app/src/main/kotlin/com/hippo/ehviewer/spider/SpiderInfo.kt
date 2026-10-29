package com.hippo.ehviewer.spider

import com.ehviewer.core.files.read
import com.ehviewer.core.files.write
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.coil.edit
import com.hippo.ehviewer.coil.read
import com.hippo.ehviewer.ktbuilder.diskCache
import com.hippo.ehviewer.legacy.readLegacySpiderInfo
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import moe.tarsin.coroutines.runSuspendCatching
import okio.Path
import okio.Path.Companion.toOkioPath
import splitties.init.appCtx

@Serializable
class SpiderInfo(
    val gid: Long,

    val token: String,

    val pages: Int,

    val pTokenMap: MutableMap<Int, String> = hashMapOf(),

    var previewPerPage: Int = -1,
) {
    /**
     * The bytes this object was last read from or written as.
     *
     * The marker is rewritten at the end of every session, reading included, and a
     * session that only looks at pages changes none of these fields -- so the write
     * is a network round trip that puts byte-identical content back. Comparing
     * against what is already there skips it.
     *
     * Null means "whatever is on disk is not this object", which is the state a
     * freshly built instance and one restored from the local cache are in: neither
     * read the file, so neither may assume it matches.
     */
    @Transient
    var writtenBytes: ByteArray? = null
}

private val cbor = Cbor {
    ignoreUnknownKeys = true
}

fun SpiderInfo.encode(): ByteArray = cbor.encodeToByteArray(contextOf<SpiderInfo>())

fun SpiderInfo.write(file: Path) = file.write { write(encode()) }

/**
 * Writes the marker, unless the file already holds exactly these bytes.
 *
 * Used for the session-end rewrite and nothing else: the local cache copy is not
 * this, because skipping its write leaves an empty entry behind, and a marker that
 * is only read has nothing to save anyway.
 */
fun SpiderInfo.writeIfChanged(file: Path): Boolean {
    val bytes = encode()
    if (bytes.contentEquals(writtenBytes)) {
        logcat("SpiderInfo") { "unchanged, not rewritten: $file" }
        return false
    }
    file.write { write(bytes) }
    writtenBytes = bytes
    return true
}

fun SpiderInfo.saveToCache() {
    runSuspendCatching {
        spiderInfoCache.edit(gid.toString()) {
            write(data)
        }
    }.onFailure {
        logcat(it)
    }
}

private val spiderInfoCache by lazy {
    diskCache {
        directory(appCtx.cacheDir.toOkioPath() / "spider_info_v2_1")
        maxSizeBytes(20 * 1024 * 1024)
    }
}

fun readFromCache(gid: Long): SpiderInfo? = spiderInfoCache.read(gid.toString()) {
    runCatching {
        data.read { cbor.decodeFromByteArray<SpiderInfo>(readByteArray()) }
    }.onFailure {
        logcat(it)
    }.getOrNull()
}

fun readCompatFromPath(file: Path): SpiderInfo? = runCatching {
    val bytes = file.read { readByteArray() }
    cbor.decodeFromByteArray<SpiderInfo>(bytes).apply { writtenBytes = bytes }
}.getOrNull() ?: runCatching {
    file.readLegacySpiderInfo()
}.getOrNull()

/**
 * Same as [readCompatFromPath], but for bytes that were already fetched -- an
 * SMB `.ehviewer` is read straight from the share, never through a descriptor.
 */
fun readCompatFromBytes(bytes: ByteArray): SpiderInfo? = runCatching {
    cbor.decodeFromByteArray<SpiderInfo>(bytes).apply { writtenBytes = bytes }
}.getOrNull() ?: runCatching {
    Buffer().apply { write(bytes) }.readLegacySpiderInfo()
}.getOrNull()
