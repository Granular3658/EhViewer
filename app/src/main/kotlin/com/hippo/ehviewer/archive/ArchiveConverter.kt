package com.hippo.ehviewer.archive

import android.os.ParcelFileDescriptor
import arrow.fx.coroutines.parMap
import com.ehviewer.core.files.delete
import com.ehviewer.core.files.exists
import com.ehviewer.core.files.isSmb
import com.ehviewer.core.files.metadataOrNull
import com.ehviewer.core.files.mkdirs
import com.ehviewer.core.files.moveTo
import com.ehviewer.core.files.openFileDescriptor
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.jni.archiveEntryName
import com.hippo.ehviewer.jni.archiveEntrySize
import com.hippo.ehviewer.jni.archiveWriterAppend
import com.hippo.ehviewer.jni.archiveWriterClose
import com.hippo.ehviewer.jni.archiveWriterOpen
import com.hippo.ehviewer.jni.closeArchive
import com.hippo.ehviewer.jni.dwarfsClose
import com.hippo.ehviewer.jni.dwarfsOpen
import com.hippo.ehviewer.jni.dwarfsOpenAll
import com.hippo.ehviewer.jni.dwarfsOpenSmb
import com.hippo.ehviewer.jni.dwarfsOpenSmbAll
import com.hippo.ehviewer.jni.dwarfsPageCount
import com.hippo.ehviewer.jni.dwarfsPageName
import com.hippo.ehviewer.jni.dwarfsPageSize
import com.hippo.ehviewer.jni.dwarfsReadPageToFd
import com.hippo.ehviewer.jni.dwarfsWriterAppend
import com.hippo.ehviewer.jni.dwarfsWriterClose
import com.hippo.ehviewer.jni.dwarfsWriterOpen
import com.hippo.ehviewer.jni.extractToFd
import com.hippo.ehviewer.jni.getExtension
import com.hippo.ehviewer.jni.lastArchiveError
import com.hippo.ehviewer.jni.needPassword
import com.hippo.ehviewer.jni.openArchive
import com.hippo.ehviewer.jni.openArchiveAll
import com.hippo.ehviewer.jni.openArchiveSmb
import com.hippo.ehviewer.jni.openArchiveSmbAll
import com.hippo.ehviewer.jni.providePassword
import com.hippo.ehviewer.smb.SmbLocation
import com.hippo.ehviewer.smb.SmbRepository
import com.hippo.ehviewer.spider.FileNameRegex
import com.hippo.ehviewer.spider.TEMP_SUFFIX as PAGE_TEMP_SUFFIX
import com.hippo.ehviewer.util.LocalEntry
import com.hippo.ehviewer.util.listLocalEntries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Path

/**
 * How many entries are copied at once.
 *
 * Each one costs a pipe and, for an archive, a reader context, and the contexts
 * come from a pool of twenty: the reader itself keeps four in flight, so staying
 * at that number leaves the rest alone and still keeps the share busy enough that
 * the copy is not one round trip after another.
 */
private const val CHUNK = 4

/** The suffix the archive is built under before it is renamed onto its name. */
private const val TEMP_SUFFIX = ".tmp"

/** What a conversion or a pack produced. */
data class ConvertedArchive(
    val path: Path,
    /** The finished archive's length, or -1 when the share would not say. */
    val size: Long,
    /** The entries' own lengths, which is what the archive was built from. */
    val packedBytes: Long,
)

/** The suffix [format] writes. */
fun archiveSuffixOf(format: Int): String = if (format == Settings.ARCHIVE_DWARFS) ".dwarfs" else ".cbz"

/**
 * What a write is producing, as the row and the notification name it.
 *
 * Not a resource on purpose: these are the names of the formats, and they read the
 * same in every language the app speaks.
 */
fun writeFormatLabel(format: Int): String = when (format) {
    Settings.ARCHIVE_DWARFS -> "dwarfs"
    Settings.ARCHIVE_OFF -> "loose"
    else -> "cbz"
}

