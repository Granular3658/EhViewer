package com.hippo.ehviewer.ui.settings

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import arrow.fx.coroutines.parMap
import arrow.fx.coroutines.parMapNotNull
import com.ehviewer.core.database.model.DownloadInfo
import com.ehviewer.core.files.delete
import com.ehviewer.core.files.find
import com.ehviewer.core.files.isDirectory
import com.ehviewer.core.files.isSmb
import com.ehviewer.core.files.list
import com.ehviewer.core.files.metadataOrNull
import com.ehviewer.core.files.mkdirs
import com.ehviewer.core.files.toOkioPath
import com.ehviewer.core.files.toUri
import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.util.isAtLeastQ
import com.ehviewer.core.util.launch
import com.ehviewer.core.util.launchIO
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.asMutableState
import com.hippo.ehviewer.client.EhEngine.fillGalleryListByApi
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.parser.GalleryDetailUrlParser
import com.hippo.ehviewer.client.parser.ParserUtils
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.download.MARKER_FILENAMES
import com.hippo.ehviewer.download.THUMB_FILENAMES
import com.hippo.ehviewer.download.allDownloadLocations
import com.hippo.ehviewer.download.downloadDir
import com.hippo.ehviewer.download.downloadLocation
import com.hippo.ehviewer.download.invalidateDownloadLocationCache
import com.hippo.ehviewer.download.probeNames
import com.hippo.ehviewer.smb.SmbCredentialStore
import com.hippo.ehviewer.smb.SmbLocation
import com.hippo.ehviewer.smb.SmbRepository
import com.hippo.ehviewer.spider.COMIC_INFO_FILE
import com.hippo.ehviewer.spider.ComicInfo
import com.hippo.ehviewer.spider.MIN_SPEED_LEVEL
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.ehviewer.spider.SpiderQueen.Companion.SPIDER_INFO_FILENAME
import com.hippo.ehviewer.spider.openSpiderDen
import com.hippo.ehviewer.spider.readComicInfo
import com.hippo.ehviewer.spider.readComicInfoFromBytes
import com.hippo.ehviewer.spider.readCompatFromBytes
import com.hippo.ehviewer.spider.readCompatFromPath
import com.hippo.ehviewer.spider.speedLevelToSpeed
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.keepNoMediaFileStatus
import com.hippo.ehviewer.ui.main.NavigationIcon
import com.hippo.ehviewer.ui.tools.awaitConfirmationOrCancel
import com.hippo.ehviewer.ui.tools.observed
import com.hippo.ehviewer.util.AppConfig
import com.hippo.ehviewer.util.displayPath
import com.hippo.ehviewer.util.displayString
import com.hippo.ehviewer.util.requestPermission
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import moe.tarsin.coroutines.runSuspendCatching
import moe.tarsin.snackbar
import moe.tarsin.string
import okio.Path
import okio.Path.Companion.toOkioPath
import splitties.init.appCtx

private const val URI_FLAGS = FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION

