package com.hippo.ehviewer.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.smb.SmbCredentials
import com.hippo.ehviewer.smb.SmbCredentialStore
import com.hippo.ehviewer.smb.SmbLocation
import com.hippo.ehviewer.smb.SmbRepository
import kotlinx.coroutines.launch

private const val SMB_PREFIX = "smb://"

/** Accepts `user`, `DOMAIN\user` and `user@domain.com` style input. */
private fun splitUserAndDomain(input: String): Pair<String, String> {
    val trimmed = input.trim()
    val backslash = trimmed.indexOf('\\')
    if (backslash >= 0) {
        return trimmed.substring(backslash + 1) to trimmed.substring(0, backslash)
    }
    val at = trimmed.indexOf('@')
    if (at > 0) {
        return trimmed.substring(0, at) to trimmed.substring(at + 1)
    }
    return trimmed to ""
}

@Composable
fun SmbLocationDialog(
    onDismiss: () -> Unit,
    onSaved: (SmbLocation) -> Unit,
    showMessage: (String) -> Unit,
) {
    var path by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var testedLocation by remember { mutableStateOf<SmbLocation?>(null) }
    var testedCredentials by remember { mutableStateOf<SmbCredentials?>(null) }
    var testMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val invalidMessage = stringResource(R.string.settings_download_smb_invalid)
    val testingMessage = stringResource(R.string.settings_download_smb_testing)

    fun onEdit() {
        testedLocation = null
        testedCredentials = null
        testMessage = null
    }

    fun parse(): Pair<SmbLocation, SmbCredentials>? {
        // The scheme is always displayed as a non-editable prefix, but a pasted
        // full URL should still work, so any leading scheme is dropped here.
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return null
        val withoutScheme = trimmed
            .removePrefix(SMB_PREFIX)
            .removePrefix("smb:/")
            .trimStart('/')
        if (withoutScheme.isEmpty()) return null
        val location = SmbLocation.parse(SMB_PREFIX + withoutScheme) ?: return null
        val (user, domain) = splitUserAndDomain(username)
        return location to SmbCredentials(user, password, domain)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_download_add_smb_location)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it; onEdit() },
                    label = { Text(stringResource(R.string.settings_download_smb_path)) },
                    placeholder = { Text(stringResource(R.string.settings_download_smb_path_hint)) },
                    prefix = {
                        Text(
                            text = SMB_PREFIX,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it; onEdit() },
                    label = { Text(stringResource(R.string.settings_download_smb_username)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; onEdit() },
                    label = { Text(stringResource(R.string.settings_download_smb_password)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                testMessage?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }
                Button(
                    onClick = {
                        val values = parse()
                        if (values == null) {
                            testMessage = invalidMessage
                        } else {
                            val (location, credentials) = values
                            testing = true
                            testMessage = testingMessage
                            scope.launch {
                                runCatching { SmbRepository.test(location, credentials) }
                                    .onSuccess { entries ->
                                        testedLocation = location
                                        testedCredentials = credentials
                                        testMessage = context.getString(R.string.settings_download_smb_test_success, entries.size)
                                    }
                                    .onFailure { error ->
                                        testedLocation = null
                                        testedCredentials = null
                                        testMessage = context.getString(R.string.settings_download_smb_test_failed, error.message ?: error::class.simpleName.orEmpty())
                                    }
                                testing = false
                            }
                        }
                    },
                    enabled = !testing,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text(stringResource(R.string.settings_download_smb_test))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val location = testedLocation
                    val credentials = testedCredentials
                    if (location == null || credentials == null) {
                        showMessage(invalidMessage)
                    } else {
                        SmbCredentialStore.put(location, credentials)
                        onSaved(location)
                    }
                },
                enabled = !testing && testedLocation != null && testedCredentials != null,
            ) {
                Text(stringResource(R.string.settings_download_smb_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
