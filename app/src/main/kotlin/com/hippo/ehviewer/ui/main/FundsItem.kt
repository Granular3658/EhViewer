package com.hippo.ehviewer.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Badge
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.ehviewer.core.ui.component.RollingNumber

@Composable
fun FundsItem(
    type: String,
    amount: Int,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = LocalTextStyle.current,
    // The site reports GP in thousands, so the badge carries a "k"; an amount
    // that is already in GP -- a mirror's balance -- must not grow one.
    suffix: String? = if (type == "GP") "k" else null,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) {
            Text(text = type)
        }
        RollingNumber(number = amount, style = textStyle, separator = true)
        if (suffix != null) {
            Text(text = suffix)
        }
    }
}
