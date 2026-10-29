package com.hippo.ehviewer.client

import com.hippo.ehviewer.EhApplication.Companion.ktorClient
import com.hippo.ehviewer.Settings
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The archive@home API: a third-party mirror that builds a gallery's archive and
 * hands back a link.
 *
 * Two calls and one key. [balance] is the account's GP, which is the same currency
 * the site's own archiver spends, and [archiveUrl] is the paid one: it asks the
 * server to build the archive (or to answer from its cache) and returns where it
 * is. There is one archive per gallery and no size in the answer -- the size the
 * user is shown is the gallery's own, from the detail page.
 *
 * Nothing here is signed: the key travels as a bearer token to a host that is not
 * e-hentai, and the shared client's cookie store is keyed by URL, so no site
 * cookie follows it.
 */
object ArchiveAtHome {
    private const val BASE = "https://api.archive-at-home.org"

    /**
     * Required of a third-party client, `大类/应用标识`. The server accepts
     * `a-z0-9-_.` and a single `/`.
     */
    private const val CLIENT = "app/ehviewer"

    /** The archiver builds on request, so this is not the shared client's 10 s. */
    private const val PARSE_TIMEOUT_MILLIS = 120_000L

    private val json = Json { ignoreUnknownKeys = true }

    /** The key as stored, trimmed: a pasted value often carries a newline. */
    val key: String get() = Settings.archiveAtHomeKey.value.trim()

    val isConfigured: Boolean get() = key.isNotEmpty()

    @Serializable
    private data class Me(val balance: Int = 0)

    @Serializable
    private data class Parsed(
        @SerialName("archive_url") val archiveUrl: String? = null,
        val error: String? = null,
    )

    /** The account's balance, in GP. */
    suspend fun balance(): Int = request("/api/v1/me").let { json.decodeFromString<Me>(it).balance }

    /**
     * The link to the gallery's archive.
     *
     * The answer is one of three shapes and only the body tells them apart: a
     * build (`gp_cost` and a link), a cache hit (a link and no cost), or a
     * refusal in `error` -- which arrives with a `200`, so the status alone says
     * nothing.
     *
     * [force] is the only lever the API has over which of the first two comes
     * back: off, the server answers from its cache when it has the archive and
     * builds it when it does not; on, it builds it regardless. There is no
     * "cached only" -- a caller that must not spend anything has no way to say so.
     */
    suspend fun archiveUrl(gid: Long, token: String, force: Boolean = false): String {
        val body = request(
            path = "/api/v1/parse",
            method = "POST",
            payload = """{"gallery_id":"$gid","gallery_key":"$token","force":$force}""",
            timeoutMillis = PARSE_TIMEOUT_MILLIS,
        )
        val parsed = runCatching { json.decodeFromString<Parsed>(body) }.getOrElse {
            throw ArchiveAtHomeException(body.take(200))
        }
        return parsed.archiveUrl ?: throw ArchiveAtHomeException(parsed.error)
    }

    private suspend fun request(
        path: String,
        method: String = "GET",
        payload: String? = null,
        timeoutMillis: Long = 30_000L,
    ): String {
        val key = key
        if (key.isEmpty()) throw ArchiveAtHomeException(unauthorized = true)
        val response = ktorClient.let { client ->
            when (method) {
                "POST" -> client.post("$BASE$path") {
                    header(HttpHeaders.Authorization, "Bearer $key")
                    header("X-Client", CLIENT)
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                    timeout { requestTimeoutMillis = timeoutMillis }
                }
                else -> client.get("$BASE$path") {
                    header(HttpHeaders.Authorization, "Bearer $key")
                    header("X-Client", CLIENT)
                    timeout { requestTimeoutMillis = timeoutMillis }
                }
            }
        }
        val body = response.bodyAsText()
        if (response.status == HttpStatusCode.Unauthorized) throw ArchiveAtHomeException(unauthorized = true)
        if (response.status.value !in 200..299) throw ArchiveAtHomeException(body.take(200))
        return body
    }
}

/**
 * A refusal from archive@home.
 *
 * [unauthorized] is the one case the caller can act on -- the key is wrong or
 * revoked -- and it is the only one worth its own message. Everything else is
 * whatever the server said, which is usually a reason to wait.
 */
class ArchiveAtHomeException(
    message: String? = null,
    val unauthorized: Boolean = false,
) : Exception(message)
