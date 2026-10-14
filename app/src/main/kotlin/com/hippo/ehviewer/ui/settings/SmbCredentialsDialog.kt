package com.hippo.ehviewer.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.smb.SmbCredentialStore
import com.hippo.ehviewer.smb.SmbCredentials
import com.hippo.ehviewer.smb.SmbLocation
import com.hippo.ehviewer.smb.SmbRepository
import com.hippo.ehviewer.smb.parseUserAndDomain
import kotlinx.coroutines.launch

/**
 * A name and a password for a server that refused the session.
 *
 * A dialog of its own rather than the one that adds a location, because it asks
 * a different question. There is no path field on purpose: the server is already
 * known -- it just said no -- and making the answer to "who are you" depend on
 * typing a path is a puzzle with nothing behind it. The test asks the server
 * about itself instead, which needs no path either, so the button that has to
 * pass before saving can always be pressed.
 */
@Composable
fun SmbCredentialsDialog(
    /** The server, as the person sees it. */
    host: String,
    port: Int,
    /**
     * The directory the refusal happened at, when it is known.
     *
     * The test asks for that directory, so it checks the thing that actually
     * failed rather than the session in general. It is null when the server
     * refused before any share was named, which is the common case.
     */
    location: SmbLocation?,
    onDismiss: () -> Unit,
    onSaved: (SmbCredentials) -> Unit,
    /**
     * Prefilled with what was tried, so a wrong password can be corrected
     * without retyping the name.
     */
    initial: SmbCredentials? = null,
) {
    var username by remember {
        mutableStateOf(
            initial?.let { if (it.domain.isEmpty()) it.user else "${it.domain}\\${it.user}" }.orEmpty(),
        )
    }
    var password by remember { mutableStateOf(initial?.password.orEmpty()) }
    var testing by remember { mutableStateOf(false) }
    var tested by remember { mutableStateOf<SmbCredentials?>(null) }
    var testMessage by remember { mutableStateOf<String?>(null) }
    // Whether there is still something stored for this server to take back. It
    // starts from what was prefilled and stops being true once it is forgotten,
    // so the button goes away with the credential it is about.
    var stored by remember { mutableStateOf(initial != null) }
    val scope = rememberCoroutineScope()
    val testingMessage = stringResource(R.string.settings_download_smb_testing)
    val testSuccessMessage = stringResource(R.string.settings_download_smb_test_success)
    val testFailedMessage = stringResource(R.string.settings_download_smb_test_failed)

    fun entered(): SmbCredentials {
        val (user, domain) = parseUserAndDomain(username)
        return SmbCredentials(user, password, domain)
    }

    fun onEdit() {
        // A change makes the last test describe credentials that are no longer
        // on screen, and Save is gated on a test that passed.
        tested = null
        testMessage = null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_download_smb_credentials)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
            ) {
                Text(
                    stringResource(R.string.settings_download_smb_credentials_for).format("$host:$port"),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = {
                        username = it
                        onEdit()
                    },
                    label = { Text(stringResource(R.string.settings_download_smb_username)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        onEdit()
                    },
                    label = { Text(stringResource(R.string.settings_download_smb_password)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                testMessage?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Button(
                        onClick = {
                            val wanted = entered()
                            testing = true
                            testMessage = testingMessage
                            scope.launch {
                                runCatching { SmbRepository.testCredentials(host, port, location, wanted) }
                                    .onSuccess { entries ->
                                        tested = wanted
                                        testMessage = testSuccessMessage.format(entries.toString())
                                    }
                                    .onFailure { error ->
                                        tested = null
                                        testMessage = testFailedMessage.format(error.message ?: error::class.simpleName.orEmpty())
                                    }
                                testing = false
                            }
                        },
                        enabled = !testing,
                    ) {
                        Text(stringResource(R.string.settings_download_smb_test))
                    }
                    if (stored) {
                        ForgetCredentialsButton(host, port) {
                            // The fields were filled from what was stored, so
                            // they go with it: leaving them would say the app
                            // still has a password it no longer has.
                            stored = false
                            username = ""
                            password = ""
                            tested = null
                            testMessage = null
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { tested?.let(onSaved) },
                enabled = !testing && tested != null,
            ) {
                Text(stringResource(R.string.settings_download_smb_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

/**
 * The way to take back the credentials stored for a server.
 *
 * Shown next to the test, because those are the two things that can be done to a
 * credential that is already saved: try it, or throw it away. Forgetting is
 * behind a confirmation -- it is the one action here that typing the same thing
 * again does not undo, and a prefilled field looks the same either way.
 */
@Composable
fun ForgetCredentialsButton(
    host: String,
    port: Int,
    onForgotten: () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }
    TextButton(
        onClick = { confirming = true },
        modifier = Modifier.padding(start = 8.dp),
    ) {
        Text(stringResource(R.string.settings_download_smb_forget))
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.settings_download_smb_forget)) },
            text = {
                Text(
                    stringResource(R.string.settings_download_smb_forget_confirm).format("$host:$port"),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        SmbCredentialStore.forget(host, port)
                        confirming = false
                        onForgotten()
                    },
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}
