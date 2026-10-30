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
package com.hippo.ehviewer.download

import android.net.Uri
import android.os.SystemClock
import android.util.SparseLongArray
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisallowComposableCalls
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.util.size
import arrow.fx.coroutines.parMapNotNull
import com.ehviewer.core.data.model.asEntity
import com.ehviewer.core.database.model.DownloadArtist
import com.ehviewer.core.database.model.DownloadInfo
import com.ehviewer.core.database.model.DownloadLabel
import com.ehviewer.core.files.delete
import com.ehviewer.core.files.find
import com.ehviewer.core.files.isDirectory
import com.ehviewer.core.files.isSmb
import com.ehviewer.core.files.toOkioPath
import com.ehviewer.core.files.toUri
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.preferences.edit
import com.ehviewer.core.util.logcat
import com.ehviewer.core.util.mapNotNull
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.exception.FatalException
import com.hippo.ehviewer.smb.SmbLocation
import com.hippo.ehviewer.smb.SmbRepository
import com.hippo.ehviewer.spider.COMIC_INFO_FILE
import com.hippo.ehviewer.spider.SpeedTracker
import com.hippo.ehviewer.spider.SpiderQueen
import com.hippo.ehviewer.spider.SpiderQueen.Companion.SPIDER_INFO_FILENAME
import com.hippo.ehviewer.spider.SpiderQueen.OnSpiderListener
import com.hippo.ehviewer.spider.downloadDirname
import com.hippo.ehviewer.spider.readComicInfo
import com.hippo.ehviewer.spider.readCompatFromPath
import com.hippo.ehviewer.spider.toSimpleTags
import com.hippo.ehviewer.util.AppConfig
import com.hippo.ehviewer.util.insertWith
import com.hippo.ehviewer.util.runAssertingNotMainThread
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import moe.tarsin.coroutines.runSuspendCatching
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath
import splitties.init.appCtx

object DownloadManager : OnSpiderListener, CoroutineScope {
    override val coroutineContext = Dispatchers.IO + Job()

    // All download info list
    private val allInfoList = runAssertingNotMainThread { EhDB.getAllDownloadInfo() as MutableList }

    val downloadInfoList: List<DownloadInfo>
        get() = allInfoList

    // All download info map
    private val mAllInfoMap = allInfoList.associateBy { it.gid } as MutableMap<Long, DownloadInfo>

    // All labels without default label
    // Create the SnapshotStateList first in case the database query is time-consuming
    val labelList = mutableStateListOf<DownloadLabel>().apply {
        addAll(runAssertingNotMainThread { EhDB.getAllDownloadLabelList() })
    }

    // Store download info wait to start
    private val mWaitList = ArrayDeque<DownloadInfo>()
    private var mDownloadListener: DownloadListener? = null
    private var mCurrentTask: DownloadInfo? = null
    private var mCurrentSpider: SpiderQueen? = null

    private val mutableNotifyFlow = MutableSharedFlow<DownloadInfo>(extraBufferCapacity = 1)
    val notifyFlow = mutableNotifyFlow.asSharedFlow()

    private val mutex = Mutex()

    private val sortMutex = Mutex()
    private val sortMode
        get() = SortMode.from(Settings.downloadSortMode.value)

    var isInitialized by mutableStateOf(false)
        private set

    init {
        launch {
            val mode = sortMode
            if (mode != SortMode.Default) sortDownloads(mode)
            isInitialized = true
        }
    }

    fun containLabel(label: String?): Boolean {
        if (label == null) {
            return false
        }
        return labelList.any { it.label == label }
    }

    fun containDownloadInfo(gid: Long) = mAllInfoMap.containsKey(gid)

    fun getDownloadInfo(gid: Long) = mAllInfoMap[gid]

    fun getDownloadState(gid: Long): Int {
        val info = mAllInfoMap[gid]
        return info?.state ?: DownloadInfo.STATE_INVALID
    }

    fun setDownloadListener(listener: DownloadListener?) {
        mDownloadListener = listener
    }

    private suspend fun ensureDownload() {
        mutex.withLock {
            if (mCurrentTask != null) {
                // Only one download
                return
            }

            // Get download from wait list
            if (!mWaitList.isEmpty()) {
                val info = mWaitList.removeFirst()
                // An archive transfer is already writing into this gallery's
                // directory; a page download now would put pages beside a file
                // that the transfer's cleanup deletes them from.
                if (isArchiveDownloading(info.gid)) {
                    mWaitList.addLast(info)
                    return
                }
                mCurrentTask = info
                with(info) {
                    archiveFile?.let {
                        return onFinish(pages, pages, pages, failed = false)
                    }
                    state = DownloadInfo.STATE_DOWNLOAD
                    speed = -1
                    remaining = -1
                    total = -1
                    finished = 0
                    downloaded = 0
                    legacy = -1
                }
                val spider = SpiderQueen.obtainSpiderQueen(info, SpiderQueen.MODE_DOWNLOAD)
                mCurrentSpider = spider
                spider.addOnSpiderListener(this)
                // Update in DB
                EhDB.putDownloadInfo(info)
                // Start speed count
                mSpeedReminder.start()
                // Notify start downloading
                mDownloadListener?.onStart(info)
                // Notify state update
                mutableNotifyFlow.emit(info)
            }
        }
    }

    suspend fun startDownload(galleryInfo: BaseGalleryInfo, label: String?) {
        if (mCurrentTask?.gid == galleryInfo.gid) {
            // It is current task
            return
        }
        // Already being downloaded, as an archive. It is in the list and shows its
        // own progress; a page download of the same gallery would write into the
        // same directory.
        if (isArchiveDownloading(galleryInfo.gid)) {
            return
        }

        // Check in download list
        var info = mAllInfoMap[galleryInfo.gid]
        if (info != null) { // Get it in download list
            if (info.state != DownloadInfo.STATE_WAIT) {
                // Set state DownloadInfo.STATE_WAIT
                info.state = DownloadInfo.STATE_WAIT
                // Add to wait list
                mWaitList.add(info)
                // Update in DB
                EhDB.putDownloadInfo(info)
                // Notify state update
                mutableNotifyFlow.emit(info)
                // Make sure download is running
                ensureDownload()
            }
        } else {
            // It is new download info
            info = DownloadInfo(galleryInfo.asEntity(), galleryInfo.downloadDirname())
            info.label = label
            info.state = DownloadInfo.STATE_WAIT
            // Add to all download list and map
            sortMutex.withLock {
                allInfoList.insertWith(info, sortMode.comparator())
            }
            mAllInfoMap[galleryInfo.gid] = info

            // Add to wait list
            mWaitList.add(info)

            // Save to
            EhDB.putDownloadInfo(info)

            // Notify
            mutableNotifyFlow.emit(info)
            // Make sure download is running
            ensureDownload()

            // Add it to history
            EhDB.putHistoryInfo(info)
        }
    }

