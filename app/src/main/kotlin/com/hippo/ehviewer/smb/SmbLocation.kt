package com.hippo.ehviewer.smb

import android.net.Uri
import com.ehviewer.core.files.normalizeSmbUrl
import okio.Path
import okio.Path.Companion.toPath

/** A read-only SMB location. Credentials are deliberately absent. */
data class SmbLocation(
    val host: String,
    val port: Int,
    val share: String,
    val subPath: String,
) {
    val credentialKey: String
        get() = "smb://$host:$port/$share"

    val uriString: String
        get() = buildString {
            append(credentialKey)
            if (subPath.isNotEmpty()) append('/').append(subPath)
        }

    val path: Path
        get() = uriString.toPath()

    fun child(relativePath: String): SmbLocation = copy(
        subPath = listOf(subPath, relativePath.trim('/'))
            .filter(String::isNotEmpty)
            .joinToString("/"),
    )

    companion object {
        fun parse(path: Path): SmbLocation? = parse(path.toString())

        fun parse(value: String): SmbLocation? = runCatching {
            val normalized = value.normalizeSmbUrl()
            val uri = Uri.parse(normalized)
            check(uri.scheme.equals("smb", ignoreCase = true))
            val host = requireNotNull(uri.host)
            val port = if (uri.port == -1) 445 else uri.port
            require(port in 1..65535)
            val segments = uri.pathSegments
            require(segments.isNotEmpty())
            val share = segments.first()
            require(share.isNotEmpty())
            val subPath = segments.drop(1).joinToString("/")
            SmbLocation(host, port, share, subPath)
        }.getOrNull()
    }
}
