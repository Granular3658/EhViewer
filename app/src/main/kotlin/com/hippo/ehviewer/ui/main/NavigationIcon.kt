package com.hippo.ehviewer.ui.main

import android.os.SystemClock
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ramcosta.composedestinations.navigation.DestinationsNavigator

/** How long a press keeps the button from acting again. */
private const val PRESS_SETTLE_MILLIS = 500L

@Composable
context(navigator: DestinationsNavigator)
fun NavigationIcon() {
    // One press, one pop. Two taps that land together used to pop the stack
    // twice, and with nothing left to pop the screen went blank -- which reads as
    // the app having broken, and needs a restart to get out of.
    var lastPress by remember { mutableLongStateOf(0L) }
    IconButton(
        onClick = {
            val now = SystemClock.uptimeMillis()
            if (now - lastPress < PRESS_SETTLE_MILLIS) return@IconButton
            lastPress = now
            navigator.popBackStack()
        },
        shapes = IconButtonDefaults.shapes(),
    ) {
        Icon(imageVector = Icons.AutoMirrored.Default.ArrowBack, contentDescription = null)
    }
}
