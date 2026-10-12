/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hippo.ehviewer.spider

import android.webkit.MimeTypeMap
import arrow.fx.coroutines.autoCloseable
import arrow.fx.coroutines.closeable
import arrow.fx.coroutines.parMap
import arrow.fx.coroutines.resourceScope
import com.ehviewer.core.database.model.DownloadArtist
import com.ehviewer.core.files.delete
import com.ehviewer.core.files.exists
import com.ehviewer.core.files.find
import com.ehviewer.core.files.isDirectory
import com.ehviewer.core.files.isSmb
import com.ehviewer.core.files.list
import com.ehviewer.core.files.metadataOrNull
import com.ehviewer.core.files.mkdirs
import com.ehviewer.core.files.moveTo
import com.ehviewer.core.files.openFileDescriptor
import com.ehviewer.core.files.sendTo
import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.EhUtils.getSuitableTitle
import com.hippo.ehviewer.client.ehRequest
import com.hippo.ehviewer.client.getImageKey
import com.hippo.ehviewer.coil.read
import com.hippo.ehviewer.coil.suspendEdit
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.download.allDownloadLocations
import com.hippo.ehviewer.download.defaultDownloadLocation
import com.hippo.ehviewer.download.tempDownloadDir
import com.hippo.ehviewer.download.writableDownloadLocations
import com.hippo.ehviewer.image.PathSource
import com.hippo.ehviewer.jni.archiveWriterAppend
import com.hippo.ehviewer.jni.archiveWriterClose
import com.hippo.ehviewer.jni.archiveWriterOpen
import com.hippo.ehviewer.ktbuilder.diskCache
import com.hippo.ehviewer.smb.SmbLocation
import com.hippo.ehviewer.smb.SmbRepository
import com.hippo.ehviewer.util.FileUtils
import com.hippo.ehviewer.util.copyTo
import com.hippo.ehviewer.util.sha1
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.request
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import okio.Path
import okio.Path.Companion.toOkioPath
import splitties.init.appCtx

class SpiderDen(val info: GalleryInfo) {
    private val gid = info.gid
    var downloadDir: Path? = null
        private set(value) {
            // The listing is derived from the directory, so it has to be rebuilt
            // when the directory is (re)assigned, e.g. when a read-only SMB
            // gallery is switched to a writable location for downloading.
            if (field != value) {
                field = value
                fileCache = null
            }
        }

    private var tempDownloadDir: Path? = null
    private val saveAsCbz = Settings.saveAsCbz.value
    private val archiveName = "$gid.cbz"

    /**
     * Where the archive is built before it is published.
     *
     * A write that is interrupted -- cancelled, killed, or out of space -- leaves
     * this behind instead of something under [archiveName], so nothing that reads
     * an archive can ever open a half-written one. Debris under this name is
     * removed when the gallery is next opened.
     */
    private val archivePartName = "$gid.cbz$ARCHIVE_PART_SUFFIX"

    private val lock = ReentrantReadWriteLock()

    // Search in both directories to maintain compatibility
    private var fileCache: MutableMap<String, Path>? = null

    @Synchronized
    private fun fileCache(): MutableMap<String, Path> = fileCache ?: listOfNotNull(tempDownloadDir, downloadDir)
        .map(Path::list)
        .flatten()
        .associateBy { it.name }
        .toMutableMap()
        .also { fileCache = it }

    private val imageDir
        get() = if (downloadDir?.isSmb == true) {
            // An SMB gallery keeps its loose images on the share: there is no local
            // temp directory to stage them in, and the archive is built from them in
            // place.
            downloadDir
        } else {
            tempDownloadDir.takeIf { saveAsCbz } ?: downloadDir
        }

    constructor(info: GalleryInfo, dirname: String) : this(info) {
        downloadDir = allDownloadLocations.firstNotNullOfOrNull { (it / dirname).takeIf { it.isDirectory } }
            ?: (defaultDownloadLocation / dirname)
    }

