package com.hippo.ehviewer.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.client.parser.Archive
import com.hippo.ehviewer.client.parser.Funds

@Composable
fun ArchiveList(
    funds: Funds,
    items: List<Archive>,
    onItemClick: (Archive) -> Unit,
    modifier: Modifier = Modifier,
    unlocked: String? = null,
) {
    val currentFunds = stringResource(R.string.current_funds)
    val archiveFree = stringResource(R.string.archive_free)
    val archiveUnlocked = stringResource(R.string.archive_unlocked)
    val archiveOriginal = stringResource(R.string.archive_original)
    val archiveResample = stringResource(R.string.archive_resample)
    val fundsStyle = MaterialTheme.typography.labelLarge
    val (hath, nonHath) = remember(items) { items.partition { it.isHath } }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(text = currentFunds, textAlign = TextAlign.Center)
        }
        item {
            FundsItem(
                type = "GP",
                amount = funds.gp,
                modifier = Modifier.fillMaxWidth(),
                textStyle = fundsStyle,
            )
        }
        item {
            FundsItem(
                type = "C",
                amount = funds.credit,
                modifier = Modifier.fillMaxWidth(),
                textStyle = fundsStyle,
            )
        }
        items(nonHath) {
            ArchiveItem(
                name = if (it.res == "org") archiveOriginal else archiveResample,
                // "Free!" on the page means the account's allowance covers it, which
                // is not the same as free: only a download that was already unlocked
                // costs nothing, and that is what the colour says too.
                label = when {
                    it.isUnlockedBy(unlocked) -> archiveUnlocked
                    it.cost == "Free!" -> archiveFree
                    else -> it.cost
                },
                size = it.size,
                onClick = { onItemClick(it) },
                unlocked = it.isUnlockedBy(unlocked),
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(text = "H@H")
            }
        }
        items(hath) {
            ArchiveItem(
                name = if (it.res == "org") archiveOriginal else it.name,
                label = if (it.cost == "Free") archiveFree else it.cost,
                size = it.size,
                onClick = { onItemClick(it) },
            )
        }
    }
}

/**
 * Whether the archiver already has this one paid for.
 *
 * The page names two kinds, "original" and "resample", and the non-H@H rows are
 * exactly those two: `res` is `org` for the original.
 */
private fun Archive.isUnlockedBy(unlocked: String?): Boolean = when (unlocked) {
    null -> false
    "original" -> res == "org"
    else -> res != "org"
}

/**
 * One archive option.
 *
 * [label] is what the line above the button says -- a price for the site's
 * archiver, the download mode for a mirror -- and [size] is what the line below
 * it says. Both are nullable because a source that does not have one leaves the
 * line out; drawing it empty reads as a value that failed to load.
 */
@Composable
internal fun ArchiveItem(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    size: String? = null,
    unlocked: Boolean = false,
) {
    val labelStyle = MaterialTheme.typography.labelMedium
    val colors = if (unlocked) {
        // Already paid for once: the archiver serves this one for free now, and the
        // cost above the button does not say so. The *container* role rather than
        // the plain one, because a dynamic colour scheme is free to give primary and
        // tertiary the same tone -- on a monochrome wallpaper they are the same
        // colour, which is a hint nobody can see.
        ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    } else {
        ButtonDefaults.buttonColors()
    }
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (label != null) {
            Text(
                text = label,
                style = labelStyle,
            )
        }
        Button(
            onClick = onClick,
            shapes = ButtonDefaults.shapes(),
            colors = colors,
            modifier = Modifier.width(100.dp),
        ) {
            Text(text = name)
        }
        if (size != null) {
            Text(
                text = size,
                style = labelStyle,
            )
        }
    }
}
