package com.hippo.ehviewer.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.archive.ConvertedArchive
import com.hippo.ehviewer.archive.archiveSuffixOf
import com.hippo.ehviewer.archive.convertArchive
import com.hippo.ehviewer.archive.convertTargetFor
import com.hippo.ehviewer.archive.defaultConvertFormat
import com.hippo.ehviewer.archive.extractArchive
import com.hippo.ehviewer.archive.packDirectory
import com.hippo.ehviewer.archive.packTargetFor
import com.hippo.ehviewer.asMutableState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okio.Path

/** What the dialog is about to write, and from what. */
sealed interface ArchiveTask {
    /** The file or folder the archive is built from. */
    val source: Path

    /** Rewrite an existing archive in another format. */
    data class Convert(override val source: Path, val sourceSize: Long) : ArchiveTask

    /** Pack a directory into a new archive. */
    data class Pack(override val source: Path, val directoryName: String) : ArchiveTask

    /** Unpack an archive into a directory of its own. */
    data class Extract(override val source: Path, val sourceSize: Long, val directoryName: String) : ArchiveTask
}

/**
 * Writes one archive: either a conversion of an existing one, or a folder packed
 * into a new one.
 *
 * The two are the same job from the user's side -- choose a format, watch it go,
 * see what it cost -- so they are the same screen. The sizes are half the point:
 * the two formats differ in what they are for, and a ratio above 100% is a real
 * answer rather than a mistake.
 */