    @Volatile
    @SpiderQueen.Mode
    var mode = SpiderQueen.MODE_READ
        private set

    suspend fun setMode(value: Int) {
        mode = value
        if (mode == SpiderQueen.MODE_DOWNLOAD) {
            if (downloadDir == null) {
                downloadDir = getGalleryWritableDownloadDir(info).apply { mkdirs() }
            }
            // An SMB gallery has no local temp directory: its images go straight to
            // the share and the archive is built from them there.
            if (saveAsCbz && tempDownloadDir == null && downloadDir?.isSmb != true) {
                tempDownloadDir = info.tempDownloadDir!!.apply { mkdirs() }
            }
        }
    }

    private fun containInCache(index: Int): Boolean {
        val key = getImageKey(gid, index)
        return sCache.read(key) {} != null
    }

    private fun findImageFile(index: Int, temp: Boolean = false) = lock.read {
        val head = perFilename(index)
        fileCache().entries.firstOrNull { (name) -> name.startsWith(head) && temp == name.endsWith(TEMP_SUFFIX) }?.value
    }

    private fun containInDownloadDir(index: Int): Boolean = findImageFile(index) != null

    private fun copyFromCacheToDownloadDir(index: Int): Boolean {
        val dir = imageDir ?: return false
        val key = getImageKey(gid, index)
        return runCatching {
            sCache.read(key) {
                val extension = metadata.toFile().readText()
                val file = dir.findDownloadFileForIndex(index, extension)
                data sendTo file
            } != null
        }.onFailure {
            logcat(it)
        }.getOrDefault(false)
    }

    operator fun contains(index: Int): Boolean = when (mode) {
        SpiderQueen.MODE_READ -> {
            containInCache(index) || containInDownloadDir(index)
        }
        SpiderQueen.MODE_DOWNLOAD -> {
            containInDownloadDir(index) || copyFromCacheToDownloadDir(index)
        }
        else -> {
            false
        }
    }

    private fun removeFromCache(index: Int): Boolean {
        val key = getImageKey(gid, index)
        return sCache.remove(key)
    }

    private fun removeTempFile(index: Int) = findImageFile(index, temp = true)?.let { file ->
        file.delete()
        lock.write { fileCache().remove(file.name) }
    }

    fun removeIntermediateFiles(index: Int) {
        removeFromCache(index)
        removeTempFile(index)
    }

    fun clearCache() {
        repeat(info.pages, ::removeFromCache)
    }

    private fun Path.findDownloadFileForIndex(index: Int, extension: String) = with(lock) {
        val name = perFilename(index, extension)
        read { fileCache()[name] } ?: resolve(name).also { write { fileCache()[name] = it } }
    }

    suspend fun makeHttpCallAndSaveImage(
        index: Int,
        url: String,
        referer: String?,
        notifyProgress: (Long, Long, Int) -> Unit,
    ) = timeoutBySpeed(
        url,
        { ehRequest(url, referer, builder = it) },
        notifyProgress,
        { resp -> check(saveFromHttpResponse(index, resp)) },
    )

    private suspend inline fun saveResponseMeta(
        index: Int,
        ext: String,
        crossinline fops: suspend (Path) -> Unit,
    ): Boolean {
        imageDir?.run {
            val tempFile = findDownloadFileForIndex(index, "$ext$TEMP_SUFFIX")
            fops(tempFile)
            val file = resolve(tempFile.name.removeSuffix(TEMP_SUFFIX))
            file.delete()
            lock.write { fileCache().remove(file.name) }
            tempFile moveTo file
            lock.write {
                fileCache().remove(tempFile.name)
                fileCache()[file.name] = file
            }
            return true
        }

        // Read Mode, allow save to cache
        if (mode == SpiderQueen.MODE_READ) {
            val key = getImageKey(gid, index)
            return sCache.suspendEdit(key) {
                metadata.toFile().writeText(ext)
                fops(data)
            }
        }
        return false
    }

