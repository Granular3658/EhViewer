package com.hippo.ehviewer.download

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.text.format.Formatter
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ehviewer.core.files.delete
import com.ehviewer.core.files.metadataOrNull
import com.ehviewer.core.i18n.R
import com.ehviewer.core.util.isAtLeastS
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.archive.archiveSuffixOf
import com.hippo.ehviewer.archive.convertGalleryArchive
import com.hippo.ehviewer.archive.packGalleryImages
import com.hippo.ehviewer.archive.writeFormatLabel
import com.hippo.ehviewer.spider.downloadDirname
import com.hippo.ehviewer.util.FileUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Rewrites downloaded galleries from one archive format to the other, in the
 * background, with a notification that shows how far along it is.
 *
 * A service for the same reason the archive downloader is one: a gallery is tens
 * of megabytes of copying, and leaving the list must not abandon it. One gallery
 * at a time -- the native reader holds one archive, so two conversions at once
 * would read each other's pages -- and the queue is what makes a multi-selection
 * one action instead of several.
 *
 * The old archive is removed once the new one is complete, which is what makes
 * this a conversion and not a copy: a gallery with both formats in its directory
 * is one the reader has to choose between, and it always chooses the same one.
 */
class GalleryConvertService : Service(), CoroutineScope {
    private val tag = "GalleryConvertService"

    override val coroutineContext = Dispatchers.IO + SupervisorJob()

    private val notifyManager by lazy { NotificationManagerCompat.from(this) }
    private val channelId by lazy { "$packageName.gallery_convert" }

    /** Queued galleries, drained one at a time; touched from the main thread and the worker. */
    private val queue = ArrayDeque<Request>()
    private var worker: Job? = null

    /** What the worker is on right now, or null; read from the main thread. */
    @Volatile
    private var current: Request? = null

    /** Whether this instance has been put in the foreground; only the first batch needs it. */
    @Volatile
    private var inForeground = false
    private var lastNotifiedAt = 0L

    // The speed is sampled on a wall clock rather than per buffer: the buffers are
    // as irregular as the disk, and a rate read off one of them says nothing.
    private var lastSampleAt = 0L
    private var lastSampleBytes = 0L
    private var speed = 0L

    /** How the batch as a whole went, for the notification it ends with. */
    private var converted = 0
    private var failed = 0
    private var skipped = 0

    private class Request(
        val gid: Long,
        val title: String,
        val format: Int,
        /** Where this gallery sits in the batch it was selected in, counting from one. */
        val position: Int,
        val count: Int,
    )

    override fun onCreate() {
        notifyManager.createNotificationChannel(
            NotificationChannelCompat.Builder(channelId, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.gallery_convert_channel))
                .build(),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val gids = intent.getLongArrayExtra(KEY_GIDS) ?: return stop()
                val titles = intent.getStringArrayExtra(KEY_TITLES).orEmpty()
                val format = intent.getIntExtra(KEY_FORMAT, Settings.ARCHIVE_DWARFS)
                // The notification has to exist before the work starts: a service
                // that takes minutes to reach startForeground is killed.
                if (!inForeground) startForegroundSafely(getString(R.string.gallery_convert_preparing), 0, 0)
                val requests = gids.mapIndexed { index, gid ->
                    Request(gid, titles.getOrElse(index) { gid.toString() }, format, index + 1, gids.size)
                }
                DownloadManager.markConvertQueued(gids.toList(), format)
                synchronized(queue) {
                    queue.addAll(requests)
                    if (worker == null) worker = launch { drain() }
                }
            }
            ACTION_CANCEL -> {
                // Stopping means stopping: what is queued goes too, or the next
                // gallery would start right after the one that was cancelled.
                synchronized(queue) { queue.clear() }
                DownloadManager.clearAllConvertProgress()
                worker?.cancel()
            }
            ACTION_CANCEL_ONE -> {
                // One row's stop button. A gallery that has not been reached yet
                // leaves the queue; the one being written stops where it is, and
                // the queue behind it carries on -- the worker's own unwinding
                // starts the next one.
                val gid = intent.getLongExtra(KEY_GID, -1L)
                synchronized(queue) {
                    queue.removeAll { it.gid == gid }
                }
                DownloadManager.clearConvertProgress(gid)
                if (current?.gid == gid) worker?.cancel()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent) = null

    private fun stop(): Int {
        stopSelf()
        return START_NOT_STICKY
    }

