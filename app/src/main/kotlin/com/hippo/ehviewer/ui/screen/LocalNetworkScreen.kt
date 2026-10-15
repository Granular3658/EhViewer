package com.hippo.ehviewer.ui.screen

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
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
import com.hippo.ehviewer.gallery.lastPageByDirectory
import com.hippo.ehviewer.smb.SmbAuthRequired
import com.hippo.ehviewer.smb.SmbCredentialStore
import com.hippo.ehviewer.smb.SmbLocation
import com.hippo.ehviewer.smb.SmbRepository
import com.hippo.ehviewer.ui.LocalSnackBarHostState
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.main.NavigationIcon
import com.hippo.ehviewer.ui.navToReader
import com.hippo.ehviewer.ui.settings.SmbCredentialsDialog
import com.hippo.ehviewer.ui.settings.SmbLocationDialog
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.tarsin.snackbar

private const val DEFAULT_SMB_PORT = 445

/** Extensions [navToReader] knows how to open as a gallery. */
private val ARCHIVE_SUFFIXES = listOf(".zip", ".cbz", ".dwarfs")

/**
 * Extensions the reader decodes: the list the share provider maps to image
 * types, so it is the same set of files the rest of the app calls images.
 *
 * A directory holding any of these opens as a gallery of the directory itself
 * -- not of what is under it, which would be a walk of the whole share.
 */
private val IMAGE_SUFFIXES = listOf(
    ".webp", ".gif", ".jpg", ".jpeg", ".png", ".avif", ".bmp",
    ".heic", ".heif", ".jxl", ".mng", ".apng", ".tif", ".tiff", ".wbmp",
)

/** Separates a host row from the name discovery gave it. */
private val TAB_CHAR = 0x09.toChar()

private fun isArchive(name: String) = ARCHIVE_SUFFIXES.any { name.endsWith(it, ignoreCase = true) }

private fun isImage(name: String) = IMAGE_SUFFIXES.any { name.endsWith(it, ignoreCase = true) }

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

/** A host as the list shows it: kept by hand, or found by the last scan. */
private data class HostRow(val value: String, val discovered: Boolean)

/**
 * A typed host, in the form the list stores: `host[:port]`.
 *
 * The scheme is accepted and dropped, because it is how the same address is
 * written everywhere else in the app, and a share or path after the host is
 * dropped too, so a pasted URI works as well as a bare address.
 */
private fun normalizeHostInput(value: String): String = value
    .trim()
    .removePrefix("smb://")
    .removePrefix("smb:")
    .trimStart('/')
    .substringBefore('/')
    .trim()

/**
 * The rows to show: the typed hosts first, then whatever the last scan found
 * that is not already among them.
 *
 * Matched by address rather than by the stored string, because a typed host
 * usually carries no name and a found one does, so the two forms of the same
 * host would otherwise both be listed.
 */
private fun hostRows(saved: Set<String>, discovered: List<String>): List<HostRow> {
    val known = saved.mapTo(HashSet()) { parseSavedHost(it).address }
    return saved.sorted().map { HostRow(it, discovered = false) } +
        discovered.filterNot { parseSavedHost(it).address in known }.map { HostRow(it, discovered = true) }
}