    private suspend fun saveFromHttpResponse(index: Int, response: HttpResponse): Boolean {
        val url = response.request.url.toString()
        val extension = MimeTypeMap.getFileExtensionFromUrl(url).ifEmpty { "jpg" }
        return saveResponseMeta(index, extension) { outFile ->
            response.bodyAsChannel().copyTo(outFile)
            FileHashRegex.find(url)?.let {
                val expected = it.groupValues[1]
                val actual = outFile.sha1()
                check(expected == actual) { "File hash mismatch: expected $expected, but got $actual\nURL: $url" }
            }
        }
    }

    fun saveToPath(index: Int, file: Path): Boolean {
        val key = getImageKey(gid, index)

        // Read from diskCache first
        sCache.read(key) {
            runCatching {
                data sendTo file
                return true
            }.onFailure {
                logcat(it)
                return false
            }
        }

        // Read from download dir
        runCatching {
            requireNotNull(findImageFile(index)) sendTo file
        }.onFailure {
            logcat(it)
            return false
        }.onSuccess {
            return true
        }
        return false
    }

    fun getExtension(index: Int): String? {
        val key = getImageKey(gid, index)
        return sCache.read(key) { metadata.toFile().readText() }
            ?: findImageFile(index)?.name.let { FileUtils.getExtensionFromFilename(it) }
    }

    fun getImageSource(index: Int): PathSource {
        if (mode == SpiderQueen.MODE_READ) {
            val key = getImageKey(gid, index)
            val snapshot = sCache.openSnapshot(key)
            if (snapshot != null) {
                return object : PathSource, AutoCloseable by snapshot {
                    override val source = snapshot.data
                    override val type by lazy {
                        snapshot.metadata.toFile().readText()
                    }
                }
            }
        }
        val source = requireNotNull(findImageFile(index)) { "Source $index not found!" }
        return object : PathSource {
            override val source = source
            override val type by lazy {
                FileUtils.getExtensionFromFilename(source.name)!!
            }

            override fun close() = Unit
        }
    }

    /**
     * Writes the gallery as a single archive, reporting how many entries have
     * been written so far.
     *
     * The archive is built under [archivePartName] and only renamed onto
     * [archiveName] once every entry is in it. Renaming inside one directory is
     * atomic, so the final name only ever holds a complete archive: an
     * interrupted write leaves debris under the part name and the loose images
     * -- which only [postArchive] removes, and only after this returns -- still
     * whole. That is what makes cancelling the write safe.
     */
    suspend fun archive(progress: suspend (Int, Int) -> Unit = { _, _ -> }) = saveAsCbz && downloadDir?.run {
        val target = resolve(archiveName)
        val part = resolve(archivePartName)
        runCatching {
            archiveTo(part, progress)
            part moveTo target
        }.onFailure {
            part.delete()
            logcat(it)
        }.isFailure
    } == true

    // Postpone this to `SpiderQueen.stop` because files may still be in use by reader
    suspend fun postArchive(archiveWritten: Boolean): Boolean {
        // An SMB archive is written but not cleaned up yet: reading one back needs a
        // mmap-able descriptor, which the share cannot provide, so the loose images
        // are still the only copy the app can open. Drop this guard once the archive
        // reader can take its input from the in-memory cache instead.
        //
        // `archiveWritten` guards the other direction: if this session tried to
        // rewrite the archive and did not finish, what is under [archiveName] is
        // the previous one, and the loose images are the copy this session
        // produced. Deleting them in its favour would throw away the pages that
        // were just downloaded.
        val dir = downloadDir?.takeUnless { it.isSmb }
        val archived = saveAsCbz && archiveWritten && dir?.find(archiveName) != null
        if (archived) {
            dir.list().parMap(concurrency = 10) {
                if (it.name.matches(FileNameRegex)) {
                    it.delete()
                }
            }
            (dir / SpiderQueen.SPIDER_INFO_FILENAME).delete()
            tempDownloadDir?.delete()
        }
        return archived
    }