    private suspend fun drain() {
        var more = false
        try {
            while (true) {
                val request = synchronized(queue) { queue.removeFirstOrNull() } ?: break
                current = request
                run(request)
            }
        } finally {
            // Whether the queue emptied or the worker was cancelled, the service
            // only stops once nothing is left to do -- and a request that arrived
            // while this was finishing still gets a worker.
            synchronized(queue) {
                worker = null
                more = queue.isNotEmpty()
                if (more) worker = launch { drain() }
            }
            if (!more) {
                current = null
                finishBatch()
                stopSelf()
            }
        }
    }

    private suspend fun run(request: Request) {
        try {
            val download = DownloadManager.getDownloadInfo(request.gid)
            val info = download?.galleryInfo
            val archive = info?.let { resolveArchiveFile(it) }
            if (archive == null) {
                // No archive: the gallery was downloaded page by page, so the pages
                // are the thing to pack -- which is the other direction of the same
                // conversion. Asking for loose pages from a gallery that already is
                // loose is nothing to do.
                val directory = info?.let { resolveDownloadDirSuspending(download.location, it.downloadDirname()) }
                if (directory == null || request.format == Settings.ARCHIVE_OFF) {
                    Log.d(tag, "convert ${request.gid}: nothing to pack")
                    skipped++
                    return
                }
                lastNotifiedAt = 0
                lastSampleAt = 0
                speed = 0
                notify(getString(R.string.gallery_convert_preparing), 0, 0)
                packGalleryImages(directory, request.gid, request.format) { done, total ->
                    val speed = sampleSpeed(done)
                    notify(progressText(done, total, speed), done, total)
                    DownloadManager.updateConvertProgress(request.gid, request.format, done, total, speed)
                }
                converted++
                Log.d(tag, "convert ${request.gid}: packed the pages in ${directory.name}")
                return
            }
            // Loose pages are not a file to write onto, so there is nothing to
            // compare a name with: a gallery with an archive always has something
            // to unpack.
            if (request.format != Settings.ARCHIVE_OFF) {
                val target = archive.parent!! / (archive.name.substringBeforeLast('.', archive.name) + archiveSuffixOf(request.format))
                if (target == archive) {
                    Log.d(tag, "convert ${request.gid}: already ${archive.name}")
                    skipped++
                    return
                }
            }
            val size = runCatching { archive.metadataOrNull()?.size }.getOrNull() ?: 0L
            lastNotifiedAt = 0
            lastSampleAt = 0
            speed = 0
            notify(getString(R.string.gallery_convert_preparing), 0, 0)
            convertGalleryArchive(archive, size, request.format) { done, total ->
                val speed = sampleSpeed(done)
                notify(progressText(done, total, speed), done, total)
                DownloadManager.updateConvertProgress(request.gid, request.format, done, total, speed)
            }
            // The new archive is complete and in place, so the old one is the
            // duplicate now.
            runCatching { archive.delete() }.onFailure { logcat(it) }
            converted++
            Log.d(tag, "convert ${request.gid}: ${archive.name} done")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(e)
            failed++
        } finally {
            if (current?.gid == request.gid) current = null
            // Whatever happened, the row goes back to being a downloaded gallery:
            // a failure is said by the notification, not left in the list.
            DownloadManager.clearConvertProgress(request.gid)
        }
    }

    /** A bytes-per-second figure, refreshed at most once a second. */
    private fun sampleSpeed(done: Long): Long {
        val now = SystemClock.elapsedRealtime()
        if (lastSampleAt == 0L) {
            lastSampleAt = now
            lastSampleBytes = done
            return 0
        }
        val elapsed = now - lastSampleAt
        if (elapsed < SPEED_SAMPLE_MILLIS) return speed
        speed = ((done - lastSampleBytes) * 1000 / elapsed).coerceAtLeast(0)
        lastSampleBytes = done
        lastSampleAt = now
        return speed
    }

    private fun startForegroundSafely(text: String, done: Long, total: Long) {
        val notification = buildNotification(text, done, total)
        runCatching { startForeground(NOTIFICATION_ID, notification) }.onSuccess {
            inForeground = true
        }.onFailure {
            // Android 12 refuses a foreground start from the background; the
            // conversion still runs, it just has no notification to show.
            if (isAtLeastS && it is ForegroundServiceStartNotAllowedException) logcat(it) else throw it
        }
    }