@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.DownloadScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    fun launchSnackbar(message: String) = launch { snackbar(message) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.settings_download)) },
                navigationIcon = { NavigationIcon() },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        Column(modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection).verticalScroll(rememberScrollState()).padding(paddingValues)) {
            val downloadLocationsState by Settings.downloadLocations.collectAsState()
            val smbLocationsState by Settings.smbLocations.collectAsState()
            val defaultLocationUri by Settings.defaultDownloadLocationUri.collectAsState()
            var showSmbDialog by remember { mutableStateOf(false) }
            // The SMB location whose URL is being edited, if any.
            var editingSmb by remember { mutableStateOf<String?>(null) }
            // The location being re-picked, or null when adding a new one.
            var replacing by remember { mutableStateOf<String?>(null) }
            // Until a location is configured the downloader still uses the legacy
            // single one. Show it as a row of its own -- it is not part of the
            // configured set -- so there is still somewhere to change it from.
            val legacyUriStr = downloadLocation.toUri().toString()
            val legacyOnly = downloadLocationsState.isEmpty()
            val locations = downloadLocationsState.ifEmpty { listOfNotNull(legacyUriStr.takeIf(String::isNotEmpty)) }
            val cannotGetDownloadLocation = stringResource(id = R.string.settings_download_cant_get_download_location)
            val extraLocationsLabel = stringResource(id = R.string.settings_download_extra_locations)
            val addLocationLabel = stringResource(id = R.string.settings_download_add_location)
            val removeLocationLabel = stringResource(id = R.string.settings_download_remove_location)
            val setDefaultLabel = stringResource(id = R.string.settings_download_set_default)
            val alreadyAddedLabel = stringResource(id = R.string.settings_download_location_already_added)
            val cannotRemoveDefaultLabel = stringResource(id = R.string.settings_download_cannot_remove_default)

            suspend fun onLocationPicked(treeUri: Uri, replaceUriStr: String?) {
                contextOf<Context>().contentResolver.runCatching {
                    // Only take the new permission. Releasing the existing ones here would
                    // revoke access to every other configured location: a replaced location
                    // releases its own below, and a removed one when it is removed.
                    takePersistableUriPermission(treeUri, URI_FLAGS)
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
                    val path = docUri.toOkioPath()
                    check(path.isDirectory) { "$path is not a directory" }
                    keepNoMediaFileStatus(path)
                    val current = Settings.downloadLocations.value
                    // Different tree URIs can resolve to the same directory
                    if (current.any { it != replaceUriStr && runCatching { Uri.parse(it).toOkioPath() }.getOrNull() == path }) {
                        launchSnackbar(alreadyAddedLabel)
                        return@runCatching
                    }
                    val uriStr = docUri.toString()
                    val wasDefault = replaceUriStr != null &&
                        (replaceUriStr == defaultLocationUri || replaceUriStr == legacyUriStr)
                    Settings.downloadLocations.value = current - (replaceUriStr ?: "") + uriStr
                    // The first location (or an explicit pick) becomes the default, so the
                    // default never depends on the iteration order of the underlying Set
                    if (wasDefault || Settings.defaultDownloadLocationUri.value == null) {
                        Settings.defaultDownloadLocationUri.value = uriStr
                        downloadLocation = path
                    }
                    if (replaceUriStr != null) {
                        // The location it replaced loses its permission
                        runCatching { releasePersistableUriPermission(Uri.parse(replaceUriStr), URI_FLAGS) }
                    }
                    invalidateDownloadLocationCache()
                }.onFailure {
                    logcat(it)
                    launchSnackbar(cannotGetDownloadLocation)
                }
            }

            val dirLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
                treeUri?.let { launchIO { onLocationPicked(it, replacing) } }
            }

            /** Opens the folder picker, falling back to the app directory without DocumentsUI. */
            suspend fun pickDirectory() {
                try {
                    dirLauncher.launch(null)
                } catch (_: ActivityNotFoundException) {
                    // Best effort for devices without DocumentsUI
                    if (!isAtLeastQ && requestPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                        runCatching {
                            val path = Environment.getExternalStorageDirectory().toOkioPath() / AppConfig.APP_DIRNAME
                            path.mkdirs()
                            check(path.isDirectory) { "$path is not a directory" }
                            keepNoMediaFileStatus(path)
                            val uriStr = path.toUri().toString()
                            Settings.downloadLocations.value = Settings.downloadLocations.value + uriStr
                            Settings.defaultDownloadLocationUri.value = uriStr
                            downloadLocation = path
                            invalidateDownloadLocationCache()
                            return
                        }.onFailure {
                            logcat(it)
                        }
                    }
                    launchSnackbar(cannotGetDownloadLocation)
                }
            }

            val showSource = Settings.showDownloadSource.asMutableState()
            SwitchPreference(
                title = stringResource(id = R.string.settings_download_show_source),
                summary = stringResource(id = R.string.settings_download_show_source_summary),
                state = showSource,
            )

            if (locations.isNotEmpty()) {
                Preference(title = extraLocationsLabel) {}
                locations.forEach { uriStr ->
                    val isSmb = uriStr in smbLocationsState
                    // The legacy location is the default by definition: it is only
                    // shown when nothing is configured, and then it is what the
                    // downloader falls back to.
                    val isLegacy = legacyOnly && uriStr == legacyUriStr
                    // SMB shares are writable now, so they can be picked as the default too
                    val isDefault = isLegacy || uriStr == defaultLocationUri
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (isSmb) uriStr else Uri.parse(uriStr).displayPath ?: "",
                            modifier = Modifier
                                .weight(1f)
                                .then(
                                    Modifier.clickable {
                                        if (isSmb) {
                                            // An SMB location is a URL the user typed, so a tap
                                            // reopens the dialog to fix it.
                                            editingSmb = uriStr
                                        } else {
                                            // A local one is backed by a SAF grant, so it has to
                                            // be picked again to replace it.
                                            replacing = uriStr
                                            launchIO { pickDirectory() }
                                        }
                                    },
                                ),
                        )
                        if (isDefault) {
                            Text(
                                text = stringResource(id = R.string.settings_download_set_default_done),
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        } else {
                            TextButton(
                                onClick = {
                                    launchIO {
                                        Settings.defaultDownloadLocationUri.value = uriStr
                                        invalidateDownloadLocationCache()
                                    }
                                },
                            ) {
                                Text(setDefaultLabel)
                            }
                        }
                        // The last remaining location anchors the whole set and has no
                        // remove button: dropping it would leave no configured location and
                        // silently fall back to the legacy single one, which then disappears
                        // again as soon as a new location is added. Any other location can go,
                        // including the default one, which re-points the default elsewhere.
                        if (locations.size > 1) {
                            TextButton(
                                onClick = {
                                    launchIO {
                                        if (Settings.downloadLocations.value.size <= 1) {
                                            launchSnackbar(cannotRemoveDefaultLabel)
                                        } else {
                                            val remaining = Settings.downloadLocations.value - uriStr
                                            Settings.downloadLocations.value = remaining
                                            if (defaultLocationUri == uriStr) {
                                                Settings.defaultDownloadLocationUri.value = remaining.minOrNull()
                                            }
                                            if (isSmb) {
                                                Settings.smbLocations.value = Settings.smbLocations.value - uriStr
                                                SmbLocation.parse(uriStr)?.let(SmbCredentialStore::remove)
                                            } else {
                                                // Only the removed local location loses its permission
                                                runCatching { contextOf<Context>().contentResolver.releasePersistableUriPermission(Uri.parse(uriStr), URI_FLAGS) }
                                            }
                                            invalidateDownloadLocationCache()
                                        }
                                    }
                                },
                            ) {
                                Text(removeLocationLabel)
                            }
                        }
                    }
                }
            }
            // Keep this outside the block above so a location can always be added back,
            // even after the last one has been removed
            Preference(title = addLocationLabel) {
                replacing = null
                launchIO { pickDirectory() }
            }
            Preference(title = stringResource(R.string.settings_download_add_smb_location)) {
                showSmbDialog = true
            }
            if (showSmbDialog) {
                SmbLocationDialog(
                    onDismiss = { showSmbDialog = false },
                    showMessage = ::launchSnackbar,
                    onSaved = { location ->
                        val uriStr = location.uriString
                        if (uriStr in Settings.downloadLocations.value) {
                            launchSnackbar(string(R.string.settings_download_location_already_added))
                        } else {
                            Settings.downloadLocations.value = Settings.downloadLocations.value + uriStr
                            Settings.smbLocations.value = Settings.smbLocations.value + uriStr
                            invalidateDownloadLocationCache()
                            showSmbDialog = false
                        }
                    },
                )
            }
            editingSmb?.let { oldUriStr ->
                SmbLocationDialog(
                    initial = SmbLocation.parse(oldUriStr),
                    onDismiss = { editingSmb = null },
                    showMessage = ::launchSnackbar,
                    onSaved = { location ->
                        val newUriStr = location.uriString
                        if (newUriStr != oldUriStr && newUriStr in Settings.downloadLocations.value) {
                            launchSnackbar(string(R.string.settings_download_location_already_added))
                        } else {
                            val oldLocation = SmbLocation.parse(oldUriStr)
                            val wasDefault = defaultLocationUri == oldUriStr
                            Settings.downloadLocations.value =
                                Settings.downloadLocations.value - oldUriStr + newUriStr
                            Settings.smbLocations.value = Settings.smbLocations.value - oldUriStr + newUriStr
                            if (wasDefault) Settings.defaultDownloadLocationUri.value = newUriStr
                            // The credential key is host + port + share, so editing only the
                            // sub-path keeps the same entry; a changed key must not leave the
                            // old credentials behind.
                            if (oldLocation != null && oldLocation.credentialKey != location.credentialKey) {
                                SmbCredentialStore.remove(oldLocation)
                            }
                            invalidateDownloadLocationCache()
                            editingSmb = null
                        }
                    },
                )
            }
            val mediaScan = Settings.mediaScan.asMutableState()
            SwitchPreference(
                title = stringResource(id = R.string.settings_download_media_scan),
                summary = if (mediaScan.value) stringResource(id = R.string.settings_download_media_scan_summary_on) else stringResource(id = R.string.settings_download_media_scan_summary_off),
                state = mediaScan,
            )
            val multiThreadDownload = Settings.multiThreadDownload.asMutableState()
            SimpleMenuPreferenceInt(
                title = stringResource(id = R.string.settings_download_concurrency),
                summary = stringResource(id = R.string.settings_download_concurrency_summary, multiThreadDownload.value),
                entry = com.hippo.ehviewer.R.array.multi_thread_download_entries,
                entryValueRes = com.hippo.ehviewer.R.array.multi_thread_download_entry_values,
                state = multiThreadDownload,
            )
            val downloadDelay = Settings.downloadDelay.asMutableState()
            SimpleMenuPreferenceInt(
                title = stringResource(id = R.string.settings_download_download_delay),
                summary = stringResource(id = R.string.settings_download_download_delay_summary, downloadDelay.value),
                entry = com.hippo.ehviewer.R.array.download_delay_entries,
                entryValueRes = com.hippo.ehviewer.R.array.download_delay_entry_values,
                state = downloadDelay,
            )
            IntSliderPreference(
                maxValue = 10,
                minValue = MIN_SPEED_LEVEL,
                title = stringResource(id = R.string.settings_download_timeout_speed),
                pref = Settings.timeoutSpeed,
                display = ::speedLevelToSpeed,
            )
            val preloadImage = Settings.preloadImage.asMutableState()
            SimpleMenuPreferenceInt(
                title = stringResource(id = R.string.settings_download_preload_image),
                summary = stringResource(id = R.string.settings_download_preload_image_summary, preloadImage.value),
                entry = com.hippo.ehviewer.R.array.preload_image_entries,
                entryValueRes = com.hippo.ehviewer.R.array.preload_image_entry_values,
                state = preloadImage,
            )
            SwitchPreference(
                title = stringResource(id = R.string.settings_download_download_origin_image),
                summary = stringResource(id = R.string.settings_download_download_origin_image_summary),
                state = Settings.downloadOriginImage.asMutableState(),
            )
            SimpleMenuPreferenceInt(
                title = stringResource(id = R.string.settings_download_save_as_archive),
                entry = com.hippo.ehviewer.R.array.archive_format_entries,
                entryValueRes = com.hippo.ehviewer.R.array.archive_format_entry_values,
                state = Settings.archiveFormat.asMutableState(),
            )
            SwitchPreference(
                title = stringResource(id = R.string.settings_download_archive_metadata),
                summary = stringResource(id = R.string.settings_download_archive_metadata_summary),
                state = Settings.archiveMetadata.asMutableState(),
            )
            WorkPreference(
                title = stringResource(id = R.string.settings_download_reload_metadata),
                summary = stringResource(id = R.string.settings_download_reload_metadata_summary),
            ) {
                fun DownloadInfo.isStable(): Boolean {
                    val downloadTime = downloadDir?.resolve(COMIC_INFO_FILE)?.metadataOrNull()?.lastModifiedAtMillis ?: return false
                    val postedTime = posted?.let { ParserUtils.parseDate(it) } ?: return false
                    // stable 30 days after posted
                    val stableTime = postedTime + 30L * 24L * 60L * 60L * 1000L
                    return downloadTime > stableTime
                }

                runSuspendCatching {
                    // Both passes reach the share: the filter stats a file in
                    // every gallery's directory, and the rewrite writes one. They
                    // run one after the other, so each needs its own bound -- and
                    // unbounded, a library of a hundred items puts a hundred SMB
                    // calls in flight, each parking an IO-pool thread in the
                    // native runtime's block_on. That runtime has two workers
                    // behind it, so past the pool's size the work stops rather
                    // than queueing. Same shape as the restore scan below.
                    DownloadManager.downloadInfoList.parMapNotNull(concurrency = 4) {
                        if (it.state == DownloadInfo.STATE_FINISH && !it.isStable()) it else null
                    }.apply {
                        fillGalleryListByApi(this, EhUrl.referer)
                        val toUpdate = parMap(concurrency = 4) { di ->
                            di.galleryInfo.also { openSpiderDen(it, di.dirname!!, di.location).writeComicInfo(false) }
                        }
                        EhDB.updateGalleryInfo(toUpdate)
                        launchSnackbar(string(R.string.settings_download_reload_metadata_successfully, toUpdate.size))
                    }
                }.onFailure {
                    launchSnackbar(string(R.string.settings_download_reload_metadata_failed, it.displayString()))
                }
            }
            val restoreFailed = stringResource(id = R.string.settings_download_restore_failed)
            WorkPreference(
                title = stringResource(id = R.string.settings_download_restore_download_items),
                summary = stringResource(id = R.string.settings_download_restore_download_items_summary),
            ) {
                // The scan below fans out through `parMapNotNull`, so these are touched
                // from several threads: a plain `var` loses updates and the numbers in
                // the snackbar stop adding up.
                val duplicateCount = java.util.concurrent.atomic.AtomicInteger()
                val restoredCount = java.util.concurrent.atomic.AtomicInteger()
                // One gallery can sit in several locations at once. Only the first
                // directory found for a gallery stands for the list entry; every later
                // one is a duplicate of it, and counts as one whether or not the list
                // already had the entry. Counting directories instead reported one
                // restore and no duplicates for three copies of one gallery, and three
                // duplicates once the list had the entry.
                val seenGids = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
                suspend fun getRestoreItem(file: Path, known: Map<String, Boolean>?): RestoreItem? {
                    // An SMB candidate was listed through the client, so it is
                    // already known to be a directory; asking `file.isDirectory`
                    // would send us back through the provider for nothing.
                    val smb = if (file.isSmb) SmbLocation.parse(file) else null
                    if (smb == null && !file.isDirectory) return null
                    // The scan walked one location at a time, so the candidate's
                    // parent is the location it came from.
                    val location = file.parent?.toString() ?: return null

                    /**
                     * The first of the marker files this directory holds, read from
                     * the share.
                     *
                     * `ComicInfo.xml` comes first: it carries the name, which the
                     * marker never does, and the same id, token and page count. A
                     * file that is there but does not parse falls through to the
                     * next one -- preferring the XML is not a promise that it is
                     * readable.
                     *
                     * The provider's synchronous query/open bridge blocks the
                     * calling thread while it waits for a *second* thread of the
                     * same bounded pool, so scanning enough directories at once
                     * deadlocks it; these suspend calls do their own work on the IO
                     * dispatcher instead.
                     */
                    suspend fun readSmbMarker(location: SmbLocation, known: Map<String, Boolean>): LocalGallery? {
                        for (name in MARKER_FILENAMES) {
                            // Known absent: skip it without paying for an open that
                            // would fail. Not known at all: try it -- the probe
                            // could not answer for this name.
                            if (known[name] == false) continue
                            val bytes = SmbRepository.readAll(location.child(name), MAX_RESTORE_MARKER_BYTES) ?: continue
                            localGalleryOf(name, bytes)?.let { return it.withThumb(known) }
                        }
                        return null
                    }

                    suspend fun readPathMarker(file: Path): LocalGallery? {
                        // A stat on a local path is free next to the read, so this
                        // side probes instead of carrying a map around.
                        val thumbs = THUMB_FILENAMES.filter { file.find(it) != null }
                        for (name in MARKER_FILENAMES) {
                            val path = file.find(name) ?: continue
                            val local = if (name == COMIC_INFO_FILE) {
                                readComicInfo(path)?.toLocalGallery()
                            } else {
                                readCompatFromPath(path)?.let { LocalGallery(it.gid, it.token, it.pages) }
                            }
                            if (local != null) return local.copy(thumb = thumbs.firstOrNull())
                        }
                        return null
                    }

                    return runSuspendCatching {
                        val local = when {
                            smb != null -> readSmbMarker(smb, known.orEmpty())
                            else -> readPathMarker(file)
                        } ?: return null
                        val gid = local.gid
                        val dirname = file.name
                        if (!seenGids.add(gid)) {
                            duplicateCount.incrementAndGet()
                            return null
                        }
                        if (DownloadManager.containDownloadInfo(gid)) {
                            // Already in the list, so there is nothing to add: record
                            // where its directory is (this scan is what knows).
                            DownloadManager.recordDownloadDir(gid, dirname, location)
                            return null
                        }
                        RestoreItem(dirname, local, location)
                    }.onFailure {
                        logcat(it)
                    }.getOrNull()
                }
                runSuspendCatching {
                    // SMB locations are listed through the client directly: one
                    // round trip already tells which entries are directories,
                    // and an unreachable share must not abort the local scan.
                    val candidates = allDownloadLocations.flatMap { location ->
                        val smb = SmbLocation.parse(location)
                        if (smb != null) {
                            runCatching { SmbRepository.list(smb) }
                                .onFailure { logcat(it) }
                                .getOrDefault(emptyList())
                                .filter { it.isDirectory }
                                .map { location / it.name }
                        } else {
                            runCatching { location.list() }
                                .onFailure { logcat(it) }
                                .getOrDefault(emptyList())
                        }
                    }
                    // Which marker and thumbnail names each candidate holds, asked
                    // once per share: a batch of fifty names is one compound
                    // request, so it costs the same two round trips as one name,
                    // while a stat per name is a round trip each. Whatever a share
                    // never answers for is left out of the map, and the reads below
                    // try those names anyway.
                    val presence = probeNames(candidates, PROBE_FILENAMES)
                    // The scan fans out over every candidate, and each one costs
                    // four SMB operations. Unbounded, a library of a hundred
                    // directories puts more than a hundred of them in flight at
                    // once: every one parks an IO-pool thread in the native
                    // runtime's block_on, and that runtime has two worker threads
                    // behind it. Past the pool's size nothing new is ever given a
                    // thread, and the scan stops dead rather than finishing
                    // slowly. A share serialises the operations anyway -- 45
                    // directories measured 5.5 MB and 750 ms -- so concurrency
                    // here buys queueing, not throughput.
                    val result = candidates.parMapNotNull(concurrency = 4) { getRestoreItem(it, presence[it.toString()]) }.also {
                        // Titles, thumbnails, categories and tags come from here,
                        // and so does the page count for a directory that has no
                        // marker. None of it is needed to open the gallery again,
                        // so a network that is down costs metadata, not the
                        // restore: every item keeps what was read off the disk.
                        runCatching { fillGalleryListByApi(it, EhUrl.referer) }.onFailure {
                            logcat(it)
                        }
                    }
                    if (result.isEmpty() && duplicateCount.get() == 0) {
                        launchSnackbar(RESTORE_NOT_FOUND)
                    } else {
                        // The write-back fans out over every restored directory. Each
                        // one writes its ComicInfo.xml through the content provider,
                        // whose synchronous bridge blocks the calling thread while the
                        // work it waits for needs a second thread of the same pool --
                        // unbounded, that empties the pool and the coroutine never
                        // returns. Keep it small: this is a metadata write, not a
                        // throughput path.
                        result.parMap(concurrency = 4) {
                            DownloadManager.recordDownloadDir(it.gid, it.dirname, it.location)
                            if (DownloadManager.restoreDownload(it.galleryInfo, it.dirname, it.location, it.thumb)) {
                                restoredCount.incrementAndGet()
                            }
                            // Only for a gallery the API answered for: the file is
                            // rebuilt out of the info object, and one carrying a
                            // name but no tags would wipe the artists the
                            // directory already recorded. `simpleTags` is set by
                            // the parser for every gallery it returned and by
                            // nothing else, so it is the cheap way to tell.
                            if (it.simpleTags != null) {
                                SpiderDen(it.galleryInfo, it.dirname, it.location).writeComicInfo(false)
                            }
                        }
                        launchSnackbar(RESTORE_COUNT_MSG(restoredCount.get(), duplicateCount.get()))
                    }
                }.onFailure {
                    logcat(it)
                    launchSnackbar("$restoreFailed: ${it.displayString()}")
                }
            }
            WorkPreference(
                title = stringResource(id = R.string.settings_download_clean_redundancy),
                summary = stringResource(id = R.string.settings_download_clean_redundancy_summary),
            ) {
                fun isRedundant(file: Path): Boolean {
                    if (!file.isDirectory) return false
                    val name = file.name
                    val gid = name.substringBefore('-').toLongOrNull() ?: return false
                    return name != DownloadManager.getDownloadInfo(gid)?.dirname
                }
                val list = allDownloadLocations.filterNot { it.isSmb }.flatMap { it.list() }.filter(::isRedundant)
                if (list.isNotEmpty()) {
                    awaitConfirmationOrCancel(
                        confirmText = R.string.delete,
                        title = R.string.settings_download_clean_redundancy,
                    ) {
                        LazyColumn {
                            items(list) {
                                Text(it.name, modifier = Modifier.padding(vertical = 8.dp))
                            }
                        }
                    }
                }
                val cnt = list.count { runCatching { it.delete() }.getOrNull() != null }
                launchSnackbar(FINAL_CLEAR_REDUNDANCY_MSG(cnt))
            }
        }
    }
}

