package com.hippo.ehviewer.download

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.text.format.Formatter
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.savedstate.serialization.decodeFromSavedState
import androidx.savedstate.serialization.encodeToSavedState
import com.ehviewer.core.files.SystemFileSystem
import com.ehviewer.core.files.delete
import com.ehviewer.core.files.moveTo
import com.ehviewer.core.files.read
import com.ehviewer.core.files.write
import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.util.isAtLeastS
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.EhApplication.Companion.ktorClient
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.applyEhConfig
import com.hippo.ehviewer.client.parser.Archive
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.ehviewer.spider.downloadDirname
import com.hippo.ehviewer.util.ensureSuccess
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.prepareRequest
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.rethrowCloseCauseIfNeeded
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.Sink
import kotlinx.io.readString
import kotlinx.io.writeString
import okio.IOException
import okio.Path

/**
 * Fetches a gallery's archive into its download directory, in the background,
 * with a notification that shows how far along it is and can stop it.
 *
 * A service rather than a coroutine on the screen, because an archive is tens of
 * megabytes and leaving the gallery detail page must not abandon the transfer.
 *
 * There is no resume: the archive host answers 404 to a range request and 416 to
 * one it reads as out of bounds, so every attempt starts over. What a stopped
 * attempt leaves is the row and the directory marker -- not a part file worth
 * keeping, and not a transfer worth continuing.
 *
 * One request at a time. Two transfers would share one notification and one
 * `title`, and the first to finish would `stopSelf` the service out from under
 * the other; they are queued instead, which also keeps them out of the page
 * downloader's way.
 */
class ArchiveDownloadService : Service(), CoroutineScope {
    private val tag = "ArchiveDownloadService"

    override val coroutineContext = Dispatchers.IO + SupervisorJob()

    private val notifyManager by lazy { NotificationManagerCompat.from(this) }
    private val channelId by lazy { "$packageName.archive" }

    /** Queued requests, drained one at a time; touched from the main thread and the worker. */
    private val queue = ArrayDeque<Request>()
    private var worker: Job? = null

    /** Serializes "create the row, then queue it", so the queue keeps arrival order. */
    private val enqueueMutex = Mutex()
    private var title: String = ""
    private var lastNotifiedAt = 0L

    private class Request(
        val galleryDetail: GalleryDetail,
        val gid: Long,
        val res: String,
        val isHath: Boolean,
        val title: String,
    )

    override fun onCreate() {
        notifyManager.createNotificationChannel(
            NotificationChannelCompat.Builder(channelId, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.download_service))
                .build(),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val bundle = intent.getBundleExtra(KEY_GALLERY) ?: return stop()
                val res = intent.getStringExtra(KEY_RES) ?: return stop()
                val request = Request(
                    galleryDetail = decodeFromSavedState(bundle),
                    gid = bundle.getLong(KEY_GID),
                    res = res,
                    isHath = intent.getBooleanExtra(KEY_HATH, false),
                    title = intent.getStringExtra(KEY_TITLE).orEmpty(),
                )
                // The notification has to exist before the work starts: a service
                // that takes minutes to reach startForeground is killed.
                startForegroundSafely(request.gid, getString(R.string.archive_download_preparing), 0, 0)
                // The same gallery asked for twice is one download. Without this
                // the queue holds it twice, and the second transfer renames onto
                // a name that already exists -- which a document provider answers
                // by inventing "$gid (1).zip" rather than replacing it. The claim
                // is synchronous on purpose: two taps can arrive before either
                // has had a chance to run anything.
                if (!DownloadManager.claimArchiveDownload(request.gid)) return START_NOT_STICKY
                launch { enqueue(request) }
            }
            ACTION_CANCEL -> {
                // Stopping means stopping: the queued requests go too, or the user
                // would see another download start right after cancelling one.
                val dropped = synchronized(queue) {
                    val copy = queue.toList()
                    queue.clear()
                    copy
                }
                dropped.forEach {
                    DownloadManager.abandonArchiveDownload(it.gid)
                    DownloadManager.releaseArchiveDownload(it.gid)
                }
                worker?.cancel()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent) = null

    private fun stop(): Int {
        stopSelf()
        return START_NOT_STICKY
    }

    /**
     * Creates the row, then queues the request.
     *
     * Both under one lock so the queue keeps the order the requests arrived in,
     * and so a queued gallery is in the download list from the moment it is
     * queued rather than from the moment the one ahead of it finishes.
     */
    private suspend fun enqueue(request: Request) {
        try {
            enqueueMutex.withLock {
                showQueued(request)
                synchronized(queue) {
                    queue.addLast(request)
                    if (worker == null) worker = launch { drain() }
                }
            }
        } catch (e: Throwable) {
            // Nothing will run this request now, so nothing else will release the
            // claim it holds on the gallery.
            logcat(e)
            DownloadManager.abandonArchiveDownload(request.gid)
            DownloadManager.releaseArchiveDownload(request.gid)
        }
    }

