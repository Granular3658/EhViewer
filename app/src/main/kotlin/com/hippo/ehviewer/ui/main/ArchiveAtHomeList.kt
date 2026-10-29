package com.hippo.ehviewer.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R

/**
 * The archive page for archive@home, laid out like [ArchiveList].
 *
 * Same header, same balance row, same button shape, and the same pair of buttons
 * -- because the mirror answers in the same two shapes the site's archiver does:
 * from its cache, or built now. What is missing is what the source does not say.
 * Both rows are one archive of the gallery's own pages, so both are named what the
 * site calls it ("original"), both show the gallery's size from the detail page,
 * and neither carries a price: the site's "Free!" marker and its two costs belong
 * to the site's archiver.
 *
 * [onDownload] receives the `force` flag: false is a normal download -- the server
 * answers from its cache when it has the archive and builds it when it does not --
 * and true forbids the cache, so it builds regardless. The API has no cached-only
 * request, which is why the plain button is a preference and not a promise.
 */
@Composable
fun ArchiveAtHomeList(
    balance: Int,
    size: String?,
    onDownload: (force: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val balanceText = stringResource(R.string.archive_at_home_balance)
    val archiveName = stringResource(R.string.archive_original)
    val normalText = stringResource(R.string.archive_at_home_normal)
    val noCacheText = stringResource(R.string.archive_at_home_no_cache)
    val fundsStyle = MaterialTheme.typography.labelLarge
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(text = balanceText, textAlign = TextAlign.Center)
        }
        // One amount, so it takes the row the site's archiver splits between GP
        // and credits; `FundsItem` centres itself, and a half-empty row would put
        // the only number off to the left.
        item(span = { GridItemSpan(maxLineSpan) }) {
            FundsItem(
                type = "GP",
                amount = balance,
                modifier = Modifier.fillMaxWidth(),
                textStyle = fundsStyle,
                suffix = null,
            )
        }
        item {
            ArchiveItem(
                name = archiveName,
                onClick = { onDownload(false) },
                modifier = Modifier.fillMaxWidth(),
                label = normalText,
                size = size,
            )
        }
        item {
            ArchiveItem(
                name = archiveName,
                onClick = { onDownload(true) },
                modifier = Modifier.fillMaxWidth(),
                label = noCacheText,
                size = size,
            )
        }
    }
}
