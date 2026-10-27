package com.hippo.ehviewer.updater

import com.ehviewer.core.files.write
import com.hippo.ehviewer.BuildConfig
import com.hippo.ehviewer.EhApplication.Companion.ktorClient
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.executeAndParseAs
import com.hippo.ehviewer.spider.timeoutBySpeed
import com.hippo.ehviewer.util.copyTo
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.util.zip.ZipInputStream
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.io.asSource
import moe.tarsin.coroutines.runSuspendCatching
import okio.Path
import tachiyomi.data.release.Artifacts
import tachiyomi.data.release.CommitComparison
import tachiyomi.data.release.Release
import tachiyomi.data.release.Repository
import tachiyomi.data.release.WorkflowRuns
import tachiyomi.data.release.downloadLink

// The endpoints below are GitHub's: a push workflow run, its artifacts and a
// commit comparison. The host follows the remote so a build from a fork asks its
// own repository, and only a GitHub remote is asked in GitHub's shape.
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
                val branch = statement(API_URL).executeAndParseAs<Repository>().defaultBranch
                val run = statement("$API_URL/actions/runs?event=push&status=success&ref=refs/heads/$branch").executeAndParseAs<WorkflowRuns>().workflowRuns[0]
                val shortSha = run.headSha.take(7)
                if (shortSha != curSha) {
                    val artifacts = statement("$API_URL/actions/runs/${run.id}/artifacts").executeAndParseAs<Artifacts>()
                    val archiveUrl = artifacts.downloadLink
                    val changelog = runSuspendCatching {
                        val commitComparisonUrl = "$API_URL/compare/$curSha...$shortSha"
                        val result = statement(commitComparisonUrl).executeAndParseAs<CommitComparison>()
                        // TODO: Prettier format, Markdown?
                        result.commits.joinToString("\n") { commit ->
                            "${commit.commit.message.takeWhile { it != '\n' }} (@${commit.commit.author.name})"
                        }
                    }.getOrDefault(run.title)
                    return EhRelease(shortSha, changelog, archiveUrl)
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

data class EhRelease(
    val version: String,
    val changelog: String,
    val downloadLink: String,
)
