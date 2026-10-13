package com.hippo.ehviewer.ui.screen

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.download.invalidateDownloadLocationCache
import com.hippo.ehviewer.smb.SmbLocation
import com.hippo.ehviewer.smb.SmbRepository
import com.hippo.ehviewer.ui.LocalSnackBarHostState
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.main.NavigationIcon
import com.hippo.ehviewer.ui.navToReader
import com.hippo.ehviewer.ui.settings.SmbLocationDialog
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.launch
import moe.tarsin.snackbar

private const val DEFAULT_SMB_PORT = 445

/** Extensions [navToReader] knows how to open as a gallery. */
private val ARCHIVE_SUFFIXES = listOf(".zip", ".cbz", ".dwarfs")

/** Separates a host row from the name discovery gave it. */
private val TAB_CHAR = 0x09.toChar()

private fun isArchive(name: String) = ARCHIVE_SUFFIXES.any { name.endsWith(it, ignoreCase = true) }

private data class SavedHost(val host: String, val port: Int, val name: String?) {
    val address: String
        get() = "${if (host.contains(':')) "[$host]" else host}:$port"

    val label: String
        get() = name?.let { "$it ($address)" } ?: address
}

/** Stored rows may be plain host[:port] or address + tab + discovered name. */
private fun parseSavedHost(value: String): SavedHost {
    val parts = value.split(TAB_CHAR, limit = 2)
    val endpoint = parts[0]
    val savedName = parts.getOrNull(1)
    val parsed = if (endpoint.startsWith('[')) {
        val closing = endpoint.indexOf(']')
        if (closing > 0) {
            val host = endpoint.substring(1, closing)
            val port = endpoint.substring(closing + 1).removePrefix(":").toIntOrNull() ?: DEFAULT_SMB_PORT
            host to port
        } else {
            endpoint to DEFAULT_SMB_PORT
        }
    } else {
        val lastColon = endpoint.lastIndexOf(':')
        if (lastColon > 0 && endpoint.substring(lastColon + 1).toIntOrNull() != null) {
            endpoint.substring(0, lastColon) to endpoint.substring(lastColon + 1).toInt()
        } else {
            endpoint to DEFAULT_SMB_PORT
        }
    }
    return SavedHost(parsed.first, parsed.second, savedName?.takeIf(String::isNotBlank))
}

private fun discoveredHostRow(value: String): String {
    val parts = value.split(TAB_CHAR, limit = 2)
    val host = parts[0]
    val name = parts.getOrNull(1)
    val address = parseSavedHost(host).address
    return if (name.isNullOrBlank()) address else "$address$TAB_CHAR$name"
}

private fun String.appendSegment(segment: String) = if (isEmpty()) segment else "$this/$segment"

/**
 * A browser for the SMB shares on the local network.
 *
 * Three levels, each a list: the hosts that have been entered, the shares a
 * host offers, and the directories inside a share. The breadcrumb is that
 * stack read out loud, so tapping a segment returns to that level.
 *
 * Nothing discovers hosts yet, so a host arrives by being typed in and is then
 * remembered. Reading a share needs credentials, and they are asked for only
 * after a listing fails: the host is usually already configured for downloads,
 * so asking up front would ask for something the app often already has.
 */