    /**
     * Puts the gallery in the download list while its request waits its turn.
     *
     * The directory and its marker are written here for the same reason as before
     * the transfer: an attempt that dies leaves something the restore scan can
     * identify.
     */
    private suspend fun showQueued(request: Request) {
        val detail = request.galleryDetail
        SpiderDen(detail).apply {
            initDownloadDir()
            runCatching { writeSpiderInfo(detail.pages) }.onFailure { logcat(it) }
        }
        DownloadManager.restoreDownload(detail.galleryInfo, detail.downloadDirname(), EhDB.getDownloadLocation(request.gid))
        DownloadManager.enqueueArchiveDownload(request.gid)
    }

    private suspend fun drain() {
        try {
            while (true) {
                val request = synchronized(queue) { queue.removeFirstOrNull() } ?: break
                title = request.title
                run(request)
            }
        } finally {
            // Whether the queue emptied or the worker was cancelled, the service
            // only stops once nothing is left to do -- and a request that arrived
            // while this was finishing still gets a worker.
            synchronized(queue) {
                worker = null
                if (queue.isEmpty()) stopSelf() else worker = launch { drain() }
            }
        }
    }

    private suspend fun run(request: Request) {
        val gid = request.gid
        var part: Path? = null
        try {
            DownloadManager.beginArchiveDownload(gid)
            val downloaded = prepare(request)
            part = downloaded
            downloaded moveTo downloaded.parent!!.resolve("$gid.zip")
            part = null
            // The archive is the whole gallery, so the download is finished.
            DownloadManager.markArchiveDownloaded(gid, request.galleryDetail.pages)
            SpiderDen(request.galleryDetail).apply {
                initDownloadDir()
                // The archive is the whole gallery, but the archive alone does not
                // identify the directory: the restore scan looks for a marker.
                runCatching { writeSpiderInfo(request.galleryDetail.pages) }.onFailure { logcat(it) }
                if (Settings.archiveMetadata.value) writeComicInfo()
                // Whatever loose images an earlier attempt left are now redundant:
                // the reader prefers the archive and would never open them again.
                removeLooseImages()
            }
            finish(gid)
        } catch (e: CancellationException) {
            // The user stopped it. A part file is not worth keeping: nothing can
            // continue it, and it is only the size of a transfer that will be
            // redone from zero.
            part?.let { runCatching { it.delete() } }
            DownloadManager.abandonArchiveDownload(gid)
            throw e
        } catch (e: Throwable) {
            logcat(e)
            // Kept on failure: its size is the only record of how far it got.
            DownloadManager.abandonArchiveDownload(gid)
            finish(gid, e)
        } finally {
            DownloadManager.finishArchiveDownload(gid)
        }
    }

    /** Downloads into the part file and returns it. */
    private suspend fun prepare(request: Request): Path {
        val gid = request.gid
        val spiderDen = SpiderDen(request.galleryDetail).apply { initDownloadDir() }
        val dir = spiderDen.downloadDir ?: throw IOException("No download directory for $gid")
        val part = dir.resolve("$gid.zip.part")
        // No resume. The H@H archive handler answers 404 to a range request and
        // 416 to one it reads as out of bounds, so a part file left by an earlier
        // attempt is only in the way: start over rather than pick it up.
        part.delete()
        val url = EhEngine.downloadArchive(gid, request.galleryDetail.token, request.res, request.isHath)
            ?: throw IOException("No archive link for $gid")
        ktorClient.prepareRequest(url) {
            applyEhConfig(EhUrl.getGalleryDetailUrl(gid, request.galleryDetail.token), EhUrl.origin)
            timeout {
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                socketTimeoutMillis = 60_000
            }
        }.execute { response ->
            Log.d(tag, "archive $gid: ${response.status}, length=${response.contentLength()}")
            response.status.ensureSuccess()
            val total = response.contentLength() ?: 0L
            notify(gid, progressText(0, total), 0, total)
            var lastPercent = -1
            response.bodyAsChannel().copyTo(part) { written ->
                notify(gid, progressText(written, total), written, total)
                if (total > 0) {
                    val percent = (written * 100 / total).toInt()
                    if (percent != lastPercent) {
                        lastPercent = percent
                        DownloadManager.updateArchiveProgress(gid, percent)
                    }
                }
            }
            // A 200 is not enough: while the archive is still being prepared the
            // archiver answers with a page saying so, and that page must not become
            // the gallery's archive.
            val head = SystemFileSystem.read(part) { readByteArray(2) }
            if (head.size != 2 || head[0] != 'P'.code.toByte() || head[1] != 'K'.code.toByte()) {
                part.delete()
                throw IllegalStateException("The archiver returned a page instead of an archive")
            }
        }
        return part
    }

