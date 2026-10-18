package com.hippo.ehviewer.download

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.format.Formatter
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.savedstate.serialization.decodeFromSavedState
import androidx.savedstate.serialization.encodeToSavedState
import com.ehviewer.core.files.SystemFileSystem
import com.ehviewer.core.files.append
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
import com.hippo.ehviewer.client.applyEhConfig
import com.hippo.ehviewer.client.parser.Archive
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.ehviewer.spider.downloadDirname
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
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
import kotlinx.io.Sink
import kotlinx.io.readString
import kotlinx.io.writeString
import okio.Path

/**
 * Fetches a gallery's archive into its download directory, in the background,
 * with a notification that shows how far along it is and can stop it.
 *
 * A service rather than a coroutine on the screen, because an archive is tens of
 * megabytes and leaving the gallery detail page must not abandon the transfer.
 * The partial file is kept when the user stops it or the transfer fails, and the
 * next attempt asks the server for the rest of it, so stopping is cheap.
 */
class ArchiveDownloadService : Service(), CoroutineScope {
    override val coroutineContext = Dispatchers.IO + SupervisorJob()

    private val notifyManager by lazy { NotificationManagerCompat.from(this) }
    private val channelId by lazy { "$packageName.archive" }
    private var job: Job? = null

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
                val gid = bundle.getLong(KEY_GID)
                val res = intent.getStringExtra(KEY_RES) ?: return stop()
                val isHath = intent.getBooleanExtra(KEY_HATH, false)
                // The notification has to exist before the work starts: a service
                // that takes minutes to reach startForeground is killed.
                startForegroundSafely(gid, getString(R.string.archive_download_preparing), 0, 0)
                job = launch { run(decodeFromSavedState<GalleryDetail>(bundle), gid, res, isHath) }
            }
            ACTION_CANCEL -> {
                job?.cancel()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent) = null

    private fun stop(): Int {
        stopSelf()
        return START_NOT_STICKY
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
            .setContentTitle(getString(R.string.archive_download_title, gid))
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(if (indeterminate) 0 else 100, if (indeterminate) 0 else (done * 100 / total).toInt(), indeterminate)
            .addAction(com.hippo.ehviewer.R.drawable.v_pause_x24, getString(R.string.archive_download_cancel), cancel)
            .build()
    }

    private fun notify(gid: Long, text: String, done: Long, total: Long) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        runCatching { notifyManager.notify(NOTIFICATION_ID, buildNotification(gid, text, done, total)) }
    }

    private suspend fun run(galleryDetail: GalleryDetail, gid: Long, res: String, isHath: Boolean) {
        val part = try {
            prepare(galleryDetail, gid, res, isHath) ?: return finish(gid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(e)
            return finish(gid, e)
        }
        val target = part.parent!!.resolve("$gid.zip")
        part moveTo target
        forgetUrl(part.parent!!, gid)
        // The gallery is readable from this directory now; without the record the
        // file sits on disk while the app still goes to the network.
        DownloadManager.restoreDownload(galleryDetail.galleryInfo, galleryDetail.downloadDirname(), EhDB.getDownloadLocation(gid))
        if (Settings.archiveMetadata.value) {
            SpiderDen(galleryDetail).apply {
                initDownloadDir()
                writeComicInfo()
            }
        }
        finish(gid)
    }

    /** Downloads into the part file and returns it, or null when there is nothing to do. */
    private suspend fun prepare(galleryDetail: GalleryDetail, gid: Long, res: String, isHath: Boolean): Path? {
        val spiderDen = SpiderDen(galleryDetail).apply { initDownloadDir() }
        val dir = spiderDen.downloadDir ?: return null
        val part = dir.resolve("$gid.zip.part")
        // Whatever is already there is the start of the same file: ask for the
        // rest instead of downloading it again.
        val existing = SystemFileSystem.metadataOrNull(part)?.size ?: 0L
        // A link stays good for a week and re-downloading within that week costs
        // nothing, so a resume should reuse the link it already has instead of
        // asking for another one: each request counts against the weekly limit on
        // how often an archive may be fetched again.
        var url = if (existing > 0) cachedUrl(dir, gid) else null
        if (url == null) {
            url = EhEngine.downloadArchive(gid, galleryDetail.token, res, isHath) ?: return null
            saveUrl(dir, gid, url)
        }
        ktorClient.prepareRequest(url) {
            applyEhConfig(EhUrl.getGalleryDetailUrl(gid, galleryDetail.token), EhUrl.origin)
            timeout {
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                socketTimeoutMillis = 60_000
            }
            if (existing > 0) header("Range", "bytes=$existing-")
        }.execute { response ->
            val resuming = existing > 0 && response.status == HttpStatusCode.PartialContent
            if (existing > 0 && !resuming) part.delete()
            val length = response.contentLength() ?: 0L
            val total = if (resuming) existing + length else length
            response.status.ensureSuccessFor(resuming)
            val start = if (resuming) existing else 0L
            var done = start
            notify(gid, progressText(done, total), done, total)
            response.bodyAsChannel().copyTo(part, resuming) { written ->
                done = start + written
                notify(gid, progressText(done, total), done, total)
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

    private fun urlFile(dir: Path, gid: Long) = dir.resolve("$gid.zip.part.url")

    /**
     * The link this part file was being fetched from, if it is still worth using.
     *
     * Shorter than the week the server honours on purpose: a link used just past
     * its expiry wastes a whole download, and the cost of being early is one
     * request that would have been free anyway.
     */
    private fun cachedUrl(dir: Path, gid: Long): String? = runCatching {
        val lines = urlFile(dir, gid).read { readString() }.lineSequence().filter { it.isNotBlank() }.toList()
        val issued = lines[0].toLong()
        if (System.currentTimeMillis() / 1000 - issued > URL_TTL_SECONDS) null else lines[1]
    }.getOrNull()

    private fun saveUrl(dir: Path, gid: Long, url: String) = runCatching {
        urlFile(dir, gid).write { writeString("${System.currentTimeMillis() / 1000}\n$url\n") }
    }

    private fun forgetUrl(dir: Path, gid: Long) = runCatching { urlFile(dir, gid).delete() }

    private fun HttpStatusCode.ensureSuccessFor(resuming: Boolean) {
        if (resuming && this == HttpStatusCode.PartialContent) return
        if (!isSuccess()) throw IllegalStateException(toString())
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
            .setContentTitle(getString(R.string.archive_download_title, gid))
            .setContentText(text)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            runCatching { notifyManager.notify(NOTIFICATION_ID, notification) }
        }
        stopSelf()
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
        private const val NOTIFICATION_ID = 10
        private const val URL_TTL_SECONDS = 6 * 24 * 3600L

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
            ContextCompat.startForegroundService(context, intent)
        }
    }
}

/**
 * Copy the body into [file], reporting how many bytes have arrived.
 *
 * Resuming appends, so a transfer that was stopped picks up where the server
 * left off instead of rewriting what is already on disk.
 */
@OptIn(InternalAPI::class)
private suspend fun ByteReadChannel.copyTo(file: Path, append: Boolean, onProgress: (Long) -> Unit) {
    suspend fun Sink.drain() {
        var written = 0L
        while (!isClosedForRead) {
            written += transferFrom(readBuffer)
            onProgress(written)
            awaitContent()
        }
        rethrowCloseCauseIfNeeded()
    }
    if (append) file.append { drain() } else file.write { drain() }
}