    suspend fun startRangeDownload(gidList: LongArray) {
        val updateList = gidList.mapNotNull { mAllInfoMap[it] }
            .filter {
                when (it.state) {
                    DownloadInfo.STATE_NONE, DownloadInfo.STATE_FAILED, DownloadInfo.STATE_FINISH -> true
                    else -> false
                }
            }
        if (updateList.isNotEmpty()) {
            updateList.onEach {
                it.state = DownloadInfo.STATE_WAIT
                EhDB.putDownloadInfo(it)
                mutableNotifyFlow.emit(it)
            }
            mWaitList.addAll(updateList)
            ensureDownload()
        }
    }

    @Stable
    @Composable
    fun collectDownloadState(gid: Long): State<Int> = remember {
        notifyFlow.transform { if (it.gid == gid) emit(getDownloadState(gid)) }
    }.collectAsState(getDownloadState(gid))

    @Stable
    @Composable
    fun collectContainDownloadInfo(gid: Long): State<Boolean> = remember {
        notifyFlow.transform { if (it.gid == gid) emit(containDownloadInfo(gid)) }
    }.collectAsState(containDownloadInfo(gid))

    @Stable
    @Composable
    inline fun <T> updatedDownloadInfo(info: DownloadInfo, crossinline transform: @DisallowComposableCalls DownloadInfo.() -> T): T = remember {
        notifyFlow.transform { if (it.gid == info.gid) emit(transform(it)) }
    }.collectAsState(transform(info)).value

    suspend fun startAllDownload() {
        val updateList = sortMutex.withLock {
            allInfoList.filter {
                when (it.state) {
                    DownloadInfo.STATE_NONE, DownloadInfo.STATE_FAILED -> true
                    else -> false
                }
            }
        }
        if (updateList.isNotEmpty()) {
            updateList.onEach {
                it.state = DownloadInfo.STATE_WAIT
                EhDB.putDownloadInfo(it)
                mutableNotifyFlow.emit(it)
            }
            mWaitList.addAll(updateList)
            ensureDownload()
        }
    }

    suspend fun addDownload(downloadInfoList: List<DownloadInfo>) = sortMutex.withLock {
        val comparator = sortMode.comparator()
        downloadInfoList.forEach { info ->
            if (containDownloadInfo(info.gid)) return@forEach

            // Ensure download state
            if (DownloadInfo.STATE_WAIT == info.state || DownloadInfo.STATE_DOWNLOAD == info.state) {
                info.state = DownloadInfo.STATE_NONE
            }

            // Add to all download list and map
            allInfoList.insertWith(info, comparator)
            mAllInfoMap[info.gid] = info

            // Save to
            EhDB.putDownloadInfo(info)
            EhDB.putDownloadArtist(info.gid, info.artistInfoList)
        }
    }

    suspend fun addDownloadLabel(downloadLabelList: List<DownloadLabel>) {
        val offset = downloadLabelList.size
        downloadLabelList.forEachIndexed { index, label ->
            if (!containLabel(label.label)) {
                label.position = index + offset
                labelList.add(EhDB.addDownloadLabel(label))
            }
        }
    }

    /**
     * Registers a download found on disk, and says whether it was new.
     *
     * `false` means the gallery was already in the list: the caller has just recorded
     * the directory on the entry that is already there (`recordDownloadDir`), so there
     * is nothing to add. Inserting a second entry is not harmless -- `insertWith`
     * never replaces an equal element, and the downloads screen renders this list with
     * `key = { it.gid }`, so two entries for one gid make it throw. Same guard as
     * `addDownload` uses.
     */
    suspend fun restoreDownload(
        galleryInfo: BaseGalleryInfo,
        dirname: String,
        location: String? = null,
        localThumb: String? = null,
    ): Boolean {
        if (containDownloadInfo(galleryInfo.gid)) return false

        val info = DownloadInfo(galleryInfo.asEntity(), dirname, location)
        info.state = DownloadInfo.STATE_NONE
        // What the restore scan saw next to the pages, so the first card drawn
        // does not have to ask the share which thumbnail file it wrote.
        info.localThumb = localThumb

        // Add to all download list and map
        sortMutex.withLock {
            allInfoList.insertWith(info, sortMode.comparator())
        }
        mAllInfoMap[galleryInfo.gid] = info

        // Save to
        EhDB.putDownloadInfo(info)

        // Notify
        mutableNotifyFlow.emit(info)
        return true
    }

    /**
     * The galleries with an archive request queued or running.
     *
     * Session state on purpose: a transfer cannot be continued, so nothing about
     * it is worth persisting. It exists to keep the page downloader off a gallery
     * whose directory the archive transfer is writing into, and to refuse a second
     * archive request for the same gallery -- the queue would otherwise hold it
     * twice and download it twice.
     */
    private val archiveDownloads = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    /** Byte progress per archive transfer, session only; see [archiveByteProgress]. */
    private val archiveBytes = java.util.concurrent.ConcurrentHashMap<Long, LongArray>()

    fun isArchiveDownloading(gid: Long) = gid in archiveDownloads

    /**
     * Claims the gallery for an archive transfer, or reports that one already has it.
     *
     * Called synchronously from the service's `onStartCommand`, before anything is
     * launched: the check and the claim have to be one step, or two taps that
     * arrive close together both pass the check and the gallery is queued twice.
     */
    fun claimArchiveDownload(gid: Long) = archiveDownloads.add(gid)