@Composable
fun ArchiveTaskDialog(
    task: ArchiveTask,
    onDismiss: () -> Unit,
    onFinished: () -> Unit = {},
    showMessage: (String) -> Unit = {},
) {
    // Three tiers, and nothing in between: the title at the dialog's own size, the
    // two things the user acts on at 14sp (the name field's text and the format),
    // and everything that only reports at 12sp or 11sp. The name field used to
    // take the default 16sp, which made it read as a heading -- it is an input,
    // and it is the one thing here the user is meant to type into, not to look at.
    val scope = rememberCoroutineScope()
    var format by remember { mutableIntStateOf(defaultConvertFormat()) }
    // One setting for both dialogs: what to do with a source once an archive holds
    // it does not depend on which screen asked, and remembering it per screen
    // would be remembering the same answer twice.
    var deleteSource by Settings.archiveDeleteSource.asMutableState()
    var name by remember {
        mutableStateOf(
            when (task) {
                is ArchiveTask.Pack -> task.directoryName + archiveSuffixOf(defaultConvertFormat())
                // The archive's own name without its extension: what it holds is
                // named after what it was.
                is ArchiveTask.Extract -> task.directoryName
                is ArchiveTask.Convert -> ""
            },
        )
    }
    var choosing by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0L to 0L) }
    var result by remember { mutableStateOf<ConvertedArchive?>(null) }
    val sameFormat = task is ArchiveTask.Convert && convertTargetFor(task.source, format) == task.source
    // Two of the three write a name the user can change; a conversion names itself.
    val named = task !is ArchiveTask.Convert
    val sameFormatMessage = stringResource(id = R.string.archive_convert_same_format)
    // Read as a format rather than through a Context: what the failure says is
    // only known when it happens, and the format is what has to follow the
    // configuration.
    val failureFormat = stringResource(id = R.string.archive_convert_failed)
    val emptyNameMessage = stringResource(
        id = if (task is ArchiveTask.Extract) R.string.archive_extract_empty_name else R.string.archive_pack_empty_name,
    )

    fun start() {
        if (sameFormat) {
            showMessage(sameFormatMessage)
            return
        }
        if (named && name.isBlank()) {
            showMessage(emptyNameMessage)
            return
        }
        result = null
        progress = 0L to 0L
        running = true
        job = scope.launch {
            try {
                result = when (task) {
                    is ArchiveTask.Convert -> convertArchive(task.source, task.sourceSize, format, deleteSource) { done, total ->
                        progress = done to total
                    }
                    is ArchiveTask.Pack -> packDirectory(task.source, name.trim(), format, deleteSource) { done, total ->
                        progress = done to total
                    }
                    is ArchiveTask.Extract -> extractArchive(task.source, task.sourceSize, name.trim(), deleteSource) { done, total ->
                        progress = done to total
                    }
                }
                // The listing this dialog was opened from is now out of date: it
                // was read before the new archive existed, so the archive is
                // missing from it until the directory is entered again.
                onFinished()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // A toast is gone in a few seconds, and a failure that is only a
                // toast leaves nothing to look at afterwards. One line here is what
                // makes the next one of these diagnosable.
                logcat(e)
                // Said the way the rest of the app says things: the screen's own
                // snackbar, the same one the archive list timeout uses. A toast is
                // a second, differently styled voice for the same kind of message.
                showMessage(failureFormat.format(e.message ?: e.toString()))
            } finally {
                running = false
            }
        }
    }

    fun pick(option: Int) {
        val suffix = archiveSuffixOf(option)
        val previous = archiveSuffixOf(format)
        choosing = false
        format = option
        if (task is ArchiveTask.Pack) {
            // The extension follows the format; whatever was typed before it stays.
            name = if (name.endsWith(previous, ignoreCase = true)) {
                name.dropLast(previous.length) + suffix
            } else {
                name + suffix
            }
        }
        // What is on screen describes the previous archive, not this one.
        result = null
        progress = 0L to 0L
    }

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(
                        id = when (task) {
                            is ArchiveTask.Pack -> R.string.archive_pack_title
                            is ArchiveTask.Extract -> R.string.archive_extract_title
                            is ArchiveTask.Convert -> R.string.archive_convert_title
                        },
                    ),
                )
                // Above the bar, because the name is what the bar is about to fill.
                if (named) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = {
                            Text(
                                text = stringResource(
                                    id = if (task is ArchiveTask.Extract) R.string.archive_task_directory else R.string.archive_task_name,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                        singleLine = true,
                        enabled = !running,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // Always here: a bar that only appears while the work runs makes
                // the dialog jump, and the jump reads as the work having started
                // over. It sits at zero before the start and at the end after it.
                LinearProgressIndicator(
                    progress = { if (progress.second > 0) progress.first.toFloat() / progress.second else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // The count keeps its line whether or not there is one, for the
                // same reason the bar does.
                Text(
                    text = if (progress.second > 0) {
                        stringResource(id = R.string.archive_convert_progress, preciseSize(progress.first), preciseSize(progress.second))
                    } else {
                        " "
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                // One line, left-aligned, and one Text rather than three: "Before:",
                // "After:" and "Ratio:" spelled out do not fit across a dialog on a
                // phone, and the third one was being clipped. The arrow says which
                // number is which, and the percentage is the ratio by being one.
                //
                // Empty until there is something to say, and blank rather than
                // absent so the dialog does not change height when it fills in: a
                // conversion and a pack both report only what they ended with, and
                // a row of dashes before that is noise.
                Text(
                    text = result?.takeIf { it.size >= 0 }?.let { converted ->
                        stringResource(
                            id = R.string.archive_task_sizes,
                            beforeOf(task, converted),
                            preciseSize(converted.size),
                            ratio(task, converted),
                        )
                    } ?: " ",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
                // The format sits in the corner the dialog has nothing else to put
                // there, and opens the way the settings menu's selector does. What
                // happens to the source is next to it, because it is the other half
                // of the same decision.
                Row(
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.clickable { deleteSource = !deleteSource }.padding(end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = deleteSource, onCheckedChange = { deleteSource = it })
                        Text(text = stringResource(id = R.string.archive_task_delete_source), style = MaterialTheme.typography.labelMedium)
                    }
                    if (task is ArchiveTask.Extract) return@Row
                    Box {
                        Row(
                            modifier = Modifier.clickable(enabled = !running) { choosing = true }.padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(text = formatName(format), style = MaterialTheme.typography.labelLarge)
                            Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                            listOf(Settings.ARCHIVE_CBZ, Settings.ARCHIVE_DWARFS).forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(text = formatName(option)) },
                                    modifier = Modifier.background(
                                        if (option == format) {
                                            MaterialTheme.colorScheme.secondaryContainer
                                        } else {
                                            Color.Transparent
                                        },
                                    ),
                                    onClick = { pick(option) },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = ::start, enabled = !running) {
                Text(text = stringResource(id = R.string.archive_convert_start))
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (running) {
                        job?.cancel()
                    } else {
                        onDismiss()
                    }
                },
            ) {
                Text(
                    text = stringResource(
                        id = if (running) R.string.archive_convert_cancel else R.string.archive_convert_close,
                    ),
                )
            }
        },
    )
}

/** The name the format selector shows, in the two places that offer one. */
@Composable
internal fun formatName(format: Int) = stringResource(
    id = when (format) {
        Settings.ARCHIVE_DWARFS -> R.string.settings_download_save_as_archive_dwarfs
        // Only the gallery dialog offers this one, and "Off" does not say what it
        // leaves behind: the pages, as files.
        Settings.ARCHIVE_OFF -> R.string.gallery_convert_format_off
        else -> R.string.settings_download_save_as_archive_cbz
    },
)

/**
 * What the new archive is measured against.
 *
 * A conversion knows it before it starts: it is the file on the other side. A
 * pack does not -- a folder's size is not in the listing, and walking it just to
 * fill in a number would be a second pass over the share -- so it reports the
 * bytes it actually wrote once the write has finished.
 */
private fun beforeOf(task: ArchiveTask, result: ConvertedArchive): String = when (task) {
    is ArchiveTask.Convert -> preciseSize(task.sourceSize)
    is ArchiveTask.Pack -> preciseSize(result.packedBytes)
    // The archive it came out of: what was unpacked is measured against the file
    // that held it.
    is ArchiveTask.Extract -> preciseSize(task.sourceSize)
}

/**
 * The new archive's size against what it was built from, which is above 100% when
 * the new format stores what the old one compressed.
 *
 * A pack has no file to compare against -- the folder's size is not in the
 * listing, and walking it just to fill in a number would be a second pass over
 * the share -- so it compares against the bytes it actually wrote, which it knows
 * anyway.
 */
private fun ratio(task: ArchiveTask, converted: ConvertedArchive): String {
    val before = when (task) {
        is ArchiveTask.Convert -> task.sourceSize
        is ArchiveTask.Pack -> converted.packedBytes
        is ArchiveTask.Extract -> task.sourceSize
    }
    return if (before <= 0 || converted.size < 0) "—" else String.format("%.1f%%", converted.size * 100.0 / before)
}

/**
 * A size to one decimal.
 *
 * The listing rounds to whole units, which is right for a list and useless here:
 * a bar that says `2 MiB / 2 MiB` for a 2.8 MB file reads as finished before it
 * has started.
 */
private fun preciseSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format("%.1f KiB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format("%.1f MiB", bytes / (1024.0 * 1024))
    else -> String.format("%.2f GiB", bytes / (1024.0 * 1024 * 1024))
}
