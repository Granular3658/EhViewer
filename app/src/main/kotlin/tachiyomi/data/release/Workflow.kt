package tachiyomi.data.release

import com.ehviewer.core.util.unreachable
import com.hippo.ehviewer.util.AppConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Repository(@SerialName("default_branch") val defaultBranch: String)

@Serializable
data class WorkflowRuns(@SerialName("workflow_runs") val workflowRuns: List<WorkflowRun>)

@Serializable
data class WorkflowRun(
    @SerialName("commit_sha") val headSha: String,
    @SerialName("title") val title: String,
    @SerialName("id") val id: Int,
)

typealias Artifacts = List<Artifact>

val Artifacts.downloadLink
    get() = (find { AppConfig.matchVariant(it.name) } ?: unreachable()).downloadLink

@Serializable
data class Artifact(
    val name: String,
    @SerialName("archive_download_url")
    val downloadLink: String,
)