/**
 * What a directory says about its gallery without asking anyone.
 *
 * [thumb] is the name of the thumbnail file found next to the pages, which the
 * restore records so that drawing the card does not have to look for it again.
 */
private data class LocalGallery(
    val gid: Long,
    val token: String,
    val pages: Int,
    val title: String? = null,
    val thumb: String? = null,
)

/** What the presence probe asks about: the markers, and every thumbnail name. */
private val PROBE_FILENAMES = MARKER_FILENAMES + THUMB_FILENAMES

/** One of the marker files, read from bytes the caller already has. */
private fun localGalleryOf(name: String, bytes: ByteArray): LocalGallery? = if (name == COMIC_INFO_FILE) {
    readComicInfoFromBytes(bytes)?.toLocalGallery()
} else {
    readCompatFromBytes(bytes)?.let { LocalGallery(it.gid, it.token, it.pages) }
}

/** Records which thumbnail the directory holds, when the probe knows. */
private fun LocalGallery.withThumb(known: Map<String, Boolean>) = copy(thumb = THUMB_FILENAMES.firstOrNull { known[it] == true })

/** The name a `ComicInfo.xml` gives the gallery; the marker never has one. */
private fun ComicInfo.localTitle() = series?.ifBlank { null }

/** A `ComicInfo.xml` holds the link, the page count and, usually, the name. */
private fun ComicInfo.toLocalGallery() = GalleryDetailUrlParser.parse(web)?.let {
    LocalGallery(it.gid, it.token, pageCount, localTitle())
}