    suspend fun exportAsCbz(file: Path) {
        downloadDir!!.find(archiveName)?.sendTo(file) ?: archiveTo(file)
    }

    /**
     * One source for the archive: a descriptor, the length to declare for it, and
     * the entry name. The length has to be passed in rather than left to the native
     * writer's `fstat`: a descriptor for a remote file is a pipe, and a pipe's stat
     * reports a FIFO of size 0, which libarchive refuses to archive at all.
     */
    private class ArchiveSource(val fd: Int, val size: Long, val name: String)

    private suspend fun archiveTo(file: Path, progress: suspend (Int, Int) -> Unit = { _, _ -> }) = resourceScope {
        val comicInfoFile = downloadDir!! / COMIC_INFO_FILE
        val comicInfo = closeable {
            if (!comicInfoFile.exists()) {
                writeComicInfo()
            } else if (info.pages == 0) {
                info.pages = readComicInfo(comicInfoFile)!!.pageCount
            }
            comicInfoFile.openFileDescriptor("r")
        }
        val pages = info.pages
        // A share answers one listing with every child's size, so take the sizes
        // from it rather than stat-ing each image: on a gallery of thousands of
        // images that was one round trip per image.
        val smbSizes = downloadDir?.takeIf { it.isSmb }
            ?.let(SmbLocation::parse)
            ?.let { location -> runCatching { SmbRepository.list(location) }.getOrNull() }
            ?.associate { it.name to it.size }
            .orEmpty()
        val arcFd = closeable { file.openFileDescriptor("rw") }
        check(archiveWriterOpen(arcFd.fd) == 0) { "Failed to open archive for writing" }
        var written = 0
        try {
            // Every source keeps an open descriptor until its entry is written,
            // and on a share that descriptor is a pipe with a worker thread
            // behind it. Opening a whole gallery up front is what it cannot
            // afford, so write it in bounded chunks.
            for (start in 0 until pages step ARCHIVE_CHUNK) {
                // Stopping has to be able to stop the writing. It is safe to
                // abandon here because the archive is still under a name nobody
                // reads, and the loose images are untouched.
                coroutineContext.ensureActive()
                // Report before the chunk as well as after it. A gallery smaller
                // than one chunk would otherwise get a single callback once the
                // work is already done, which is indistinguishable from no
                // progress at all -- and that is the common case for a small
                // gallery.
                progress(start, pages)
                val end = minOf(start + ARCHIVE_CHUNK, pages)
                resourceScope {
                    val sources = (start until end).parMap { idx ->
                        val f = autoCloseable { getImageSource(idx) }
                        val source = f.source
                        ArchiveSource(
                            fd = closeable { source.openFileDescriptor("r") }.fd,
                            size = smbSizes[source.name] ?: source.metadataOrNull()?.size ?: -1L,
                            name = perFilename(idx, f.type),
                        )
                    }
                    written += archiveWriterAppend(
                        sources.map(ArchiveSource::fd).toIntArray(),
                        sources.map(ArchiveSource::size).toLongArray(),
                        sources.map(ArchiveSource::name).toTypedArray(),
                        sources.size,
                    )
                }
                progress(end, pages)
            }
            written += archiveWriterAppend(
                intArrayOf(comicInfo.fd),
                longArrayOf(smbSizes[COMIC_INFO_FILE] ?: comicInfoFile.metadataOrNull()?.size ?: -1L),
                arrayOf(COMIC_INFO_FILE),
                1,
            )
        } finally {
            archiveWriterClose()
        }
        // The writer reports failures only through logcat, and a silently empty
        // archive is exactly what a FIFO source used to produce.
        check(written == pages + 1) { "Archive wrote $written of ${pages + 1} entries" }
    }