/**
 * The format a conversion starts on: the one the download settings would write.
 *
 * "Save as archive" has an off position, and a conversion has none -- writing an
 * archive is the whole point of it -- so off means DwarFS, which is the smaller
 * of the two and the one that keeps a gallery under the same size it had.
 */
fun defaultConvertFormat(): Int = if (Settings.archiveFormat.value == Settings.ARCHIVE_CBZ) {
    Settings.ARCHIVE_CBZ
} else {
    Settings.ARCHIVE_DWARFS
}

/** The file [source] would be rewritten to, next to itself. */
fun convertTargetFor(source: Path, format: Int): Path {
    val name = source.name
    val base = name.substringBeforeLast('.', name)
    return source.parent!! / (base + archiveSuffixOf(format))
}

/** The file a pack of [source] would be written to, next to the directory. */
fun packTargetFor(source: Path, name: String): Path = source.parent!! / name

/** Whether [name] is a DwarFS image, which is read through a different API. */
fun isDwarfsName(name: String) = name.endsWith(".dwarfs", ignoreCase = true)

/**
 * Rewrites the archive at [source] into [format], entry by entry.
 *
 * This converts a file, not a gallery: every regular file the source holds is
 * copied, under the name it already had, in the order the source keeps them. A
 * `ComicInfo.xml` beside the pages stays beside the pages, and a name that was
 * `cover.webp` is still `cover.webp` afterwards -- what the conversion changes is
 * the container, and nothing else.
 *
 * Nothing is decoded: each entry is copied out of the source and into the new
 * archive through a pipe, so a conversion is two passes over the bytes and no
 * image work at all. Progress counts bytes because every entry's length is known
 * before the first one moves.
 *
 * The native reader holds one archive at a time, which this relies on: the
 * browser and the reader are separate destinations, so a conversion and a
 * gallery cannot be open at once.
 */
suspend fun convertArchive(
    source: Path,
    sourceSize: Long,
    format: Int,
    deleteSource: Boolean = false,
    onProgress: (done: Long, total: Long) -> Unit,
): ConvertedArchive = withContext(Dispatchers.IO) {
    require(format == Settings.ARCHIVE_CBZ || format == Settings.ARCHIVE_DWARFS) { "Unsupported format $format" }
    val target = convertTargetFor(source, format)
    require(target != source) { "The archive is already in this format" }
    val reader = openReader(source, sourceSize)
    val converted = try {
        val count = reader.entryCount
        check(count > 0) { lastArchiveError() ?: "The archive holds no entries" }
        val needsSizes = format == Settings.ARCHIVE_CBZ
        val entries = (0 until count).map { index ->
            val size = reader.sizeOf(index)
            check(!needsSizes || size >= 0) { "Entry ${index + 1} has no length in the source" }
            Entry(reader.nameOf(index), size.coerceAtLeast(0)) { pipe -> reader.copyTo(index, pipe.fd) }
        }
        // A DwarFS image hands out its entries one at a time -- the image itself is
        // behind one lock, and that lock is held for the whole copy. Several
        // copies in flight would then wait on each other while the writer waits
        // for the first of them, which is a deadlock, not a slowdown: the entry it
        // is reading is not the entry the lock is held for. So the image is fed
        // one entry at a time; an archive is read through a pool of contexts and
        // needs no such rule.
        val chunk = if (isDwarfsName(source.name)) 1 else CHUNK
        writeArchive(target, format, entries, chunk, onProgress)
    } finally {
        runCatching { reader.close() }
    }
    if (deleteSource) runCatching { source.delete() }
    converted
}

/**
 * Writes the files under [source] into a new archive named [name].
 *
 * The folder is a folder, not a gallery: everything under it goes in, nested
 * directories and all, under the names it has. Lengths come from the listing,
 * which is what the write needs anyway -- the zip writer declares each entry's
 * length before it reads it -- so the total is known before the first byte moves
 * and the bar can count bytes.
 */