    /** Gives the claim back when a request never reaches the queue. */
    fun releaseArchiveDownload(gid: Long) {
        archiveDownloads -= gid
    }

    /**
     * Puts the gallery in the download list when its request is queued.
     *
     * At enqueue time, not at start time: a queued archive is waiting for the one
     * in front of it, and a list that only shows the running one is a list the
     * user cannot see their own work in.
     */
    fun enqueueArchiveDownload(gid: Long) {
        val info = mAllInfoMap[gid] ?: return
        info.state = DownloadInfo.STATE_WAIT
        info.finished = 0
        info.downloaded = 0
        info.total = 0
        mutableNotifyFlow.tryEmit(info)
    }

    /**
     * Shows the gallery as downloading while its archive is fetched.
     *
     * The percentage goes in [DownloadInfo.finished] with [DownloadInfo.total]
     * pinned to 100: those counters are pages everywhere else, and a byte count
     * would not fit an `Int` for the multi-gigabyte archives this app produces.
     * Nothing here is written to the database -- the row keeps saying "not
     * started" until [markArchiveDownloaded], so a killed process cannot leave a
     * row that claims to be downloading with nothing behind it.
     */
    fun beginArchiveDownload(gid: Long) {
        val info = mAllInfoMap[gid] ?: return
        info.state = DownloadInfo.STATE_DOWNLOAD
        info.finished = 0
        info.downloaded = 0
        info.total = ARCHIVE_PROGRESS_STEPS
        mutableNotifyFlow.tryEmit(info)
    }

    fun updateArchiveProgress(gid: Long, percent: Int, speed: Long, done: Long, total: Long) {
        val info = mAllInfoMap[gid] ?: return
        // Kept before the "nothing changed" check: the percentage can sit still
        // while bytes keep arriving, and the size next to it should not.
        archiveBytes[gid] = longArrayOf(done, total)
        val value = percent.coerceIn(0, ARCHIVE_PROGRESS_STEPS)
        if (info.finished == value && info.speed == speed) return
        info.finished = value
        info.downloaded = value
        info.speed = speed
        mutableNotifyFlow.tryEmit(info)
    }

    /**
     * How far an archive transfer has got in bytes, or null if none is running.
     *
     * Session state, like the percentage it accompanies: the counters on
     * [DownloadInfo] are pages, and they are `Int`, which a multi-gigabyte archive
     * overflows. The notification has always had the real numbers; this is how the
     * download list gets at them.
     */
    fun archiveByteProgress(gid: Long): Pair<Long, Long>? = archiveBytes[gid]?.let { it[0] to it[1] }

    /**
     * What a conversion is doing to one gallery, for the list to draw.
     *
     * Kept here rather than in the service because the row that asks is a row in
     * the download list, and a row cannot ask a service: this is the one place
     * both of them can see.
     */
    enum class ConvertState { QUEUED, RUNNING }

    /** How far a conversion has got, in the units a transfer is reported in. */
    data class ConvertProgress(val state: ConvertState, val format: Int, val done: Long, val total: Long, val speed: Long)

    private val convertProgress = MutableStateFlow<Map<Long, ConvertProgress>>(emptyMap())

    /** The galleries a batch is waiting to reach, so the list can say so now. */
    fun markConvertQueued(gids: Collection<Long>, format: Int) {
        convertProgress.update { current ->
            current + gids.associateWith { ConvertProgress(ConvertState.QUEUED, format, 0, 0, 0) }
        }
    }

    fun updateConvertProgress(gid: Long, format: Int, done: Long, total: Long, speed: Long) {
        val value = ConvertProgress(ConvertState.RUNNING, format, done, total, speed)
        convertProgress.update { if (it[gid] == value) it else it + (gid to value) }
    }

    /** Whether a conversion holds this gallery, queued or being written. */
    fun isConverting(gid: Long) = convertProgress.value.containsKey(gid)

    fun clearConvertProgress(gid: Long) {
        convertProgress.update { it - gid }
    }

    fun clearAllConvertProgress() {
        convertProgress.value = emptyMap()
    }

    // No initial value read out of the flow: a flow emits what it holds as soon as
    // collection starts, so the only thing an initial would change is the frame
    // before that -- and reading a StateFlow during composition is what the lint
    // rule is about.
    @Stable
    @Composable
    fun collectConvertProgress(gid: Long): State<ConvertProgress?> = remember(gid) { convertProgress.map { it[gid] } }.collectAsState(null)

    /** The transfer ended without an archive: the row goes back to how a restore leaves it. */
    fun abandonArchiveDownload(gid: Long) {
        val info = mAllInfoMap[gid] ?: return
        if (info.state == DownloadInfo.STATE_FINISH) return
        info.state = DownloadInfo.STATE_NONE
        info.finished = 0
        info.downloaded = 0
        info.total = 0
        mutableNotifyFlow.tryEmit(info)
    }

    /** The transfer is over, either way: the gallery may be downloaded as pages again. */
    fun finishArchiveDownload(gid: Long) {
        archiveDownloads -= gid
        archiveBytes -= gid
        launch { ensureDownload() }
    }

    /**
     * Marks a gallery as downloaded because its archive is complete.
     *
     * The archive holds every page, so there is nothing left to fetch: leaving the
     * state alone would keep the gallery in the list looking unfinished and let the
     * downloader start fetching pages the archive already has.
     */
    suspend fun markArchiveDownloaded(gid: Long, pages: Int) {
        val info = mAllInfoMap[gid] ?: return
        info.finished = pages
        info.downloaded = pages
        info.total = pages
        info.legacy = 0
        info.state = DownloadInfo.STATE_FINISH
        EhDB.putDownloadInfo(info)
        mutableNotifyFlow.emit(info)
    }