    suspend fun initDownloadDirIfExist() {
        downloadDir = getGalleryDownloadDir(info).takeIf { it.isDirectory }
        tempDownloadDir = info.tempDownloadDir?.takeIf { it.isDirectory }
        // An archive that was being written when the app went away is debris: it
        // is never read, and leaving it would mean it is rewritten from the start
        // next time anyway. Removing it here is what makes "an interrupted write
        // is recoverable" true rather than just "detectable".
        downloadDir?.resolve(archivePartName)?.delete()
    }

    suspend fun initDownloadDir() {
        downloadDir = getGalleryWritableDownloadDir(info).apply { mkdirs() }
    }

    suspend fun writeComicInfo(fetchMetadata: Boolean = true) {
        downloadDir?.run {
            resolve(COMIC_INFO_FILE).also {
                if (info !is GalleryDetail && fetchMetadata) {
                    EhEngine.fillGalleryListByApi(listOf(info))
                }
                info.getComicInfo().apply {
                    writeComicInfo(this, it)
                    DownloadManager.getDownloadInfo(gid)?.let { downloadInfo ->
                        downloadInfo.artistInfoList = DownloadArtist.from(gid, penciller.orEmpty())
                        EhDB.putDownloadArtist(gid, downloadInfo.artistInfoList)
                    }
                }
            }
        }
    }
}

private val sCache by lazy {
    diskCache {
        directory(appCtx.cacheDir.toOkioPath() / "image_cache")
        maxSizeBytes(Settings.readCacheSize.value.coerceIn(320, 5120).toLong() * 1024 * 1024)
    }
}
private const val TEMP_SUFFIX = ".tmp"

/**
 * Suffix for an archive that is still being written. Distinct from [TEMP_SUFFIX]
 * because the two are cleaned up by different rules, and because a part file
 * must never be mistaken for a finished archive.
 */
private const val ARCHIVE_PART_SUFFIX = ".part"

/**
 * How many images are opened at once while writing an archive. Every source
 * holds an open descriptor until its entry is written, and on a share that
 * descriptor is a pipe with a worker thread behind it, so this bounds threads
 * and descriptors -- it is not a throughput knob.
 */
private const val ARCHIVE_CHUNK = 32
private val FileNameRegex = Regex("^\\d{8}\\.\\w{3,4}")
private val FileHashRegex = Regex("/h/([0-9a-f]{40})")

fun perFilename(index: Int, extension: String = ""): String = "%08d.%s".format(index + 1, extension)

suspend fun GalleryInfo.downloadDirname(): String {
    var dirname = EhDB.getDownloadDirname(gid)
    if (dirname == null) {
        val title = getSuitableTitle(this)
        dirname = FileUtils.sanitizeFilename("$gid-$title")
        EhDB.putDownloadDirname(gid, dirname)
    }
    return dirname
}

/**
 * Where an existing gallery lives. Read-only SMB locations are included on
 * purpose: reading has to find the files wherever they are, and skipping them
 * here made the reader fall through to the (empty) local default directory and
 * re-download everything from the network.
 */
suspend fun getGalleryDownloadDir(info: GalleryInfo): Path {
    val dirname = info.downloadDirname()
    return allDownloadLocations.firstNotNullOfOrNull { (it / dirname).takeIf { it.isDirectory } }
        ?: (defaultDownloadLocation / dirname)
}

/**
 * Where new downloads must be written. Includes SMB shares now that they are
 * writable; an existing gallery directory there is reused as the target.
 */
suspend fun getGalleryWritableDownloadDir(info: GalleryInfo): Path {
    val dirname = info.downloadDirname()
    return writableDownloadLocations.firstNotNullOfOrNull { (it / dirname).takeIf { it.isDirectory } }
        ?: (defaultDownloadLocation / dirname)
}
