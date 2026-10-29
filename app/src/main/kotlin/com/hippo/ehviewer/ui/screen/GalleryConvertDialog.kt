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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.archive.defaultConvertFormat

/**
 * Asks what format the selected galleries should be written in.
 *
 * Nothing else: a conversion of several galleries is not one operation, so what
 * each of them got to and how fast is said by the notification the work runs
 * under, and a bar here would describe whichever one happened to be in front.
 */
@Composable
fun GalleryConvertDialog(count: Int, onDismiss: () -> Unit, onStart: (Int) -> Unit) {
    var format by remember { mutableIntStateOf(defaultConvertFormat()) }
    var choosing by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(id = R.string.gallery_convert_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(id = R.string.gallery_convert_count, count),
                    style = MaterialTheme.typography.bodySmall,
                )
                // The same selector as the browser's task dialog, in the same
                // place: it is the one thing to decide, so it sits where the eye
                // goes last and the button is under it.
                Row(
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        Row(
                            modifier = Modifier.clickable { choosing = true }.padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(text = formatName(format), style = MaterialTheme.typography.labelLarge)
                            Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                            listOf(Settings.ARCHIVE_CBZ, Settings.ARCHIVE_DWARFS, Settings.ARCHIVE_OFF).forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(text = formatName(option)) },
                                    modifier = Modifier.background(
                                        if (option == format) {
                                            MaterialTheme.colorScheme.secondaryContainer
                                        } else {
                                            Color.Transparent
                                        },
                                    ),
                                    onClick = {
                                        choosing = false
                                        format = option
                                    },
                                )
                            }
                        }
                    }
                }
                Text(
                    text = stringResource(id = R.string.gallery_convert_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onStart(format) }) {
                Text(text = stringResource(id = R.string.archive_convert_start))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(id = R.string.archive_convert_close))
            }
        },
    )
}