    /**
     * Records a download's directory: its name, and the location holding it.
     *
     * Written in the database and in the list already in memory, because the two
     * are read by different things and would otherwise disagree -- the counts
     * beside each grouping come from the database, while the list decides what
     * belongs in a group, and what a download's files are looked up by, from the
     * objects in memory. Writing only the database leaves a download that has just
     * been restored counted under a group it is missing from, and reading an
     * archive out of the directory it used to be in, until the next start.
     */
    suspend fun recordDownloadDir(gid: Long, dirname: String, location: String) {
        EhDB.putDownloadLocation(gid, dirname, location)
        mAllInfoMap[gid]?.let { info ->
            if (info.dirname != dirname || info.location != location) {
                info.dirname = dirname
                info.location = location
                mutableNotifyFlow.emit(info)
            }
        }
    }

    suspend fun stopDownload(gid: Long) {
        val info = stopDownloadInternal(gid)
        if (info != null) {
            // Update listener
            mutableNotifyFlow.emit(info)
            // Ensure download
            ensureDownload()
        }
    }

    suspend fun stopRangeDownload(gidList: LongArray) {
        stopRangeDownloadInternal(gidList)

        // Update listener
        gidList.mapNotNull { mAllInfoMap[it] }.forEach {
            mutableNotifyFlow.emit(it)
        }

        // Ensure download
        ensureDownload()
    }

    suspend fun stopAllDownload() {
        mutex.withLock {
            // Stop all in wait list
            for (info in mWaitList) {
                info.state = DownloadInfo.STATE_NONE
                // Update in DB
                EhDB.putDownloadInfo(info)

                // Notify
                mutableNotifyFlow.emit(info)
            }
            mWaitList.clear()

            // Stop current
            val info = stopCurrentDownloadInternal()
            info?.let { mutableNotifyFlow.emit(it) }
        }
    }

    suspend fun deleteDownload(gid: Long, deleteFiles: Boolean = false) {
        stopDownloadInternal(gid)
        val info = mAllInfoMap[gid]
        if (info != null) {
            // Remove from DB
            EhDB.removeDownloadInfo(info)

            // Remove all list and map
            sortMutex.withLock {
                allInfoList.remove(info)
            }
            mAllInfoMap.remove(info.gid)

            // Update listener
            mutableNotifyFlow.emit(info)

            // Ensure download
            ensureDownload()

            if (deleteFiles) {
                info.downloadDir?.delete()
                info.tempDownloadDir?.delete()
                EhDB.removeDownloadDirname(info.gid)
                // The directory just got removed, drop its cached resolution
                info.dirname?.let { downloadDirCache.remove("${info.location.orEmpty()}/$it") }
            }
        }
    }

    suspend fun deleteRangeDownload(gidList: LongArray) {
        stopRangeDownloadInternal(gidList)
        val list = gidList.mapNotNull { gid ->
            mAllInfoMap.remove(gid)
        }
        EhDB.removeDownloadInfo(list)
        sortMutex.withLock {
            allInfoList.removeAll(list.toSet())
        }

        // Update listener
        list.forEach { mutableNotifyFlow.emit(it) }

        // Ensure download
        ensureDownload()
    }

    suspend fun sortDownloads(mode: SortMode) = sortMutex.withLock {
        allInfoList.sortWith(mode.comparator())
    }

    suspend fun resetAllReadingProgress() = runCatching {
        EhDB.clearProgressInfo()
    }.onFailure { logcat(it) }

    // Update in DB
    // Update listener
    // No ensureDownload
    private suspend fun stopDownloadInternal(gid: Long): DownloadInfo? {
        // Check current task
        if (mCurrentTask?.gid == gid) {
            // Stop current
            return stopCurrentDownloadInternal()
        }
        val iterator = mWaitList.iterator()
        while (iterator.hasNext()) {
            val info = iterator.next()
            if (info.gid == gid) {
                // Remove from wait list
                iterator.remove()
                // Update state
                info.state = DownloadInfo.STATE_NONE
                // Update in DB
                EhDB.putDownloadInfo(info)
                return info
            }
        }
        return null
    }

    // Update in DB
    // Update mDownloadListener
    private suspend fun stopCurrentDownloadInternal(): DownloadInfo? {
        val info = mCurrentTask
        val spider = mCurrentSpider
        // Release spider
        if (spider != null) {
            spider.removeOnSpiderListener(this@DownloadManager)
            SpiderQueen.releaseSpiderQueen(spider, SpiderQueen.MODE_DOWNLOAD)
        }
        mCurrentTask = null
        mCurrentSpider = null
        // Stop speed reminder
        mSpeedReminder.stop()
        if (info == null) {
            return null
        }

        // Update state
        info.state = DownloadInfo.STATE_NONE
        // Update in DB
        EhDB.putDownloadInfo(info)
        // Listener
        mDownloadListener?.onCancel(info)
        return info
    }

    // Update in DB
    // Update mDownloadListener
    private suspend fun stopRangeDownloadInternal(gidList: LongArray) {
        // Two way
        if (gidList.size < mWaitList.size) {
            for (element in gidList) {
                stopDownloadInternal(element)
            }
        } else {
            // Check current task
            if (mCurrentTask != null && gidList.contains(mCurrentTask!!.gid)) {
                // Stop current
                stopCurrentDownloadInternal()
            }

            // Check all in wait list
            val iterator = mWaitList.iterator()
            while (iterator.hasNext()) {
                val info = iterator.next()
                if (gidList.contains(info.gid)) {
                    // Remove from wait list
                    iterator.remove()
                    // Update state
                    info.state = DownloadInfo.STATE_NONE
                    // Update in DB
                    EhDB.putDownloadInfo(info)
                }
            }
        }
    }

    /**
     * @param label Not allow new label
     */
    suspend fun changeLabel(list: Collection<DownloadInfo>, label: String?) {
        if (null != label && !containLabel(label)) {
            logcat(TAG, LogPriority.ERROR) { "Not exits label: $label" }
            return
        }
        list.forEach {
            it.label = label
        }
        EhDB.updateDownloadInfo(list)
    }

    suspend fun addLabel(label: String?) {
        if (label == null || containLabel(label)) {
            return
        }
        labelList.add(EhDB.addDownloadLabel(DownloadLabel(label, labelList.size)))
    }