private class RestoreItem(
    val dirname: String,
    local: LocalGallery,
    /** The location the scan found this directory in; see `putDownloadLocation`. */
    val location: String,
    /** The thumbnail file the scan found next to the pages, if it looked. */
    val thumb: String? = local.thumb,
    val galleryInfo: BaseGalleryInfo = BaseGalleryInfo(local.gid, local.token).apply {
        // Filled in before the API is asked, so that a restore that reached no
        // network still has a name to show. The API overwrites both when it
        // answers; the name falls back to the directory's own.
        title = local.title ?: dirname
        pages = local.pages
    },
) : GalleryInfo by galleryInfo
private val RESTORE_NOT_FOUND = appCtx.getString(R.string.settings_download_restore_not_found)
private val RESTORE_COUNT_MSG = { restored: Int, duplicates: Int -> appCtx.getString(R.string.settings_download_restore_successfully, restored, duplicates) }
private val NO_REDUNDANCY = appCtx.getString(R.string.settings_download_clean_redundancy_no_redundancy)
private val CLEAR_REDUNDANCY_DONE = { cnt: Int -> appCtx.getString(R.string.settings_download_clean_redundancy_done, cnt) }
private val FINAL_CLEAR_REDUNDANCY_MSG = { cnt: Int -> if (cnt == 0) NO_REDUNDANCY else CLEAR_REDUNDANCY_DONE(cnt) }

/** Upper bound for the `.ehviewer` / `ComicInfo.xml` markers read while restoring. */
private const val MAX_RESTORE_MARKER_BYTES = 1 shl 20