@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.LocalNetworkScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    val savedHosts by Settings.localNetworkHosts.collectAsState()
    var host by rememberSaveable { mutableStateOf<String?>(null) }
    var port by rememberSaveable { mutableIntStateOf(DEFAULT_SMB_PORT) }
    var share by rememberSaveable { mutableStateOf<String?>(null) }
    var subPath by rememberSaveable { mutableStateOf("") }
    var addingHost by remember { mutableStateOf(false) }
    var savingLocation by remember { mutableStateOf<SmbLocation?>(null) }
    var discovering by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val cannotList = stringResource(id = R.string.local_network_cannot_list)
    val alreadyAdded = stringResource(id = R.string.settings_download_location_already_added)
    // `snackbar` takes the host as a context receiver, so it has to be called
    // with that context in hand rather than from a plain local function.
    val snackbarHostState = LocalSnackBarHostState.current
    val scope = rememberCoroutineScope()
    val launchSnackbar: (String) -> Unit = { message ->
        scope.launch { with(snackbarHostState) { snackbar(message) } }
    }

    /** Ask the network, and keep whatever answers as if it had been typed in. */
    fun discover() {
        if (discovering) return
        discovering = true
        scope.launch {
            // The Wi-Fi stack drops multicast without this, and a probe that
            // goes unanswered is indistinguishable from an empty network.
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val lock = wifi?.createMulticastLock("ehviewer-wsd")?.apply { setReferenceCounted(false) }
            val found = runCatching {
                lock?.acquire()
                SmbRepository.discoverHosts(wifiInterfaceAddress(context))
            }.onFailure { launchSnackbar(cannotList) }.getOrElse { emptyList() }
            runCatching { lock?.takeIf { it.isHeld }?.release() }
            if (found.isNotEmpty()) {
                Settings.localNetworkHosts.value =
                    Settings.localNetworkHosts.value + found.map(::discoveredHostRow)
            }
            discovering = false
        }
    }

    // The listing for the level on screen, or null while it is being fetched.
    var names by remember { mutableStateOf<List<String>?>(null) }
    var entries by remember { mutableStateOf<List<SmbRepository.Entry>?>(null) }

    val location = remember(host, port, share, subPath) {
        val h = host
        val s = share
        if (h != null && s != null) SmbLocation(h, port, s, subPath) else null
    }

    LaunchedEffect(host, port, share, subPath) {
        names = null
        entries = null
        val h = host ?: return@LaunchedEffect
        val s = share
        if (s == null) {
            names = runCatching { SmbRepository.shares(h, port) }
                .onFailure { with(snackbarHostState) { snackbar(cannotList) } }
                .getOrElse { emptyList() }
        } else {
            entries = runCatching { SmbRepository.list(SmbLocation(h, port, s, subPath)) }
                .onFailure { with(snackbarHostState) { snackbar(cannotList) } }
                .getOrElse { emptyList() }
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(text = stringResource(id = R.string.local_network)) },
                    navigationIcon = { NavigationIcon() },
                    actions = {
                        if (host == null) {
                            IconButton(onClick = { discover() }, enabled = !discovering) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = stringResource(id = R.string.local_network_search),
                                )
                            }
                            IconButton(onClick = { addingHost = true }) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = stringResource(id = R.string.local_network_add_host),
                                )
                            }
                        }
                    },
                )
                Breadcrumb(
                    host = host,
                    share = share,
                    subPath = subPath,
                    onHost = {
                        host = null
                        share = null
                        subPath = ""
                    },
                    onShare = {
                        share = null
                        subPath = ""
                    },
                    onSegment = { subPath = it },
                )
            }
        },
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            when {
                host == null -> HostList(
                    hosts = savedHosts,
                    onOpen = { openHost, openPort ->
                        host = openHost
                        port = openPort
                        share = null
                        subPath = ""
                    },
                    onRemove = { value -> Settings.localNetworkHosts.value = Settings.localNetworkHosts.value - value },
                )
                share == null -> NameList(names = names, onOpen = { name ->
                    share = name
                    subPath = ""
                })
                else -> EntryList(
                    entries = entries,
                    onOpenDirectory = { name -> subPath = subPath.appendSegment(name) },
                    onOpenFile = { name ->
                        val current = location
                        if (current != null && isArchive(name)) {
                            navToReader(current.copy(subPath = current.subPath.appendSegment(name)).uriString)
                        }
                    },
                    onAddToDownloadLocations = { name ->
                        val current = location
                        if (current != null) {
                            savingLocation = current.copy(subPath = current.subPath.appendSegment(name))
                        }
                    },
                )
            }
        }
    }

    if (addingHost) {
        AddHostDialog(
            onDismiss = { addingHost = false },
            onAdd = { value ->
                Settings.localNetworkHosts.value = Settings.localNetworkHosts.value + value
                addingHost = false
            },
        )
    }

    // The dialog the download settings use, so a share added here behaves
    // exactly like one typed in there, including which lists it lands in.
    savingLocation?.let { target ->
        SmbLocationDialog(
            initial = target,
            onDismiss = { savingLocation = null },
            showMessage = { launchSnackbar(it) },
            onSaved = { saved ->
                val uriStr = saved.uriString
                if (uriStr in Settings.downloadLocations.value) {
                    launchSnackbar(alreadyAdded)
                } else {
                    Settings.downloadLocations.value = Settings.downloadLocations.value + uriStr
                    Settings.smbLocations.value = Settings.smbLocations.value + uriStr
                    invalidateDownloadLocationCache()
                    savingLocation = null
                }
            },
        )
    }
}