    suspend fun renameLabel(from: String, to: String) {
        val index = labelList.indexOfFirst { it.label == from }
        if (index != -1) {
            val exist = labelList.removeAt(index)
            val new = exist.copy(label = to)
            labelList.add(index, new)
            EhDB.updateDownloadLabel(new)
            sortMutex.withLock {
                allInfoList.forEach {
                    if (it.label == from) {
                        it.label = to
                    }
                }
            }
        }
    }

    suspend fun deleteLabel(label: String) {
        with(labelList) {
            val index = indexOfFirst { it.label == label }
            val item = get(index)
            EhDB.removeDownloadLabel(item)
            subList(index + 1, size).forEach {
                it.position--
            }
            removeAt(index)
        }
        sortMutex.withLock {
            allInfoList.forEach {
                if (it.label == label) {
                    it.label = null
                }
            }
        }
    }

    suspend fun readMetadataFromLocal() {
        val list = sortMutex.withLock {
            allInfoList.mapNotNull {
                val updateGallery = it.pages == 0 || it.simpleTags == null
                val updateArtist = it.artistInfoList.isEmpty()
                if (updateGallery || updateArtist) {
                    Triple(it, updateGallery, updateArtist)
                } else {
                    null
                }
            }
        }
        // Which marker each directory holds, asked once per share before the fan
        // out: this runs at every start, over every download the list knows.
        val presence = probeNames(list.mapNotNull { it.first.downloadDir }, MARKER_FILENAMES)
        val updated = list.parMapNotNull(concurrency = 5) { (info, updateGallery, updateArtist) ->
            val dir = info.downloadDir ?: return@parMapNotNull null
            val known = presence[dir.toString()]
            // Known absent: skip the open instead of paying for one that fails.
            val comicInfo = if (known?.get(COMIC_INFO_FILE) != false) readComicInfo(dir / COMIC_INFO_FILE) else null
            if (comicInfo != null) {
                val galleryInfo = if (updateGallery) {
                    info.pages = comicInfo.pageCount
                    info.simpleTags = comicInfo.toSimpleTags()
                    info.galleryInfo
                } else {
                    null
                }

                val artistList = if (updateArtist && comicInfo.penciller != null) {
                    info.artistInfoList = DownloadArtist.from(info.gid, comicInfo.penciller)
                    info.gid to info.artistInfoList
                } else {
                    null
                }

                if (galleryInfo != null || artistList != null) {
                    galleryInfo to artistList
                } else {
                    null
                }
            } else if (info.pages == 0) {
                val marker = if (known?.get(SPIDER_INFO_FILENAME) != false) readCompatFromPath(dir / SPIDER_INFO_FILENAME) else null
                val galleryInfo = marker?.run {
                    info.pages = pages
                    info.galleryInfo
                }

                galleryInfo?.let { it to null }
            } else {
                null
            }
        }

        val galleryInfoList = buildList {
            updated.forEach { (info, artists) ->
                info?.let {
                    add(it)
                }
                artists?.let { (gid, updateList) ->
                    EhDB.putDownloadArtist(gid, updateList)
                }
            }
        }
        if (galleryInfoList.isNotEmpty()) EhDB.updateGalleryInfo(galleryInfoList)
    }

    val isIdle: Boolean
        get() = mCurrentTask == null && mWaitList.isEmpty()

    override fun onGetPages(pages: Int) {
        launch {
            mCurrentTask?.let { info ->
                info.total = pages
                mutableNotifyFlow.emit(info)
            } ?: logcat(TAG, LogPriority.ERROR) { "Current task is null, but it should not be" }
        }
    }

    override fun onFatal(error: FatalException) {
        launch {
            mDownloadListener?.onFatal(error)
            stopAllDownload()
        }
    }

    override fun onPageDownload(
        index: Int,
        contentLength: Long,
        receivedSize: Long,
        bytesRead: Int,
    ) {
        launch {
            mSpeedReminder.onDownload(index, contentLength, receivedSize, bytesRead)
        }
    }

    override fun onPageSuccess(index: Int, finished: Int, downloaded: Int, total: Int) {
        launch {
            mSpeedReminder.onDone(index)
            mCurrentTask?.let { info ->
                info.finished = finished
                info.downloaded = downloaded
                info.total = total
                mDownloadListener?.onDownload(info)
                mutableNotifyFlow.emit(info)
            } ?: logcat(TAG, LogPriority.ERROR) { "Current task is null, but it should not be" }
        }
    }

    override fun onPageFailure(
        index: Int,
        error: String?,
        finished: Int,
        downloaded: Int,
        total: Int,
    ) {
        launch {
            mSpeedReminder.onDone(index)
            mCurrentTask?.let { info ->
                info.finished = finished
                info.downloaded = downloaded
                info.total = total
                mutableNotifyFlow.emit(info)
            } ?: logcat(TAG, LogPriority.ERROR) { "Current task is null, but it should not be" }
        }
    }

    /**
     * The archive write that follows a download, for the row to draw.
     *
     * It runs inside the download session, so the row's own counters are already
     * at "every page done" while the bytes are still being written -- which reads
     * as finished. This is what the row shows instead for those seconds, in the
     * units the write is actually reported in.
     */
    private val archiveWrite = MutableStateFlow<Map<Long, ConvertProgress>>(emptyMap())
    private var writeSampleAt = 0L
    private var writeSampleBytes = 0L
    private var writeSpeed = 0L

    override fun onArchiveProgress(done: Long, total: Long, format: Int) {
        val gid = mCurrentTask?.gid
        val now = SystemClock.elapsedRealtime()
        if (writeSampleAt == 0L || done < writeSampleBytes) {
            writeSampleAt = now
            writeSampleBytes = done
            writeSpeed = 0
        } else if (now - writeSampleAt >= WRITE_SPEED_SAMPLE_MILLIS) {
            writeSpeed = ((done - writeSampleBytes) * 1000 / (now - writeSampleAt)).coerceAtLeast(0)
            writeSampleAt = now
            writeSampleBytes = done
        }
        if (gid != null) {
            val value = ConvertProgress(ConvertState.RUNNING, format, done, total, writeSpeed)
            archiveWrite.update { if (it[gid] == value) it else it + (gid to value) }
        }
        // Straight through, not via launch. The service posts this on a conflated
        // channel behind a one-second throttle, so an async hop can easily land
        // after onFinish has cancelled the notification -- and then the progress
        // is never seen at all.
        mDownloadListener?.onArchiveProgress(done, total, writeSpeed, format)
    }