suspend fun packDirectory(
    source: Path,
    name: String,
    format: Int,
    deleteSource: Boolean = false,
    onProgress: (done: Long, total: Long) -> Unit,
): ConvertedArchive = withContext(Dispatchers.IO) {
    require(format == Settings.ARCHIVE_CBZ || format == Settings.ARCHIVE_DWARFS) { "Unsupported format $format" }
    require(name.isNotBlank() && !name.contains('/')) { "The name is not a file name" }
    val target = packTargetFor(source, name)
    val files = mutableListOf<PackedFile>()
    collectFiles(source, "", files)
    check(files.isNotEmpty()) { "The folder holds no files" }
    val needsSizes = format == Settings.ARCHIVE_CBZ
    val entries = files.map { file ->
        check(!needsSizes || file.size >= 0) { "${file.name} has no length in the listing" }
        Entry(file.name, file.size.coerceAtLeast(0)) { pipe -> streamInto(file.path, pipe) }
    }
    val packed = writeArchive(target, format, entries, CHUNK, onProgress)
    if (deleteSource) deleteRecursively(source)
    packed
}

/**
 * Rewrites the archive at [source] into [format] as a gallery.
 *
 * The gallery is the pages: what is written is the images the reader would show,
 * in the order it would show them, named by where they come in that order. What
 * the source holds besides them -- a `ComicInfo.xml`, a thumbnail, a directory --
 * is not part of the gallery and does not survive. That is the difference between
 * this and [convertArchive]: a download list converts what it knows a gallery to
 * be, and the browser converts a file.
 *
 * The reader holds one archive at a time, which this relies on in the same way the
 * browser's conversion does.
 */
suspend fun convertGalleryArchive(
    source: Path,
    sourceSize: Long,
    format: Int,
    onProgress: (done: Long, total: Long) -> Unit,
): ConvertedArchive = withContext(Dispatchers.IO) {
    require(format == Settings.ARCHIVE_CBZ || format == Settings.ARCHIVE_DWARFS || format == Settings.ARCHIVE_OFF) {
        "Unsupported format $format"
    }
    val reader = openReader(source, sourceSize, all = false)
    try {
        val count = reader.entryCount
        check(count > 0) { lastArchiveError() ?: "The archive holds no entries" }
        val entries = (0 until count).map { index ->
            // Named by position, not by the name it came in with: the gallery's
            // order is the reader's, and a name is what says which page is which.
            // The extension is taken from that name rather than from
            // `getExtension`, which reads the zip side's own table -- a DwarFS
            // image has no such table, and asking it for one is a null dereference.
            val name = reader.nameOf(index)
            Entry(entryName(index, name.substringAfterLast('.')), reader.sizeOf(index).coerceAtLeast(0)) { pipe ->
                reader.copyTo(index, pipe.fd)
            }
        }
        if (format == Settings.ARCHIVE_OFF) {
            // "Not archived" is a gallery of loose pages: they go where the page
            // downloader would have put them, beside the archive they came out of,
            // and the archive itself is left for the caller to remove.
            return@withContext writeLoosePages(source.parent!!, entries, onProgress)
        }
        val target = source.parent!! / (source.name.substringBeforeLast('.', source.name) + archiveSuffixOf(format))
        require(target != source) { "The archive is already in this format" }
        val chunk = if (isDwarfsName(source.name)) 1 else CHUNK
        writeArchive(target, format, entries, chunk, onProgress)
    } finally {
        runCatching { reader.close() }
    }
}

/**
 * Packs the pages a gallery already has on disk into an archive.
 *
 * This is the other direction of [convertGalleryArchive]: a gallery that was
 * downloaded page by page has no archive to rewrite, and what it has is the pages
 * themselves. They are named `%08d.ext` by the downloader, so the order they come
 * in is the order they sort in, and the archive keeps those names.
 *
 * A gallery that holds only some of its pages is packed as it is. Downloading a
 * part of a gallery on purpose is a thing people do, and the pages that are there
 * are a gallery; refusing them would leave packing by hand as the only way to get
 * an archive of one.
 *
 * The pages are removed once the archive is in place, which is what makes this a
 * conversion and not a copy: the reader prefers an archive over loose images, so
 * what is left behind would never be read again -- and neither would the
 * downloader write to it. A gallery that has an archive counts as downloaded
 * (`ensureDownload` finishes the moment it sees one), so there is no resume to
 * keep the pages for. A partial gallery goes the same way, and its archive holds
 * the pages that were there under the names they had.
 */