@Composable
private fun Breadcrumb(
    host: String?,
    share: String?,
    subPath: String,
    onHost: () -> Unit,
    onShare: () -> Unit,
    onSegment: (String) -> Unit,
) {
    if (host == null) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = host,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(onClick = onHost),
        )
        if (share != null) {
            Text(text = " / ")
            Text(
                text = share,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onShare),
            )
        }
        var accumulated = ""
        for (segment in subPath.split('/').filter { it.isNotEmpty() }) {
            accumulated = accumulated.appendSegment(segment)
            val target = accumulated
            Text(text = " / ")
            Text(
                text = segment,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onSegment(target) },
            )
        }
    }
}

/**
 * The address of the Wi-Fi interface, which is where a local network answers
 * from.
 *
 * A guard, not a repair: it was added believing the probe was going out of the
 * cellular interface, and that belief is not supported -- the host count was
 * the same before and after the change, and what actually fixed the missing
 * host was the message id. What this does buy is that the probe carries a
 * Wi-Fi source address, and a WSD responder answers by *unicast* to that
 * address: a source the LAN cannot route to loses the reply silently, and a
 * silent reply is indistinguishable from a host that is not there.
 *
 * Asked for by transport rather than taken from the active network, because
 * the active one is whatever carries default traffic and that is not
 * necessarily the network being browsed. Empty when there is no Wi-Fi, which
 * lets the native side choose.
 */
private fun wifiInterfaceAddress(context: Context): String {
    val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return ""
    val wifi = runCatching {
        connectivity.allNetworks.firstOrNull { network ->
            connectivity.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }.getOrNull() ?: return ""
    val link = runCatching { connectivity.getLinkProperties(wifi) }.getOrNull() ?: return ""
    // IPv4 only: the discovery group is IPv4, and an IPv6 link-local address
    // cannot be joined to it.
    return link.linkAddresses
        .map { it.address }
        .filterIsInstance<java.net.Inet4Address>()
        .firstOrNull()
        ?.hostAddress
        .orEmpty()
}

@Composable
private fun HostList(hosts: Set<String>, onOpen: (String, Int) -> Unit, onRemove: (String) -> Unit) {
    if (hosts.isEmpty()) {
        Empty(text = stringResource(id = R.string.local_network_no_hosts))
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(hosts.sorted()) { value ->
            val saved = parseSavedHost(value)
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clickable { onOpen(saved.host, saved.port) }
                    .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = saved.label, modifier = Modifier.weight(1f))
                TextButton(onClick = { onRemove(value) }) {
                    Text(text = stringResource(id = R.string.settings_download_remove_location))
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun NameList(names: List<String>?, onOpen: (String) -> Unit) {
    if (names == null) {
        Empty(text = "")
        return
    }
    if (names.isEmpty()) {
        Empty(text = stringResource(id = R.string.local_network_empty))
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(names) { name ->
            Text(
                text = "$name/",
                modifier = Modifier.fillMaxWidth()
                    .clickable { onOpen(name) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            )
            HorizontalDivider()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryList(
    entries: List<SmbRepository.Entry>?,
    onOpenDirectory: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onAddToDownloadLocations: (String) -> Unit,
) {
    if (entries == null) {
        Empty(text = "")
        return
    }
    if (entries.isEmpty()) {
        Empty(text = stringResource(id = R.string.local_network_empty))
        return
    }
    var menuFor by remember { mutableStateOf<String?>(null) }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(entries, key = { it.name }) { entry ->
            Box {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .combinedClickable(
                            onClick = {
                                if (entry.isDirectory) onOpenDirectory(entry.name) else onOpenFile(entry.name)
                            },
                            onLongClick = { if (entry.isDirectory) menuFor = entry.name },
                        )
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = if (entry.isDirectory) "${entry.name}/" else entry.name,
                        modifier = Modifier.weight(1f),
                    )
                    if (!entry.isDirectory) {
                        Text(text = humanSize(entry.size), style = MaterialTheme.typography.bodySmall)
                    }
                }
                DropdownMenu(expanded = menuFor == entry.name, onDismissRequest = { menuFor = null }) {
                    DropdownMenuItem(
                        text = { Text(text = stringResource(id = R.string.local_network_add_to_download_locations)) },
                        onClick = {
                            menuFor = null
                            onAddToDownloadLocations(entry.name)
                        },
                    )
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun Empty(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = text)
    }
}

private fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KiB"
    bytes < 1024L * 1024 * 1024 -> "${bytes / (1024 * 1024)} MiB"
    else -> "${bytes / (1024L * 1024 * 1024)} GiB"
}

@Composable
private fun AddHostDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(id = R.string.local_network_add_host)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(text = stringResource(id = R.string.local_network_host)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onAdd(value.trim()) }, enabled = value.isNotBlank()) {
                Text(text = stringResource(id = android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(id = android.R.string.cancel))
            }
        },
    )
}