    @Stable
    @Composable
    fun collectArchiveWrite(gid: Long): State<ConvertProgress?> = remember(gid) { archiveWrite.map { it[gid] } }.collectAsState(null)

    override fun onFinish(finished: Int, downloaded: Int, total: Int, failed: Boolean) {
        launch {
            mSpeedReminder.onFinish()
            mCurrentSpider?.let { spider ->
                mCurrentSpider = null
                spider.removeOnSpiderListener(DownloadManager)
                SpiderQueen.releaseSpiderQueen(spider, SpiderQueen.MODE_DOWNLOAD)
            }
            mCurrentTask?.let { info ->
                mCurrentTask = null
                // The write is over with the session: the row goes back to what the
                // download state says.
                archiveWrite.update { it - info.gid }
                writeSampleAt = 0
                mSpeedReminder.stop()
                info.finished = finished
                info.downloaded = downloaded
                info.total = total
                info.legacy = total - finished
                info.state = if (failed) DownloadInfo.STATE_FAILED else DownloadInfo.STATE_FINISH
                EhDB.putDownloadInfo(info)
                mDownloadListener?.onFinish(info)
                mutableNotifyFlow.emit(info)
                ensureDownload()
            } ?: logcat(TAG, LogPriority.ERROR) { "Current task is null, but it should not be" }
        }
    }

    interface DownloadListener {
        /**
         * Fatal error
         */
        fun onFatal(error: FatalException)

        /**
         * Start download
         */
        fun onStart(info: DownloadInfo)

        /**
         * Update download speed
         */
        fun onDownload(info: DownloadInfo)

        /**
         * How far the archive write has got, in entries. Sent after every page has
         * been downloaded, because the download is not finished until the archive
         * holding it is: on a share that tail is long enough to look like a hang.
         */
        fun onArchiveProgress(done: Long, total: Long, speed: Long, format: Int) {}

        /**
         * Download done
         */
        fun onFinish(info: DownloadInfo)

        /**
         * Download done
         */
        fun onCancel(info: DownloadInfo)
    }

    private val mSpeedReminder = object {
        private val contentLengthMap = SparseLongArray()
        private val receivedSizeMap = SparseLongArray()
        private val tracker = SpeedTracker()
        private val mutex = Mutex()
        private var currentJob: Job? = null
        fun start() {
            if (currentJob == null) {
                currentJob = launch {
                    tracker.speedFlow().collect(::updateSpeed)
                }
            }
        }

        suspend fun stop() {
            tracker.reset()
            mutex.withLock {
                contentLengthMap.clear()
                receivedSizeMap.clear()
            }
            currentJob?.cancel()
            currentJob = null
        }

        suspend fun onDownload(index: Int, contentLength: Long, receivedSize: Long, bytesRead: Int) {
            mutex.withLock {
                contentLengthMap.put(index, contentLength)
                receivedSizeMap.put(index, receivedSize)
            }
            tracker.track(bytesRead)
        }

        suspend fun onDone(index: Int) {
            mutex.withLock {
                contentLengthMap.delete(index)
                receivedSizeMap.delete(index)
            }
        }

        suspend fun onFinish() {
            mutex.withLock {
                contentLengthMap.clear()
                receivedSizeMap.clear()
            }
        }

        private suspend fun updateSpeed(speed: Long) {
            mCurrentTask?.let { info ->
                info.speed = speed

                // Calculate remaining
                if (info.total <= 0) {
                    info.remaining = -1
                } else if (speed == 0L) {
                    info.remaining = 300L * 24L * 60L * 60L * 1000L // 300 days
                } else {
                    var downloadingCount = 0
                    var downloadingContentLengthSum = 0L
                    var totalSize = 0L
                    mutex.withLock {
                        for (i in 0..<contentLengthMap.size) {
                            val contentLength = contentLengthMap.valueAt(i)
                            val receivedSize = receivedSizeMap.valueAt(i)
                            downloadingCount++
                            downloadingContentLengthSum += contentLength
                            totalSize += contentLength - receivedSize
                        }
                    }
                    if (downloadingCount != 0) {
                        totalSize += downloadingContentLengthSum * (info.total - info.downloaded - downloadingCount) / downloadingCount
                        info.remaining = totalSize / speed * 1000
                    }
                }
                mDownloadListener?.onDownload(info)
                mutableNotifyFlow.emit(info)
            }
        }
    }

    private const val TAG = "DownloadManager"
}

/** Where downloads go when even the external directory is unavailable. */
private const val DOWNLOAD_FALLBACK = "download"

var downloadLocation: Path
    get() = with(Settings) {
        if (downloadScheme != null) {
            Uri.Builder().apply {
                scheme(downloadScheme)
                encodedAuthority(downloadAuthority)
                encodedPath(downloadPath)
                encodedQuery(downloadQuery)
                encodedFragment(downloadFragment)
            }.build().toOkioPath()
        } else {
            // Never the empty path: it resolves to `.`, and a directory created
            // relative to the process working directory is not writable.
            AppConfig.defaultDownloadDir?.toOkioPath() ?: (appCtx.filesDir.toOkioPath() / DOWNLOAD_FALLBACK)
        }
    }
    set(value) = with(value.toUri()) {
        Settings.edit {
            downloadScheme = scheme
            downloadAuthority = encodedAuthority
            downloadPath = encodedPath
            downloadQuery = encodedQuery
            downloadFragment = encodedFragment
        }
    }

private val locationPathCache = ConcurrentHashMap<Set<String>, List<Path>>()
private val downloadDirCache = ConcurrentHashMap<String, Path>()

/**
 * Must be called whenever the configured locations change or a download
 * directory is created/removed, as both invalidate the cached resolutions.
 */
fun invalidateDownloadLocationCache() {
    locationPathCache.clear()
    downloadDirCache.clear()
}

/**
 * The directory new downloads are written to. Equals the user-selected default
 * location, or the first configured location, or the legacy single location.
 * Never throws: a corrupted stored URI falls back to the legacy location.
 */