suspend fun packGalleryImages(
    directory: Path,
    gid: Long,
    format: Int,
    onProgress: (done: Long, total: Long) -> Unit,
): ConvertedArchive = withContext(Dispatchers.IO) {
    require(format == Settings.ARCHIVE_CBZ || format == Settings.ARCHIVE_DWARFS) { "Unsupported format $format" }
    // A page still being written carries the downloader's temp suffix, and the
    // name pattern matches a prefix: without this, half a page would be packed as
    // a whole one.
    val pages = listEntries(directory)
        .filter { !it.isDirectory && it.name.matches(FileNameRegex) && !it.name.endsWith(PAGE_TEMP_SUFFIX) }
        .sortedBy { it.name }
    check(pages.isNotEmpty()) { "The gallery holds no pages" }
    val entries = pages.map { page ->
        Entry(page.name, page.size) { pipe -> streamInto(directory / page.name, pipe) }
    }
    val packed = writeArchive(directory / "$gid${archiveSuffixOf(format)}", format, entries, CHUNK, onProgress)
    // The archive is in place, so the files it was built from are the copy nothing
    // reads any more. One that will not go is not worth failing over.
    withContext(Dispatchers.IO) {
        pages.parMap(concurrency = 10) { page -> runCatching { (directory / page.name).delete() } }
    }
    packed
}

/**
 * Writes the pages out as loose files.
 *
 * The names are the ones the page downloader gives: `%08d.ext`, so a gallery that
 * came out of an archive is read exactly like one that was downloaded page by
 * page. A file that is already there is replaced -- the pages are the gallery, and
 * an older copy of a page is not something to keep.
 */
private suspend fun writeLoosePages(
    directory: Path,
    entries: List<Entry>,
    onProgress: (Long, Long) -> Unit,
): ConvertedArchive {
    val total = entries.sumOf(Entry::size)
    var done = 0L
    directory.mkdirs()
    for (entry in entries) {
        currentCoroutineContext().ensureActive()
        val opened = (directory / entry.name).openFileDescriptor("wt")
        try {
            check(entry.copyTo(opened)) { "${entry.name} could not be written" }
        } finally {
            runCatching { opened.close() }
        }
        done += entry.size
        onProgress(done, total)
    }
    return ConvertedArchive(directory, done, done)
}

/**
 * Writes the files of the archive at [source] into a directory beside it.
 *
 * The archive is a container, so what comes out is everything it holds, under the
 * names and the paths it holds them under: a `ComicInfo.xml` comes out as a file
 * beside the pages, and a nested directory is made rather than flattened. Nothing
 * is decoded -- each entry is copied to a descriptor of its own.
 *
 * The directory has to be a new one. Extracting onto a directory that is already
 * there would merge two sets of files with nothing to say which came from where,
 * and a failed run would leave no way to tell what it had got through -- the same
 * reason a write goes to a part name first.
 */
suspend fun extractArchive(
    source: Path,
    sourceSize: Long,
    directoryName: String,
    deleteSource: Boolean = false,
    onProgress: (done: Long, total: Long) -> Unit,
): ConvertedArchive = withContext(Dispatchers.IO) {
    require(directoryName.isNotBlank() && !directoryName.contains('/')) { "The name is not a directory name" }
    val target = source.parent!! / directoryName
    require(!target.exists()) { "There is already something called $directoryName" }
    val reader = openReader(source, sourceSize)
    var written = 0L
    try {
        val count = reader.entryCount
        check(count > 0) { lastArchiveError() ?: "The archive holds no entries" }
        // One length per entry, read from the source's own table before the first
        // byte moves, so the bar counts bytes rather than entries.
        val sizes = (0 until count).map { reader.sizeOf(it).coerceAtLeast(0) }
        val total = sizes.sum()
        target.mkdirs()
        for (index in 0 until count) {
            currentCoroutineContext().ensureActive()
            val name = reader.nameOf(index)
            val file = target / name
            file.parent?.mkdirs()
            val opened = file.openFileDescriptor("wt")
            try {
                check(reader.copyTo(index, opened.fd)) { "$name could not be read" }
            } finally {
                runCatching { opened.close() }
            }
            written += sizes[index]
            onProgress(written, total)
        }
    } finally {
        runCatching { reader.close() }
    }
    if (deleteSource) runCatching { source.delete() }
    ConvertedArchive(target, written, sourceSize)
}

