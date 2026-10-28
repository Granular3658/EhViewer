package com.hippo.ehviewer.updater

import android.text.Html
import com.ehviewer.core.files.write
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.BuildConfig
import com.hippo.ehviewer.EhApplication.Companion.ktorClient
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.executeAndParseAs
import com.hippo.ehviewer.client.executeSafely
import com.hippo.ehviewer.spider.timeoutBySpeed
import com.hippo.ehviewer.util.bodyAsUtf8Text
import com.hippo.ehviewer.util.copyTo
import com.hippo.ehviewer.util.ensureSuccess
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.util.zip.ZipInputStream
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.io.asSource
import moe.tarsin.coroutines.runSuspendCatching
import okio.Path
import tachiyomi.data.release.Release

// The release endpoint below is GitHub's; the commit feed is read from the web
// host instead. The host follows the remote so a build from a fork asks its own
// repository, and only a GitHub remote is asked in GitHub's shape.
val API_URL = if (BuildConfig.REPO_HOST == "github.com") {
    "https://api.github.com/repos/${BuildConfig.REPO_NAME}"
} else {
    "https://${BuildConfig.REPO_HOST}/api/v1/repos/${BuildConfig.REPO_NAME}"
}
val LATEST_RELEASE_URL = "$API_URL/releases/latest"

object AppUpdater {
    suspend fun checkForUpdate(forceCheck: Boolean = false): EhRelease? {
        val now = Clock.System.now()
        val last = Instant.fromEpochSeconds(Settings.lastUpdateTime)
        val interval = Settings.updateIntervalDays.value
        if (forceCheck || interval != 0 && now > last + interval.days) {
            Settings.lastUpdateTime = now.epochSeconds
            if (Settings.useCIUpdateChannel.value) {
                val curSha = BuildConfig.COMMIT_SHA
                // The branch this build came from, not the repository's default: a
                // build from a feature branch compared against main always looks out
                // of date, and names a commit that is older than the one running.
                // A detached checkout -- what CI builds -- has no branch to ask
                // about, so the default is the only thing left.
                val branch = BuildConfig.GIT_BRANCH.takeIf { it.isNotEmpty() && it != "HEAD" } ?: "main"
                // The commit feed rather than the API. An unauthenticated API call
                // comes out of sixty an hour shared by everyone behind the same
                // address, and running out is answered with the same bare 403 as any
                // other refusal -- a proxy with other users on it fails this check
                // for reasons that have nothing to do with the repository. The feed
                // is public, uncounted, and carries the same commits.
                val feedUrl = "https://${BuildConfig.REPO_HOST}/${BuildConfig.REPO_NAME}/commits/$branch.atom"
                val feed = statement(feedUrl).executeSafely { response ->
                    if (!response.status.isSuccess()) {
                        val text = response.bodyAsUtf8Text().take(300)
                        logcat("Updater") { "$feedUrl -> ${response.status}: $text" }
                        response.status.ensureSuccess()
                    }
                    response.bodyAsUtf8Text()
                }
                val commits = parseCommitFeed(feed)
                val latest = commits.firstOrNull() ?: return null
                if (latest.sha.take(7) != curSha) {
                    // Everything the user has not run yet, newest first.
                    val changelog = commits.takeWhile { it.sha.take(7) != curSha }
                        .take(MAX_CHANGELOG_ENTRIES)
                        .joinToString("\n") { it.title }
                        .ifEmpty { latest.title }
                    // The run is what a CI build is fetched from, and its artifacts
                    // need a token the app does not have -- so the browser it is.
                    val runsUrl = "https://${BuildConfig.REPO_HOST}/${BuildConfig.REPO_NAME}/actions?query=branch%3A$branch"
                    return EhRelease(latest.sha.take(7), changelog, runsUrl, webOnly = true)
                }
            } else {
                val curVersion = BuildConfig.RAW_VERSION_NAME
                val release = statement(LATEST_RELEASE_URL).executeAndParseAs<Release>()
                val latestVersion = release.version
                val description = release.info
                val downloadUrl = release.getDownloadLink()
                if (latestVersion != curVersion) {
                    return EhRelease(latestVersion, description, downloadUrl)
                }
            }
        }
        return null
    }

    suspend fun downloadUpdate(url: String, path: Path) {
        val isZip = url.endsWith("zip")
        timeoutBySpeed(
            url,
            {
                statement(url) {
                    // https://docs.github.com/en/rest/releases/assets?apiVersion=2022-11-28#get-a-release-asset
                    if (!isZip) accept(ContentType.Application.OctetStream)
                    it()
                }
            },
            { _, _, _ -> },
            { response ->
                if (isZip) {
                    response.bodyAsChannel().toInputStream().use { stream ->
                        ZipInputStream(stream).use { zip ->
                            zip.nextEntry
                            path.write { transferFrom(zip.asSource()) }
                        }
                    }
                } else {
                    response.bodyAsChannel().copyTo(path)
                }
            },
        )
    }
}

suspend inline fun statement(
    url: String,
    builder: HttpRequestBuilder.() -> Unit = {},
) = ktorClient.prepareGet(url) {
    apply(builder)
}

private const val MAX_CHANGELOG_ENTRIES = 10

private data class CommitEntry(val sha: String, val title: String)

private val COMMIT_SHA_REGEX = Regex("""Grit::Commit/([0-9a-f]{40})""")
private val COMMIT_TITLE_REGEX = Regex("""<title>\s*(.*?)\s*</title>""", setOf(RegexOption.DOT_MATCHES_ALL))

/**
 * The commits in a branch's feed, newest first.
 *
 * Both GitHub and Gitea publish one at `/{owner}/{repo}/commits/{branch}.atom`,
 * with the commit id and its first line in each entry. Read as text rather than
 * parsed: the shape is a handful of tags that do not move, and a feed reader
 * would be more machinery than the two fields wanted.
 */
private fun parseCommitFeed(atom: String): List<CommitEntry> = atom.split("<entry>").drop(1).mapNotNull { entry ->
    val sha = COMMIT_SHA_REGEX.find(entry)?.groupValues?.get(1) ?: return@mapNotNull null
    val title = COMMIT_TITLE_REGEX.find(entry)?.groupValues?.get(1)
        ?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString() }
        .orEmpty()
    CommitEntry(sha, title)
}

data class EhRelease(
    val version: String,
    val changelog: String,
    val downloadLink: String,
    /** Nothing to install in place: the link is a page to open, not a file. */
    val webOnly: Boolean = false,
)