    private fun startForegroundSafely(gid: Long, text: String, done: Long, total: Long) {
        val notification = buildNotification(gid, text, done, total)
        runCatching { startForeground(NOTIFICATION_ID, notification) }.onFailure {
            // Android 12 refuses a foreground start from the background; the
            // download still runs, it just has no notification to show.
            if (isAtLeastS && it is ForegroundServiceStartNotAllowedException) logcat(it) else throw it
        }
    }

    private fun buildNotification(gid: Long, text: String, done: Long, total: Long): android.app.Notification {
        val cancelIntent = Intent(this, ArchiveDownloadService::class.java).setAction(ACTION_CANCEL)
        val cancel = PendingIntent.getService(this, 0, cancelIntent, PendingIntent.FLAG_IMMUTABLE)
        val indeterminate = total <= 0
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title.ifEmpty { getString(R.string.archive_download_title, gid) })
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(if (indeterminate) 0 else 100, if (indeterminate) 0 else (done * 100 / total).toInt(), indeterminate)
            .addAction(com.hippo.ehviewer.R.drawable.v_pause_x24, getString(R.string.archive_download_cancel), cancel)
            .build()
    }

    /**
     * Repaint the progress notification, at most a few times a second.
     *
     * The copy reports every buffer it moves, which is a few kilobytes: building
     * a notification per buffer is thousands of binder calls for one archive.
     */
    private fun notify(gid: Long, text: String, done: Long, total: Long) {
        val now = SystemClock.elapsedRealtime()
        val finished = total > 0 && done >= total
        if (!finished && now - lastNotifiedAt < NOTIFY_INTERVAL_MILLIS) return
        lastNotifiedAt = now
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        runCatching { notifyManager.notify(NOTIFICATION_ID, buildNotification(gid, text, done, total)) }
    }

    private fun progressText(done: Long, total: Long): String = if (total <= 0) {
        Formatter.formatFileSize(this, done)
    } else {
        "${Formatter.formatFileSize(this, done)} / ${Formatter.formatFileSize(this, total)}"
    }

    private fun finish(gid: Long, error: Throwable? = null) {
        val text = if (error == null) {
            getString(R.string.archive_download_done)
        } else {
            error.message ?: getString(R.string.download_archive_failure)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(if (error == null) android.R.drawable.stat_sys_download_done else com.hippo.ehviewer.R.drawable.ic_baseline_warning_24)
            .setContentTitle(title.ifEmpty { getString(R.string.archive_download_title, gid) })
            .setContentText(text)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            runCatching { notifyManager.notify(NOTIFICATION_ID, notification) }
        }
    }

    override fun onDestroy() {
        cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START = "archive_start"
        private const val ACTION_CANCEL = "archive_cancel"
        private const val KEY_GALLERY = "gallery"
        private const val KEY_GID = "gid"
        private const val KEY_RES = "res"
        private const val KEY_HATH = "hath"
        private const val KEY_TITLE = "title"
        private const val NOTIFICATION_ID = 10
        private const val NOTIFY_INTERVAL_MILLIS = 250L

        fun start(context: Context, galleryDetail: GalleryDetail, archive: Archive) {
            val bundle = Bundle().apply {
                putLong(KEY_GID, galleryDetail.gid)
                putAll(encodeToSavedState(galleryDetail))
            }
            val intent = Intent(context, ArchiveDownloadService::class.java)
                .setAction(ACTION_START)
                .putExtra(KEY_GALLERY, bundle)
                .putExtra(KEY_RES, archive.res)
                .putExtra(KEY_HATH, archive.isHath)
                .putExtra(KEY_TITLE, EhUtils.getSuitableTitle(galleryDetail))
            ContextCompat.startForegroundService(context, intent)
        }
    }
}

/**
 * Copy the body into [file], reporting how many bytes have arrived.
 */
@OptIn(InternalAPI::class)
private suspend fun ByteReadChannel.copyTo(file: Path, onProgress: (Long) -> Unit) {
    file.write {
        var written = 0L
        while (!isClosedForRead) {
            written += transferFrom(readBuffer)
            onProgress(written)
            awaitContent()
        }
        rethrowCloseCauseIfNeeded()
    }
}