/**
 * Builds the archive under a part name, then renames it onto [target].
 *
 * A target that is already there -- the other format of the same archive, most of
 * the time -- is never truncated by a write that does not finish, and a cancel
 * leaves it as it was.
 */
private suspend fun writeArchive(
    target: Path,
    format: Int,
    entries: List<Entry>,
    chunk: Int,
    onProgress: (Long, Long) -> Unit,
): ConvertedArchive {
    val part = target.parent!! / (target.name + TEMP_SUFFIX)
    var output: ParcelFileDescriptor? = null
    var completed = false
    try {
        val opened = part.openFileDescriptor("rwt")
        output = opened
        writeEntries(opened, format, entries, chunk, onProgress)
        // Closing the descriptor waits for the share to have the bytes, so the
        // length read back afterwards is the finished archive's.
        opened.close()
        output = null
        part moveTo target
        completed = true
        return ConvertedArchive(
            target,
            runCatching { target.metadataOrNull()?.size }.getOrNull() ?: -1L,
            entries.sumOf(Entry::size),
        )
    } finally {
        runCatching { output?.close() }
        if (!completed) runCatching { part.delete() }
    }
}

/** Feeds [entries] to the native writer through one pipe per entry. */
private suspend fun writeEntries(
    output: ParcelFileDescriptor,
    format: Int,
    entries: List<Entry>,
    chunk: Int,
    onProgress: (Long, Long) -> Unit,
) {
    val writer = openWriter(output.fd, format == Settings.ARCHIVE_DWARFS)
    var closed = false
    try {
        val total = entries.sumOf(Entry::size)
        var done = 0L
        var written = 0
        for (start in entries.indices step chunk) {
            // Between chunks is where stopping can stop: a chunk is a blocking
            // native copy, and the whole point of a chunk is that it is short.
            currentCoroutineContext().ensureActive()
            onProgress(done, total)
            val end = minOf(start + chunk, entries.size)
            val sources = ArrayList<Source>(end - start)
            val pipes = ArrayList<ParcelFileDescriptor>(end - start)
            try {
                coroutineScope {
                    for (index in start until end) {
                        val entry = entries[index]
                        val (readSide, writeSide) = ParcelFileDescriptor.createPipe()
                        pipes += readSide
                        sources += Source(readSide.fd, entry.size, entry.name)
                        // The copy blocks until the writer has taken every byte,
                        // and the writer only takes them once it reaches this
                        // entry, so the two run against each other.
                        launch(Dispatchers.IO) {
                            try {
                                check(entry.copyTo(writeSide)) { "${entry.name} could not be read" }
                            } finally {
                                runCatching { writeSide.close() }
                            }
                        }
                    }
                    written += writer.append(sources)
                }
            } finally {
                pipes.forEach { runCatching { it.close() } }
            }
            done += entries.subList(start, end).sumOf(Entry::size)
            onProgress(done, total)
        }
        check(written == entries.size) { "Wrote $written of ${entries.size} entries" }
        writer.close()
        closed = true
    } finally {
        // The writer is one global on the native side, so it has to be let go even
        // when the write never finished -- otherwise the next one opens onto
        // nothing.
        if (!closed) runCatching { writer.close() }
    }
}

/** One entry on its way into the new archive, and how to read it. */
private class Entry(val name: String, val size: Long, val copyTo: (ParcelFileDescriptor) -> Boolean)

/** One file under a folder being packed, with the name it will get in the archive. */
private class PackedFile(val path: Path, val name: String, val size: Long)

