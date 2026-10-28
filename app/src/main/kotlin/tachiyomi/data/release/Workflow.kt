package tachiyomi.data.release

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Repository(@SerialName("default_branch") val defaultBranch: String)

@Serializable
data class WorkflowRuns(@SerialName("workflow_runs") val workflowRuns: List<WorkflowRun>)

/**
 * A workflow run, in the shape GitHub returns it.
 *
 * `head_sha` and `display_title` are GitHub's names; the Gitea ones this used to
 * read, `commit_sha` and `title`, are absent, and a missing required field is a
 * serialisation failure rather than an empty result. `id` is a `Long` because run
 * ids are past what an `Int` holds.
 */
@Serializable
data class WorkflowRun(
    @SerialName("head_sha") val headSha: String,
    @SerialName("display_title") val title: String,
    val id: Long,
)