    private fun buildNotification(text: String, done: Long, total: Long): android.app.Notification {
        val cancelIntent = Intent(this, GalleryConvertService::class.java).setAction(ACTION_CANCEL)
        val cancel = PendingIntent.getService(this, 0, cancelIntent, PendingIntent.FLAG_IMMUTABLE)
        val indeterminate = total <= 0
        val request = current
        val title = when {
            request == null -> getString(R.string.gallery_convert_title)
            request.count <= 1 -> request.title
            else -> getString(R.string.gallery_convert_title_position, request.title, request.position, request.count)
        }
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            // The text is a size, a total and a rate, which is more than one line
            // on a narrow screen holds.
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSubText(request?.let { "[${writeFormatLabel(it.format)}]" })
            .setOngoing(true)
            .setShowWhen(false)
            // Repainting a progress notification is not news: without this every
            // update re-runs the alert, and the icon animates along with it.
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(if (indeterminate) 0 else 100, if (indeterminate) 0 else (done * 100 / total).toInt(), indeterminate)
            .addAction(com.hippo.ehviewer.R.drawable.v_pause_x24, getString(R.string.archive_download_cancel), cancel)
            .build()
    }

    /**
     * Repaints the progress notification, at most a few times a second.
     *
     * The copy reports every buffer it moves, which is a few kilobytes: building
     * a notification per buffer is thousands of binder calls for one gallery.
     */
    private fun notify(text: String, done: Long, total: Long) {
        val now = SystemClock.elapsedRealtime()
        val finished = total > 0 && done >= total
        if (!finished && now - lastNotifiedAt < NOTIFY_INTERVAL_MILLIS) return
        lastNotifiedAt = now
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        runCatching { startForeground(NOTIFICATION_ID, buildNotification(text, done, total)) }
    }

    private fun progressText(done: Long, total: Long, speed: Long): String {
        val size = if (total <= 0) {
            Formatter.formatFileSize(this, done)
        } else {
            "${Formatter.formatFileSize(this, done)} / ${Formatter.formatFileSize(this, total)}"
        }
        return if (speed > 0) "$size · ${FileUtils.humanReadableByteCount(speed)}/s" else size
    }

    /**
     * What the batch ended with, once there is nothing left in the queue.
     *
     * Said once rather than per gallery: the progress notification is shared, and
     * a "done" between two of them would replace the bar of the one that is
     * running with the outcome of the one before it.
     */
    private fun finishBatch() {
        if (converted == 0 && failed == 0 && skipped == 0) return
        val text = getString(R.string.gallery_convert_summary, converted, failed + skipped)
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(if (failed == 0) android.R.drawable.stat_sys_download_done else com.hippo.ehviewer.R.drawable.ic_baseline_warning_24)
            .setContentTitle(getString(R.string.gallery_convert_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            runCatching { notifyManager.notify(NOTIFICATION_ID, notification) }
        }
        converted = 0
        failed = 0
        skipped = 0
    }

    override fun onDestroy() {
        DownloadManager.clearAllConvertProgress()
        cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START = "gallery_convert_start"
        private const val ACTION_CANCEL = "gallery_convert_cancel"
        private const val ACTION_CANCEL_ONE = "gallery_convert_cancel_one"
        private const val KEY_GID = "gid"
        private const val KEY_GIDS = "gids"
        private const val KEY_TITLES = "titles"
        private const val KEY_FORMAT = "format"
        private const val NOTIFICATION_ID = 11
        private const val NOTIFY_INTERVAL_MILLIS = 500L
        private const val SPEED_SAMPLE_MILLIS = 1000L

        /**
         * Takes one gallery out of a conversion, from the stop button on its row.
         *
         * The service is running whenever this is called -- a row only offers the
         * button while its gallery has a state here -- so a plain start is enough.
         */
        fun cancel(context: Context, gid: Long) {
            val intent = Intent(context, GalleryConvertService::class.java)
                .setAction(ACTION_CANCEL_ONE)
                .putExtra(KEY_GID, gid)
            context.startService(intent)
        }

        /** Queues one batch of galleries for conversion, in the order they are given. */
        fun start(context: Context, gids: LongArray, titles: Array<String>, format: Int) {
            if (gids.isEmpty()) return
            val intent = Intent(context, GalleryConvertService::class.java)
                .setAction(ACTION_START)
                .putExtra(KEY_GIDS, gids)
                .putExtra(KEY_TITLES, titles)
                .putExtra(KEY_FORMAT, format)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