/**
 * Every file under [directory], named relative to it.
 *
 * Nested directories are walked because the archive is a copy of the folder: a
 * file one level down belongs in it as much as the ones at the top, and leaving
 * it out would be the conversion deciding what the folder means.
 */
private suspend fun collectFiles(directory: Path, prefix: String, out: MutableList<PackedFile>) {
    for (entry in listEntries(directory)) {
        val name = if (prefix.isEmpty()) entry.name else "$prefix/${entry.name}"
        val child = directory / entry.name
        if (entry.isDirectory) {
            collectFiles(child, name, out)
        } else {
            out += PackedFile(child, name, entry.size)
        }
    }
}

/**
 * The children of [directory], with their lengths, from whichever side it is on.
 *
 * A share answers a listing in one call; a device directory answers a query.
 * Both give every child's length in that one answer, which is what a write needs
 * before it starts: the zip writer declares each entry's length before it reads
 * it, so the total has to be known first.
 */
private suspend fun listEntries(directory: Path): List<LocalEntry> = if (directory.isSmb) {
    val location = SmbLocation.parse(directory.toString()) ?: error("Not a share: $directory")
    SmbRepository.list(location).map { LocalEntry(it.name, it.isDirectory, it.size) }
} else {
    directory.listLocalEntries()
}

/** Copies one file on the share into [target], closing it so the writer sees the end. */
private fun streamInto(file: Path, target: ParcelFileDescriptor): Boolean = runCatching {
    file.openFileDescriptor("r").use { source ->
        ParcelFileDescriptor.AutoCloseInputStream(source).use { input ->
            ParcelFileDescriptor.AutoCloseOutputStream(target).use { output ->
                input.copyTo(output, DEFAULT_BUFFER_SIZE)
            }
        }
    }
    true
}.getOrElse {
    logcat("pack", it)
    false
}

/** Removes [directory] and everything under it. */
private suspend fun deleteRecursively(directory: Path) {
    for (entry in runCatching { listEntries(directory) }.getOrDefault(emptyList())) {
        val child = directory / entry.name
        if (entry.isDirectory) deleteRecursively(child) else runCatching { child.delete() }
    }
    runCatching { directory.delete() }
}

/** One entry's bytes on their way into the new archive: a pipe, its length, its name. */
private class Source(val fd: Int, val size: Long, val name: String)

/**
 * The two writers take different things, so the calls are dispatched here rather
 * than forced into one shape: the zip writer declares each entry's length, the
 * image writer reads each source to its end and takes none.
 */
private class Writer(private val dwarfs: Boolean, private val handle: Long) {
    fun append(sources: List<Source>): Int = if (dwarfs) {
        dwarfsWriterAppend(
            handle,
            sources.map(Source::fd).toIntArray(),
            sources.map(Source::name).toTypedArray(),
            sources.size,
        )
    } else {
        archiveWriterAppend(
            sources.map(Source::fd).toIntArray(),
            sources.map(Source::size).toLongArray(),
            sources.map(Source::name).toTypedArray(),
            sources.size,
        )
    }

    fun close() {
        val code = if (dwarfs) dwarfsWriterClose(handle) else archiveWriterClose()
        check(code == 0) { "Failed to finish the archive" }
    }
}

private fun openWriter(fd: Int, dwarfs: Boolean): Writer = if (dwarfs) {
    Writer(true, dwarfsWriterOpen(fd, Settings.dwarfsCompressionLevel.value))
} else {
    check(archiveWriterOpen(fd) == 0) { "The archive writer could not be opened" }
    Writer(false, 0)
}

/** The entries of an archive, whichever of the two formats it is in. */
private interface SourceReader : AutoCloseable {
    val entryCount: Int
    fun nameOf(index: Int): String
    fun sizeOf(index: Int): Long
    fun copyTo(index: Int, fd: Int): Boolean
}

private class ArchiveReader(private val handle: Long, override val entryCount: Int) : SourceReader {
    override fun nameOf(index: Int) = archiveEntryName(index) ?: entryName(index, getExtension(index))

    override fun sizeOf(index: Int) = archiveEntrySize(index)