/**
 * A browser for the SMB shares on the local network.
 *
 * Three levels, each a list: the hosts that have been entered, the shares a
 * host offers, and the directories inside a share. The breadcrumb is that
 * stack read out loud, so tapping a segment returns to that level.
 *
 * Nothing here is a network filesystem client of its own: it is a view over the
 * same SMB support the download locations use, and reading a share needs
 * credentials that are asked for only after a listing fails. The host is
 * usually already configured for downloads, so asking up front would ask for
 * something the app often already has.
 *
 * A scan shows what answered for as long as the screen is open, and nothing
 * more: a host is kept only when it is asked for, and then it is a typed host.
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
    // What the last scan found, kept for the visit rather than written to the
    // setting: a scan describes the network it ran on, and keeping every scan's
    // result would accumulate hosts from every network ever visited. A found
    // row is remembered only when it is asked for, and then it is a typed host.
    var discovered by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    // Back goes up one level, the way it does in any file browser, and only
    // leaves the screen from the top of the list. Closing the whole screen from
    // several directories down throws away the path that was just walked.
    BackHandler(enabled = host != null) {
        when {
            subPath.isNotEmpty() -> subPath = subPath.substringBeforeLast('/', "")
            share != null -> share = null
            else -> host = null
        }
    }
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

    /** Ask the network, and show what answers until the next scan. */
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
                SmbRepository.discoverHosts(wifiInterfaceAddress(context)) { soFar ->
                    // Replaced, not appended: the previous scan described a
                    // network that may not be the one this device is on any
                    // more. The list fills in as answers arrive, so the wait is
                    // a wait and not a freeze.
                    discovered = ArrayList(soFar.map(::discoveredHostRow))
                    // The wait is over as soon as there is something to look
                    // at; the names keep arriving after that.
                    if (soFar.isNotEmpty()) discovering = false
                }
            }.onFailure { launchSnackbar(cannotList) }.getOrElse { emptyList() }
            discovered = ArrayList(found.map(::discoveredHostRow))
            runCatching { lock?.takeIf { it.isHeld }?.release() }
            discovering = false
        }
    }

    // The listing for the level on screen, or null while it is being fetched.
    var names by remember { mutableStateOf<List<String>?>(null) }
    var entries by remember { mutableStateOf<List<SmbRepository.Entry>?>(null) }
    // Whether the level on screen failed to list, as opposed to listing nothing.
    // The two used to be the same thing on screen, and a directory that could
    // not be read looked exactly like a directory with nothing in it.
    var listingFailed by remember { mutableStateOf(false) }

    // The level the server refused, if it did, and a counter that retries the
    // listing once credentials have been given. Browsing asks about a server
    // before it knows a share name, so the refusal can arrive at either level
    // and the dialog is shown for whichever one was asked for.
    var refused by remember { mutableStateOf<SmbLocation?>(null) }
    var attempts by remember { mutableStateOf(0) }

    val location = remember(host, port, share, subPath) {
        val h = host
        val s = share
        if (h != null && s != null) SmbLocation(h, port, s, subPath) else null
    }

    LaunchedEffect(host, port, share, subPath, attempts) {
        names = null
        entries = null
        listingFailed = false
        val h = host ?: return@LaunchedEffect
        val s = share
        // A refusal is about credentials, and is answered by asking for them;
        // anything else is answered by saying the listing did not happen -- said
        // where the listing would have been, not in a snackbar that is gone
        // before the person looks up. Turning a failure into an empty list, as
        // this used to, reads as "this directory is empty", which is the one
        // thing a directory that could not be read is not.
        // A cancellation is this effect being replaced, not the listing failing.
        // The native call cannot be interrupted, so it still returns after the
        // person has moved on -- and what it returns, a listing or the error it
        // failed with, describes the host that was left rather than the one on
        // screen now. A replaced effect therefore writes nothing: otherwise the
        // last host's failure lands on this screen, over a list that did arrive,
        // and reads as this host failing when it never did.
        if (s == null) {
            val outcome = runCatching { SmbRepository.shares(h, port) }
            if (!currentCoroutineContext().isActive) return@LaunchedEffect
            outcome.onFailure { error ->
                if (error is CancellationException) throw error
                if (error is SmbAuthRequired) {
                    refused = SmbLocation(h, port, "", "")
                } else {
                    Log.w("smb", "shares of $h:$port did not list", error)
                    listingFailed = true
                }
            }
            names = outcome.getOrNull()
        } else {
            val target = SmbLocation(h, port, s, subPath)
            val outcome = runCatching { SmbRepository.list(target) }
            if (!currentCoroutineContext().isActive) return@LaunchedEffect
            outcome.onFailure { error ->
                if (error is CancellationException) throw error
                if (error is SmbAuthRequired) {
                    refused = target
                } else {
                    Log.w("smb", "listing $target did not list", error)
                    listingFailed = true
                }
            }
            entries = outcome.getOrNull()
        }
    }

    // The listing and the gallery are the same pictures in the same order, so
    // coming back from a gallery lands on the picture that was on screen. The
    // entries arrive asynchronously, which is why this waits for them rather
    // than running once when the screen comes back.
    val entryListState = rememberLazyListState()
    LaunchedEffect(entries) {
        val list = entries ?: return@LaunchedEffect
        val page = location?.let { lastPageByDirectory[it.uriString] } ?: return@LaunchedEffect
        val name = list.filter { !it.isDirectory && isImage(it.name) }.getOrNull(page)?.name
            ?: return@LaunchedEffect
        val index = list.indexOfFirst { it.name == name }
        if (index >= 0) entryListState.scrollToItem(index)
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
                    root = stringResource(R.string.local_network),
                    host = host,
                    share = share,
                    subPath = subPath,
                    onRoot = {
                        host = null
                        share = null
                        subPath = ""
                    },
                    // Each segment goes to the level it names, the way a path
                    // segment already does. The host's level is its shares and
                    // the share's is its root -- not "one level up from here",
                    // which is a different place depending on how deep the path
                    // is, and reads as the tap having gone wrong.
                    onHost = {
                        share = null
                        subPath = ""
                    },
                    onShare = { subPath = "" },
                    onSegment = { subPath = it },
                )
            }
        },
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            when {
                host == null -> HostList(
                    rows = hostRows(savedHosts, discovered),
                    onOpen = { openHost, openPort ->
                        host = openHost
                        port = openPort
                        share = null
                        subPath = ""
                    },
                    onRemember = { value ->
                        Settings.localNetworkHosts.value = Settings.localNetworkHosts.value + value
                        discovered = ArrayList(discovered - value)
                    },
                    onRemove = { value ->
                        Settings.localNetworkHosts.value = Settings.localNetworkHosts.value - value
                    },
                )
                listingFailed -> Empty(text = cannotList)
                share == null -> NameList(
                    names = names,
                    onOpen = { name ->
                        share = name
                        subPath = ""
                    },
                    // The share itself: a location that keeps the whole share is
                    // the share's root, which is the one directory the listing
                    // under it can never offer.
                    onAddToDownloadLocations = { name ->
                        host?.let { savingLocation = SmbLocation(it, port, name, "") }
                    },
                )
                else -> EntryList(
                    entries = entries,
                    listState = entryListState,
                    onOpenDirectory = { name -> subPath = subPath.appendSegment(name) },
                    onOpenFile = { name ->
                        val current = location
                        if (current != null) {
                            if (isArchive(name)) {
                                navToReader(current.copy(subPath = current.subPath.appendSegment(name)).uriString)
                            } else if (isImage(name)) {
                                // The images of this directory, in the order they
                                // are listed, so the tapped one is the page that
                                // opens. Subdirectories are not walked: the
                                // gallery is the directory, not the share.
                                val images = entries.orEmpty()
                                    .filter { !it.isDirectory && isImage(it.name) }
                                navToReader(
                                    current.uriString,
                                    images.map {
                                        current.copy(subPath = current.subPath.appendSegment(it.name)).uriString
                                    },
                                    images.indexOfFirst { it.name == name }.coerceAtLeast(0),
                                )
                            }
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
                val host = normalizeHostInput(value)
                if (host.isNotEmpty()) {
                    Settings.localNetworkHosts.value = Settings.localNetworkHosts.value + host
                }
                addingHost = false
            },
        )
    }

    // Credentials, asked for at the level that was refused. A dialog of its own
    // -- a name, a password, and a test -- rather than the one that adds a
    // location: that one asks for a path, and a path is not part of the question
    // the server asked.
    refused?.let { target ->
        SmbCredentialsDialog(
            host = target.host,
            port = target.port,
            // Null when the refusal came before a share was named: the test then
            // asks for the share list, which is a question that needs no path.
            location = target.takeIf { it.share.isNotEmpty() },
            // Prefilled with what was tried, so a wrong password is a correction
            // rather than a retyping.
            initial = SmbCredentialStore.find(target.host, target.port),
            onDismiss = { refused = null },
            onSaved = { credentials ->
                SmbCredentialStore.put(target, credentials)
                // The refused session is cached under the credentials it was
                // built with, so it has to go before the listing is retried.
                SmbRepository.invalidateSessions()
                refused = null
                attempts++
            },
        )
    }

    // The dialog the download settings use, so a share added here behaves
    // exactly like one typed in there, including which lists it lands in.
    savingLocation?.let { target ->
        SmbLocationDialog(
            initial = target,
            // The directory was reached by listing it, so it answers: the test
            // button would only re-ask a question this screen already knows.
            knownReachable = true,
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
    root: String,
    host: String?,
    share: String?,
    subPath: String,
    onRoot: () -> Unit,
    onHost: () -> Unit,
    onShare: () -> Unit,
    onSegment: (String) -> Unit,
) {
    if (host == null) return
    // One line, scrolled sideways, and kept at the end: a deep path used to
    // wrap and push the listing off the screen, and the part worth seeing is
    // always the part nearest the current directory.
    val scroll = rememberScrollState()
    LaunchedEffect(host, share, subPath) { scroll.scrollTo(scroll.maxValue) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The way back out of the browser. The host segment below goes to that
        // host's shares, so without this there is nothing above a host and the
        // list of hosts can only be reached with the back gesture.
        Text(
            text = root,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.clickable(onClick = onRoot),
        )
        Text(text = " / ", maxLines = 1, softWrap = false)
        Text(
            text = host,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.clickable(onClick = onHost),
        )
        if (share != null) {
            Text(text = " / ", maxLines = 1, softWrap = false)
            Text(
                text = share,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.clickable(onClick = onShare),
            )
        }
        var accumulated = ""
        for (segment in subPath.split('/').filter { it.isNotEmpty() }) {
            accumulated = accumulated.appendSegment(segment)
            val target = accumulated
            Text(text = " / ", maxLines = 1, softWrap = false)
            Text(
                text = segment,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                softWrap = false,
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
private fun HostList(
    rows: List<HostRow>,
    onOpen: (String, Int) -> Unit,
    onRemember: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    if (rows.isEmpty()) {
        Empty(text = stringResource(id = R.string.local_network_no_hosts))
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(rows, key = { it.value }) { row ->
            val saved = parseSavedHost(row.value)
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clickable { onOpen(saved.host, saved.port) }
                    .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = saved.label,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                // A found host is not kept unless asked for, and a kept one is
                // removed the same way a typed one is: the two lists differ in
                // how long they last, not in what the button does.
                if (row.discovered) {
                    TextButton(onClick = { onRemember(row.value) }) {
                        Text(text = stringResource(id = R.string.local_network_remember_host))
                    }
                } else {
                    TextButton(onClick = { onRemove(row.value) }) {
                        Text(text = stringResource(id = R.string.settings_download_remove_location))
                    }
                }
            }
            HorizontalDivider()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NameList(
    names: List<String>?,
    onOpen: (String) -> Unit,
    onAddToDownloadLocations: (String) -> Unit,
) {
    if (names == null) {
        Empty(text = "")
        return
    }
    if (names.isEmpty()) {
        Empty(text = stringResource(id = R.string.local_network_empty))
        return
    }
    var menuFor by remember { mutableStateOf<String?>(null) }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(names) { name ->
            Box {
                Text(
                    text = "$name/",
                    modifier = Modifier.fillMaxWidth()
                        .combinedClickable(
                            onClick = { onOpen(name) },
                            // A share is a location in its own right, not only a
                            // place that has directories under it: pointing a
                            // download location at the share is what keeps the
                            // whole of it, and until this there was no way to
                            // ask for that from here.
                            onLongClick = { menuFor = name },
                        )
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                )
                DropdownMenu(expanded = menuFor == name, onDismissRequest = { menuFor = null }) {
                    DropdownMenuItem(
                        text = { Text(text = stringResource(id = R.string.local_network_add_to_download_locations)) },
                        onClick = {
                            menuFor = null
                            onAddToDownloadLocations(name)
                        },
                    )
                }
            }
            HorizontalDivider()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryList(
    entries: List<SmbRepository.Entry>?,
    listState: LazyListState,
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
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
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
    // The scheme and any path are dropped, so what is typed is accepted in
    // either form; the hint says so, because it was accepted without saying.
    val host = normalizeHostInput(value)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(id = R.string.local_network_add_host)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(text = stringResource(id = R.string.local_network_host)) },
                supportingText = { Text(text = stringResource(id = R.string.local_network_host_hint)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onAdd(host) }, enabled = host.isNotEmpty()) {
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