val defaultDownloadLocation: Path
    get() = with(Settings) {
        val set = downloadLocations.value
        val chosen = defaultDownloadLocationUri.value
        when {
            chosen != null && chosen in set -> runCatching { Uri.parse(chosen).toOkioPath() }
                .getOrNull()
            set.isNotEmpty() -> set.asSequence().sorted().mapNotNull { uriStr ->
                runCatching { Uri.parse(uriStr).toOkioPath() }.getOrNull()
            }.firstOrNull()
            else -> null
        }
    } ?: downloadLocation

/**
 * All configured download directories, in a deterministic (sorted) order.
 * Falls back to the legacy single [downloadLocation] when no multi-location
 * set has been configured yet. Results are cached per configured URI set.
 */
val allDownloadLocations: List<Path>
    get() {
        val set = Settings.downloadLocations.value
        locationPathCache[set]?.let { return it }
        val paths = if (set.isEmpty()) {
            downloadLocation.takeIf { it.toString().isNotEmpty() }?.let { listOf(it) } ?: emptyList()
        } else {
            set.mapNotNull { uriStr -> runCatching { Uri.parse(uriStr).toOkioPath() }.getOrNull() }
                .sortedBy { it.toString() }
        }
        locationPathCache[set] = paths
        return paths
    }

/**
 * Every name a download directory's thumbnail has been written under.
 *
 * `thumb.webp` is what this app writes now, `thumb.jpg` is the name it inherited,
 * and `.thumb` is what versions before the thumbnail-cache split wrote -- the
 * constant for it is gone, but the files it left behind are not.
 */
val THUMB_FILENAMES = listOf("thumb.webp", "thumb.jpg", ".thumb")

/** Locations that can receive new downloads, including writable SMB shares. */
val writableDownloadLocations: List<Path>
    get() = allDownloadLocations

/**
 * Resolved per-download and cached: the naive version performed a filesystem stat
 * per list item per recomposition, on the main thread.
 *
 * Only the location the download was recorded in is asked, and on its own. Probing
 * every configured location instead costs a stat per location per download -- for a
 * share that is a round trip of unknown latency -- and asking a local provider
 * whether it holds a directory that in fact lives on a share makes it answer with a
 * missing-file error, which it writes to the parcel and logs as
 * `FileSystemProvider.isChildDocument` / `Writing exception to parcel`, once per
 * download. A download whose location was never recorded falls back to the default
 * location: the restore scan is what records an old download's location, and until
 * it has run there is nothing to tell the candidates apart by.
 */
val DownloadInfo.downloadDir: Path?
    get() = dirname?.let { name ->
        val key = "${location.orEmpty()}/$name"
        downloadDirCache[key] ?: run {
            val resolved = recordedLocationPath(location)?.let { it / name }
                ?: (defaultDownloadLocation / name)
            downloadDirCache[key] = resolved
            resolved
        }
    }

/**
 * The recorded location, if it still holds the directory.
 *
 * No search: this is the path the download list labels each row with, where a wrong
 * answer is cosmetic, and it is what the non-suspending callers can afford.
 * [resolveDownloadDirSuspending] is the one that looks elsewhere.
 */
fun resolveDownloadDir(location: String?, dirname: String): Path = recordedLocationPath(location)?.let { it / dirname }?.takeIf { it.isDirectory }
    ?: (defaultDownloadLocation / dirname)

/**
 * The directory a gallery's files are in, for the code that has to *find* them.
 *
 * The recorded location is asked first: one stat, and unless the directory has been
 * moved it is the answer. Only when it misses are the rest looked at, and they are
 * looked at at once rather than in turn. They are not equally expensive -- a local
 * directory answers in milliseconds and a share that went to sleep answers in ten
 * seconds -- and adding those up is what made a gallery moved onto a share read as
 * not downloaded at all: the local locations were asked, missed, and the search had
 * already given up on the one place the files actually were.
 *
 * The first hit wins, and the losers are abandoned rather than awaited. Cancelling
 * them does not interrupt the provider call they are blocked in -- that call runs to
 * its own timeout on an IO thread -- but nobody waits for it, which is the point.
 *
 * A candidate is only accepted if it is really this gallery's: two locations can
 * hold a directory of the same name, and racing makes the winner the fastest one
 * rather than the preferred one. A directory with no marker is accepted anyway,
 * because a bare archive the user put there deliberately has none.
 *
 * Null means nobody has it. Returning a guessed path instead would have the caller
 * re-check it with `isDirectory` -- a second round trip to the same share, and a
 * second chance to fail, which is how a directory that had just been found turned
 * into "read this one online".
 */
suspend fun resolveDownloadDirSuspending(location: String?, dirname: String): Path? {
    val started = SystemClock.elapsedRealtime()
    recordedLocationPath(location)?.let { it / dirname }?.takeIf { it.isDirectory }?.let { dir ->
        logcat("SpiderDen") { "resolve $dirname: recorded $dir in ${SystemClock.elapsedRealtime() - started}ms" }
        return dir
    }
    val candidates = writableDownloadLocations.filter { it.toString() != location }
    if (candidates.isEmpty()) return null
    val gid = dirname.substringBefore('-').toLongOrNull()
    // The racers live outside this coroutine on purpose: a loser is blocked in a
    // provider call that cancellation cannot interrupt, and waiting for it is the
    // ten seconds this function exists to avoid.
    val racers = CoroutineScope(currentCoroutineContext() + SupervisorJob())
    try {
        val winner = CompletableDeferred<Path?>()
        val jobs = candidates.map { path ->
            racers.launch(Dispatchers.IO) {
                runCatching { (path / dirname).takeIf { it.isDirectory && it.holdsGallery(gid) } }
                    .onFailure { logcat("SpiderDen") { "resolve $dirname: $path threw ${it.message}" } }
                    .getOrNull()?.let { winner.complete(it) }
            }
        }
        // Nobody found it: the answer arrives when the slowest location has given up.
        racers.launch {
            jobs.joinAll()
            winner.complete(null)
        }
        val found = winner.await()
        logcat("SpiderDen") {
            "resolve $dirname: ${candidates.size} locations searched, got $found in ${SystemClock.elapsedRealtime() - started}ms"
        }
        return found
    } finally {
        racers.cancel()
    }
}