    override fun copyTo(index: Int, fd: Int) = extractToFd(index, fd)

    override fun close() {
        closeArchive()
        SmbRepository.close(handle)
    }
}

private class LocalArchiveReader(private val pfd: ParcelFileDescriptor, override val entryCount: Int) : SourceReader {
    override fun nameOf(index: Int) = archiveEntryName(index) ?: entryName(index, getExtension(index))

    override fun sizeOf(index: Int) = archiveEntrySize(index)

    override fun copyTo(index: Int, fd: Int) = extractToFd(index, fd)

    override fun close() {
        closeArchive()
        pfd.close()
    }
}

private class LocalDwarfsReader(private val pfd: ParcelFileDescriptor, private val image: Long, override val entryCount: Int) : SourceReader {
    override fun nameOf(index: Int) = dwarfsPageName(image, index)

    override fun sizeOf(index: Int) = dwarfsPageSize(image, index)

    override fun copyTo(index: Int, fd: Int) = dwarfsReadPageToFd(image, index, fd)

    override fun close() {
        dwarfsClose(image)
        pfd.close()
    }
}

private class DwarfsReader(private val handle: Long, private val image: Long, override val entryCount: Int) : SourceReader {
    override fun nameOf(index: Int) = dwarfsPageName(image, index)

    override fun sizeOf(index: Int) = dwarfsPageSize(image, index)

    override fun copyTo(index: Int, fd: Int) = dwarfsReadPageToFd(image, index, fd)

    override fun close() {
        dwarfsClose(image)
        SmbRepository.close(handle)
    }
}

/** The name an entry gets when the archive did not give one. */
private fun entryName(index: Int, extension: String) = "%08d.%s".format(index + 1, extension)

/**
 * Opens [source] for reading, whichever side it is on.
 *
 * A share is read through an SMB handle: the descriptor for a remote file is a
 * pipe, and an archive needs to be mapped and seeked. A file on the device is
 * read through its own descriptor, which is why the two paths end in different
 * native calls -- and why the descriptor is held here: the native side closes
 * the archive, not the descriptor.
 */
private suspend fun openReader(source: Path, size: Long, all: Boolean = true): SourceReader {
    if (!source.isSmb) {
        val pfd = source.openFileDescriptor("r")
        try {
            val length = pfd.statSize.takeIf { it > 0 } ?: size
            if (isDwarfsName(source.name)) {
                val cache = Settings.dwarfsCacheSize.value
                val image = if (all) dwarfsOpenAll(pfd.fd, cache) else dwarfsOpen(pfd.fd, cache)
                return LocalDwarfsReader(pfd, image, dwarfsPageCount(image))
            }
            val count = if (all) openArchiveAll(pfd.fd, length) else openArchive(pfd.fd, length, true)
            if (count > 0 && needPassword() && Settings.archivePasswds.none(::providePassword)) {
                throw IllegalStateException("The archive needs a password")
            }
            return LocalArchiveReader(pfd, count)
        } catch (e: Throwable) {
            runCatching { pfd.close() }
            throw e
        }
    }
    val location = SmbLocation.parse(source.toString()) ?: error("Not a share: $source")
    val handle = SmbRepository.open(location)
    try {
        if (isDwarfsName(source.name)) {
            val cache = Settings.dwarfsCacheSize.value
            val image = if (all) dwarfsOpenSmbAll(handle.value, handle.size, cache) else dwarfsOpenSmb(handle.value, handle.size, cache)
            return DwarfsReader(handle.value, image, dwarfsPageCount(image))
        }
        val count = if (all) openArchiveSmbAll(handle.value, handle.size) else openArchiveSmb(handle.value, handle.size, true)
        // A password the app already knows is worth trying: the reader does the
        // same, and a conversion is a read of the same archive.
        if (count > 0 && needPassword() && Settings.archivePasswds.none(::providePassword)) {
            throw IllegalStateException("The archive needs a password")
        }
        return ArchiveReader(handle.value, count)
    } catch (e: Throwable) {
        runCatching { SmbRepository.close(handle.value) }
        throw e
    }
}
