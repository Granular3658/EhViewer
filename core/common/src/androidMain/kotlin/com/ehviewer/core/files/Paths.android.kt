package com.ehviewer.core.files

import android.content.ContentResolver
import android.net.Uri
import androidx.core.net.toUri
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered
import okio.Path
import okio.Path.Companion.toPath
import splitties.init.appCtx

fun Path.openFileDescriptor(mode: String) = PlatformSystemFileSystem.openFileDescriptor(this, mode)

actual inline fun <T> Path.read(f: Source.() -> T) = PlatformSystemFileSystem.rawSource(this).buffered().use(f)

actual inline fun <T> Path.write(f: Sink.() -> T) = PlatformSystemFileSystem.rawSink(this).buffered().use(f)

val Path.isSmb
    get() = toString().startsWith("smb:/", ignoreCase = true)

/**
 * Turns `smb:/host/...` into `smb://host/...`. A naive `replaceFirst` would
 * turn an already valid `smb://host` into `smb:///host`, which parses back
 * with an empty host and the host name as the share - that is why the extra
 * slashes are trimmed instead of blindly appended.
 */
fun String.normalizeSmbUrl(): String {
    val s = replace('\\', '/')
    val rest = when {
        s.startsWith(SMB_SCHEME, ignoreCase = true) -> s.drop(SMB_SCHEME.length)
        s.startsWith("smb:/", ignoreCase = true) -> s.drop("smb:/".length)
        else -> return s
    }
    return SMB_SCHEME + rest.trimStart('/')
}

/**
 * Builds the provider URI for an SMB path. [kind] selects what the provider
 * answers with: `smb` lists the children of a directory, `stat` describes the
 * path itself. Mixing the two made `metadataOrNull` read the first child of a
 * non-empty directory instead of the directory.
 */
private fun Path.smbUri(kind: String): Uri = Uri.Builder()
    .scheme(ContentResolver.SCHEME_CONTENT)
    .authority("${appCtx.packageName}.smb")
    .appendPath(kind)
    // Encoded as a single segment: the location itself contains slashes. Note
    // `appendEncodedPath` and not `appendPath`: the latter would encode the
    // percent signs of an already encoded string a second time.
    .appendEncodedPath(Uri.encode(toString().normalizeSmbUrl()))
    .build()

fun Path.toUri(): Uri {
    val str = toString()
    if (str.startsWith('/')) {
        return toFile().toUri()
    }
    if (isSmb) {
        // Keep credentials out of the Path and URI. The provider resolves the
        // encoded location through SmbCredentialStore at open time.
        return smbUri("smb")
    }

    val uri = str.replaceFirst("content:/", "content://").toUri()
    val path = requireNotNull(uri.encodedPath) { "Invalid path: $str" }
    val paths = path.split('/').dropWhile { it.isEmpty() }
    return if (paths.size > 4 && paths[0] == "tree") {
        uri.buildUpon().apply {
            path(null)
            repeat(3) { i ->
                appendEncodedPath(paths[i])
            }
            val root = Uri.decode(paths[3])
            val prefix = if (root.endsWith(':')) root else "$root/"
            val suffix = uri.encodedFragment?.let { "#$it" }.orEmpty()
            appendPath(paths.subList(4, paths.size).joinToString("/", prefix, suffix))
        }.build()
    } else {
        uri
    }
}

fun Path.toStatUri(): Uri = smbUri("stat")

/**
 * The SMB URI that always resolves to the streaming pipe, never to the in-memory
 * cache.
 *
 * A consumer that asks for the whole file as a descriptor
 * (`ContentResolver.openFileDescriptor`) needs the descriptor's declared length
 * to be unknown: the platform rejects one that declares a length with
 * `FileNotFoundException("Not a whole file")`. The cached descriptor declares the
 * real length -- which is what the mmap-ing decoders want -- so this API has to
 * ask for the pipe explicitly instead.
 */
fun Path.toPipeUri(): Uri = smbUri("pipe")

private const val SMB_SCHEME = "smb://"

fun Uri.toOkioPath() = if (scheme == ContentResolver.SCHEME_FILE) {
    requireNotNull(path) { "Invalid URI: $this" }
} else {
    toString()
}.toPath()