/**
 * Whether a candidate directory is really this gallery's.
 *
 * The marker names the gallery, and one naming a different gallery means this is
 * another download that happens to share a title. No marker, or one that cannot be
 * read, is accepted: neither disproves anything, and a bare archive the user put
 * there deliberately has no marker at all.
 */
private suspend fun Path.holdsGallery(gid: Long?): Boolean {
    if (gid == null) return true
    val marker = find(SPIDER_INFO_FILENAME) ?: return true
    val info = runCatching { readCompatFromPath(marker) }.getOrNull() ?: return true
    return info.gid == gid
}

/**
 * The configured location a recorded location string names, or null if it is not
 * one any more -- a location removed from the settings still has its downloads,
 * and their directories are then found the slow way.
 */
private fun recordedLocationPath(location: String?): Path? = location?.let { saved -> allDownloadLocations.firstOrNull { it.toString() == saved } }

/**
 * The archive names this app looks for, in the order it prefers them.
 *
 * `.dwarfs` and `.cbz` are the ones this app writes, `.zip` is what the archiver
 * hands over, and `.rar`/`.7z` come last because they are the ones this app never
 * writes -- if one of those is there, the user put it there deliberately.
 */
internal val ARCHIVE_SUFFIXES = listOf("dwarfs", "cbz", "zip", "rar", "7z")

/**
 * The gallery's single-file archive, if it has one.
 *
 * An image comes first: it is the one a user built themselves, and if both are
 * somehow present the one they made deliberately is the one to read.
 */
val DownloadInfo.archiveFile get() = downloadDir?.run {
    ARCHIVE_SUFFIXES.firstNotNullOfOrNull { find("$gid.$it") }
}

/**
 * The archive this gallery was downloaded as, wherever it is now.
 *
 * [archiveFile] answers from the recorded location, which is what a list needs --
 * it draws a row per gallery and cannot search for each one. Opening a gallery is
 * the opposite: it happens once, it may wait, and getting the answer wrong is not
 * cosmetic. A directory that was moved is recorded in its old location until
 * something looks for it, and the reader took "no archive there" to mean "this was
 * never downloaded": it read the gallery online, then wrote the pages it fetched
 * into a directory it had no permission to write to.
 *
 * Finding the directory is also what heals the record, so the rest of the session
 * -- and the download list -- stop asking the old location.
 */
suspend fun resolveArchiveFile(info: GalleryInfo): Path? {
    val download = DownloadManager.getDownloadInfo(info.gid) ?: return null
    // A share is asked once, in one frame; a local directory costs nothing to ask
    // five times, so it keeps the plain lookup.
    download.downloadDir?.takeIf { !it.isSmb }?.findArchive(info.gid)?.let { return it }
    val dirname = info.downloadDirname()
    val dir = resolveDownloadDirSuspending(download.location, dirname) ?: return null
    dir.parent?.let { DownloadManager.recordDownloadDir(info.gid, dirname, it.toString()) }
    return dir.findArchive(info.gid)
}

/** The two files a download directory can identify itself with. */
internal val MARKER_FILENAMES = listOf(COMIC_INFO_FILE, SPIDER_INFO_FILENAME)

/**
 * Which of [names] each of [dirs] holds, asked once per share.
 *
 * A batch of names is one compound request, so fifty names cost the same two
 * round trips as one, while a stat per name is a round trip each -- and both
 * callers run over a whole library. A directory that is not on a share is left
 * out: a stat on a local path is free.
 *
 * A name the share never answered for is absent from that directory's map, which
 * a caller has to read as "not known" and not as "not there".
 */
internal suspend fun probeNames(dirs: List<Path>, names: List<String>): Map<String, Map<String, Boolean>> {
    val presence = HashMap<String, Map<String, Boolean>>(dirs.size * 2)
    dirs.filter { it.isSmb }.groupBy { it.parent?.toString() }.forEach { (parent, group) ->
        val location = parent?.let { SmbLocation.parse(it) } ?: return@forEach
        val requested = group.flatMap { dir -> names.map { "${dir.name}/$it" } }
        val answers = runSuspendCatching { SmbRepository.existingNames(location, requested) }
            .getOrDefault(emptyMap())
        group.forEach { dir ->
            val prefix = "${dir.name}/"
            presence[dir.toString()] = answers.asSequence()
                .filter { it.key.startsWith(prefix) }
                .associate { it.key.removePrefix(prefix) to it.value }
        }
    }
    return presence
}

/**
 * The gallery's archive in this directory, if it has one.
 *
 * On a share the five suffixes travel together in one request, because each of them
 * is a round trip and the answer is almost always "none of them": a probe that costs
 * five times the latency for one question is the shape this avoids.
 */
internal suspend fun Path.findArchive(gid: Long): Path? {
    if (!isSmb) return ARCHIVE_SUFFIXES.firstNotNullOfOrNull { find("$gid.$it") }
    val names = ARCHIVE_SUFFIXES.map { "$gid.$it" }
    val present = SmbLocation.parse(this)?.let { location ->
        runCatching { SmbRepository.statMany(location, names) }.getOrNull()
    } ?: return ARCHIVE_SUFFIXES.firstNotNullOfOrNull { find("$gid.$it") }
    return present.indexOfFirst { it }.takeIf { it >= 0 }?.let { resolve(names[it]) }
}

/**
 * The scale an archive transfer's progress is reported on.
 *
 * `DownloadInfo` counts pages, and its counters are `Int`: a byte total overflows
 * on the multi-gigabyte archives this app produces. A percentage fits, and the
 * progress bar only ever wanted a fraction.
 */
private const val ARCHIVE_PROGRESS_STEPS = 100

/** How often the rate beside a write is resampled; a second is as often as it can be read. */
private const val WRITE_SPEED_SAMPLE_MILLIS = 1000L
val GalleryInfo.tempDownloadDir get() = AppConfig.externalTempPersistDir?.let { it / "$gid" }
