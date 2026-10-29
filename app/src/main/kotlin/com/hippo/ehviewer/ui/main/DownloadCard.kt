package com.hippo.ehviewer.ui.main

import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.ShapeDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ehviewer.core.database.model.DownloadInfo
import com.ehviewer.core.i18n.R
import com.ehviewer.core.ui.component.CrystalCard
import com.ehviewer.core.ui.component.GalleryListCardRating
import com.ehviewer.core.ui.util.TransitionsVisibilityScope
import com.ehviewer.core.ui.util.listThumbGenerator
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.archive.writeFormatLabel
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.util.FileUtils

/**
 * A write in flight: a size against a total, a rate, and a bar.
 *
 * A conversion and the archive write that follows a download are the same kind of
 * work and are reported the same way, so they are drawn by the same row.
 */
@Composable
private fun WriteProgressRow(done: Long, total: Long, speed: Long, format: Int) {
    ProvideTextStyle(MaterialTheme.typography.labelMedium) {
        Row {
            Text(text = convertSizeText(done, total))
            // What is being written, in the space the size line leaves: quieter and
            // a step smaller, because it is a label and not a number to read.
            Text(
                text = "[${writeFormatLabel(format)}]",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp).alignByBaseline(),
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(text = FileUtils.humanReadableByteCount(speed.coerceAtLeast(0)) + "/S")
        }
        LinearWavyProgressIndicator(
            progress = { if (total > 0) done.toFloat() / total else 0f },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * A size against a total, or just the size when the total is not known yet.
 *
 * The unit is said once when both sides are in it: `2.1 / 5.4 MiB` is the same
 * number as `2.1 MiB / 5.4 MiB` and leaves room on the line for what is being
 * written.
 */
private fun convertSizeText(done: Long, total: Long): String {
    if (total <= 0) return FileUtils.humanReadableByteCount(done)
    val doneText = FileUtils.humanReadableByteCount(done)
    val totalText = FileUtils.humanReadableByteCount(total)
    return if (doneText.substringAfterLast(' ') == totalText.substringAfterLast(' ')) {
        "${doneText.substringBeforeLast(' ')} / $totalText"
    } else {
        "$doneText / $totalText"
    }
}

@Composable
context(_: SharedTransitionScope, _: TransitionsVisibilityScope)
fun DownloadCard(
    onClick: () -> Unit,
    onThumbClick: () -> Unit,
    onLongClick: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    info: DownloadInfo,
    selectMode: Boolean,
    showProgress: Boolean,
    source: String? = null,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) = CrystalCard(modifier = modifier, onClick = onClick, onLongClick = onLongClick, interactionSource = interactionSource) {
    Row {
        val thumb = remember {
            movableContentOf<DownloadInfo> {
                with(listThumbGenerator) {
                    EhAsyncCropThumb(
                        key = it,
                        modifier = Modifier.aspectRatio(DEFAULT_RATIO).fillMaxSize(),
                    )
                }
            }
        }
        if (selectMode) {
            Card {
                thumb(info)
            }
        } else {
            Card(onClick = onThumbClick) {
                thumb(info)
            }
        }
        val stateFailed = stringResource(R.string.download_state_failed)
        val stateFailed2 = stringResource(R.string.download_state_failed_2, info.legacy)
        val downloadState by DownloadManager.collectDownloadState(info.gid)
        // A conversion is not a download state: the gallery is downloaded and
        // stays downloaded, it is being rewritten. Said in the same place the
        // download state is, because that is the line a row has for this.
        val convertState by DownloadManager.collectConvertProgress(info.gid)
        // The archive write that ends a download session: the row's page counters
        // are already at "all done" while this runs, so it needs its own numbers.
        val archiveWrite by DownloadManager.collectArchiveWrite(info.gid)
        Column(modifier = Modifier.padding(start = 8.dp, top = 2.dp, end = 4.dp)) {
            Text(
                text = EhUtils.getSuitableTitle(info),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(modifier = Modifier.weight(1f))
            val writing = when {
                convertState?.state == DownloadManager.ConvertState.RUNNING -> convertState
                archiveWrite != null -> archiveWrite
                else -> null
            }
            if (writing != null) {
                WriteProgressRow(writing.done, writing.total, writing.speed, writing.format)
            } else if (downloadState != DownloadInfo.STATE_DOWNLOAD) {
                val converting = convertState
                val stateText = when (downloadState) {
                    DownloadInfo.STATE_NONE -> stringResource(R.string.download_state_none)
                    DownloadInfo.STATE_WAIT -> stringResource(R.string.download_state_wait)
                    DownloadInfo.STATE_FAILED -> if (info.legacy <= 0) stateFailed else stateFailed2
                    DownloadInfo.STATE_FINISH -> {
                        if (showProgress) {
                            val readProgress by remember { EhDB.getReadProgressFlow(info.gid) }.collectAsState(0)
                            if (readProgress > 0) "${readProgress + 1}/${info.pages}P" else "${info.pages}P"
                        } else {
                            "${info.pages}P"
                        }
                    }
                    else -> null // The item has been removed and this will be disposed soon
                }
                // Only a queued conversion has no numbers yet; the row it belongs
                // to says so in the line the download state would use.
                ProvideTextStyle(MaterialTheme.typography.labelLarge) {
                    Row {
                        Text(
                            text = info.uploader.orEmpty(),
                            modifier = Modifier.alignByBaseline().alpha(if (info.disowned) 0.5f else 1f),
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = if (converting != null) stringResource(R.string.gallery_convert_queued) else stateText.orEmpty(),
                            modifier = Modifier.alignByBaseline(),
                        )
                    }
                }
            } else {
                var total by remember { mutableIntStateOf(info.total) }
                var finished by remember { mutableIntStateOf(info.finished) }
                var speed by remember { mutableLongStateOf(info.speed) }
                var sizeText by remember { mutableStateOf<String?>(null) }
                DownloadManager.updatedDownloadInfo(info) {
                    total = this.total
                    finished = this.finished
                    speed = this.speed
                    // An archive transfer counts in bytes, not pages: the percentage
                    // it reports is a stand-in for a size that does not fit the
                    // counters, and the size is what the notification shows anyway.
                    sizeText = DownloadManager.archiveByteProgress(gid)?.let { (done, all) ->
                        if (all > 0) {
                            "${FileUtils.humanReadableByteCount(done)} / ${FileUtils.humanReadableByteCount(all)}"
                        } else {
                            FileUtils.humanReadableByteCount(done)
                        }
                    }
                }
                ProvideTextStyle(MaterialTheme.typography.labelMedium) {
                    if (total <= 0 || finished < 0) {
                        LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        Row {
                            Text(text = sizeText ?: "$finished/$total")
                            Spacer(modifier = Modifier.weight(1f))
                            Text(text = FileUtils.humanReadableByteCount(speed.coerceAtLeast(0)) + "/S")
                        }
                        LinearWavyProgressIndicator(
                            progress = { finished.toFloat() / total.toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            Row {
                Column {
                    // Place the rating near the uploader text as there's more visual space
                    GalleryListCardRating(rating = info.rating, modifier = Modifier.padding(top = 1.dp, bottom = 3.dp))
                    val categoryColor = EhUtils.getCategoryColor(info.category)
                    val categoryText = EhUtils.getCategory(info.category).uppercase()
                    Row {
                        Text(
                            text = categoryText,
                            modifier = Modifier.clip(ShapeDefaults.Small).background(categoryColor).padding(vertical = 2.dp, horizontal = 8.dp),
                            color = EhUtils.getCategoryTextColor(categoryColor),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        // A null source already means "hidden": the caller decides via the pref
                        if (source != null) {
                            Text(
                                text = source,
                                modifier = Modifier.padding(start = 4.dp).clip(ShapeDefaults.Small)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(vertical = 2.dp, horizontal = 8.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                // A conversion is work in progress like a download is, so it offers
                // the same button: pause, which for a queued gallery means leaving
                // the queue and for the one being written means stopping it.
                val converting = convertState != null
                val running = converting || downloadState == DownloadInfo.STATE_WAIT || downloadState == DownloadInfo.STATE_DOWNLOAD
                val icon = when {
                    converting -> Icons.Default.Pause
                    downloadState == DownloadInfo.STATE_FINISH -> Icons.Default.DownloadDone
                    running -> Icons.Default.Pause
                    else -> Icons.Default.PlayArrow
                }
                if (selectMode) {
                    Box(modifier = Modifier.offset(4.dp).minimumInteractiveComponentSize()) {
                        Icon(imageVector = icon, contentDescription = null)
                    }
                } else {
                    IconButton(onClick = if (running) onStop else onStart, shapes = IconButtonDefaults.shapes(), modifier = Modifier.offset(4.dp)) {
                        Icon(imageVector = icon, contentDescription = null)
                    }
                }
            }
        }
    }
}
