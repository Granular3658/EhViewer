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
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.client.EhEngine.fillGalleryListByApi
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.parser.GalleryDetailUrlParser
import com.hippo.ehviewer.client.parser.ParserUtils
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.download.allDownloadLocations
import com.hippo.ehviewer.download.downloadDir
import com.hippo.ehviewer.download.downloadLocation
import com.hippo.ehviewer.download.invalidateDownloadLocationCache
import com.hippo.ehviewer.spider.COMIC_INFO_FILE
import com.hippo.ehviewer.spider.MIN_SPEED_LEVEL
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.ehviewer.spider.SpiderQueen.Companion.SPIDER_INFO_FILENAME
import com.hippo.ehviewer.spider.readComicInfo
import com.hippo.ehviewer.spider.readCompatFromPath
import com.hippo.ehviewer.spider.speedLevelToSpeed
import com.hippo.ehviewer.smb.SmbCredentialStore
import com.hippo.ehviewer.smb.SmbLocation
import com.hippo.ehviewer.smb.SmbRepository
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
            val cannotGetDownloadLocation = stringResource(id = R.string.settings_download_cant_get_download_location)
            val defaultDownloadDirLabel = stringResource(id = R.string.settings_download_default_location)
            val extraLocationsLabel = stringResource(id = R.string.settings_download_extra_locations)
            val addLocationLabel = stringResource(id = R.string.settings_download_add_location)
            val removeLocationLabel = stringResource(id = R.string.settings_download_remove_location)
            val setDefaultLabel = stringResource(id = R.string.settings_download_set_default)
            val alreadyAddedLabel = stringResource(id = R.string.settings_download_location_already_added)
            val cannotRemoveDefaultLabel = stringResource(id = R.string.settings_download_cannot_remove_default)

            suspend fun onLocationPicked(treeUri: Uri, setAsDefault: Boolean) {
                contextOf<Context>().contentResolver.runCatching {
                    // Only take the new permission. Releasing the existing ones here would
                    // revoke access to every other configured location: permissions are
                    // released explicitly when a location is removed instead.
                    takePersistableUriPermission(treeUri, URI_FLAGS)
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
                    val path = docUri.toOkioPath()
                    check(path.isDirectory) { "$path is not a directory" }
                    keepNoMediaFileStatus(path)
                    val current = Settings.downloadLocations.value
                    // Different tree URIs can resolve to the same directory
                    if (current.any { runCatching { Uri.parse(it).toOkioPath() }.getOrNull() == path }) {
                        launchSnackbar(alreadyAddedLabel)
                        return@runCatching
                    }
                    val uriStr = docUri.toString()
                    Settings.downloadLocations.value = current + uriStr
                    // The first location (or an explicit pick) becomes the default, so the
                    // default never depends on the iteration order of the underlying Set
                    if (setAsDefault || Settings.defaultDownloadLocationUri.value == null) {
                        Settings.defaultDownloadLocationUri.value = uriStr
                        downloadLocation = path
                    }
                    invalidateDownloadLocationCache()
                }.onFailure {
                    logcat(it)
                    launchSnackbar(cannotGetDownloadLocation)
                }
            }

            val defaultDirLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
                treeUri?.let { launchIO { onLocationPicked(it, true) } }
            }
            val extraDirLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
                treeUri?.let { launchIO { onLocationPicked(it, false) } }
            }

            val defaultSummary = defaultLocationUri?.let { Uri.parse(it).displayPath }
                ?: downloadLocation.toUri().displayPath
            Preference(
                title = defaultDownloadDirLabel,
                summary = defaultSummary,
            ) {
                launchIO {
                    val defaultDownloadDir = AppConfig.defaultDownloadDir
                    if (defaultDownloadDir?.delete() == false) {
                        val path = defaultDownloadDir.toOkioPath()
                        awaitConfirmationOrCancel(
                            confirmText = R.string.pick_new_download_location,
                            dismissText = if (downloadLocation != path) {
                                R.string.reset_download_location
                            } else {
                                android.R.string.cancel
                            },
                            title = R.string.waring,
                            onCancelButtonClick = {
                                // Resetting only makes sense for the legacy single location.
                                // With locations configured it would revoke every granted
                                // permission without changing anything effective.
                                if (downloadLocationsState.isEmpty() && downloadLocation != path) {
                                    contextOf<Context>().contentResolver.run {
                                        persistedUriPermissions.forEach {
                                            releasePersistableUriPermission(it.uri, URI_FLAGS)
                                        }
                                    }
                                    downloadLocation = path
                                }
                            },
                        ) {
                            Text(stringResource(id = R.string.default_download_dir_not_empty))
                        }
                    }
                    try {
                        defaultDirLauncher.launch(null)
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
                                return@launchIO
                            }.onFailure {
                                logcat(it)
                            }
                        }
                        launchSnackbar(cannotGetDownloadLocation)
                    }
                }
            }

            val showSource = Settings.showDownloadSource.asMutableState()
            SwitchPreference(
                title = stringResource(id = R.string.settings_download_show_source),
                summary = stringResource(id = R.string.settings_download_show_source_summary),
                state = showSource,
            )

            if (downloadLocationsState.isNotEmpty()) {
                Preference(title = extraLocationsLabel) {}
                downloadLocationsState.forEach { uriStr ->
                    val isSmb = uriStr in smbLocationsState
                    val isDefault = !isSmb && uriStr == defaultLocationUri
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (isSmb) uriStr else Uri.parse(uriStr).displayPath ?: "",
                            modifier = Modifier.weight(1f),
                        )
                        if (isSmb) {
                            Text(
                                text = stringResource(R.string.settings_download_smb_read_only),
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        } else if (isDefault) {
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
                        // The default location anchors the whole set, so it has no remove
                        // button at all: removing it would leave no configured location and
                        // silently fall back to the legacy single one, which then disappears
                        // again as soon as a new location is added.
                        if (!isDefault) {
                            TextButton(
                                onClick = {
                                    launchIO {
                                        if (isDefault || Settings.downloadLocations.value.size <= 1) {
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
                extraDirLauncher.launch(null)
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
                state = Settings.timeoutSpeed.asMutableState(),
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
            SwitchPreference(
                title = stringResource(id = R.string.settings_download_save_as_cbz),
                state = Settings.saveAsCbz.asMutableState(),
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
                    DownloadManager.downloadInfoList.parMapNotNull {
                        if (it.state == DownloadInfo.STATE_FINISH && !it.isStable()) it else null
                    }.apply {
                        fillGalleryListByApi(this, EhUrl.referer)
                        val toUpdate = parMap { di ->
                            di.galleryInfo.also { SpiderDen(it, di.dirname!!).writeComicInfo(false) }
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
                var restoreDirCount = 0
                suspend fun getRestoreItem(file: Path): RestoreItem? {
                    if (!file.isDirectory) return null
                    return runSuspendCatching {
                        val (gid, token) = file.find(SPIDER_INFO_FILENAME)?.let {
                            readCompatFromPath(it)?.run {
                                GalleryDetailUrlParser.Result(gid, token)
                            }
                        } ?: file.find(COMIC_INFO_FILE)?.let {
                            readComicInfo(it)?.run {
                                GalleryDetailUrlParser.parse(web)
                            }
                        } ?: return null
                        val dirname = file.name
                        if (DownloadManager.containDownloadInfo(gid)) {
                            // Restore download dir to avoid redownload
                            val dbdirname = EhDB.getDownloadDirname(gid)
                            if (null == dbdirname || dirname != dbdirname) {
                                EhDB.putDownloadDirname(gid, dirname)
                                restoreDirCount++
                            }
                            return null
                        }
                        RestoreItem(dirname, gid, token)
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
                    val result = candidates.parMapNotNull { getRestoreItem(it) }.also {
                        fillGalleryListByApi(it, EhUrl.referer)
                    }
                    if (result.isEmpty()) {
                        launchSnackbar(RESTORE_COUNT_MSG(restoreDirCount))
                    } else {
                        val count = result.parMap {
                            if (it.pages != 0) {
                                EhDB.putDownloadDirname(it.gid, it.dirname)
                                DownloadManager.restoreDownload(it.galleryInfo, it.dirname)
                                SpiderDen(it.galleryInfo, it.dirname).writeComicInfo(false)
                            }
                        }.size
                        launchSnackbar(RESTORE_COUNT_MSG(count + restoreDirCount))
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

private class RestoreItem(
    val dirname: String,
    gid: Long,
    token: String,
    val galleryInfo: BaseGalleryInfo = BaseGalleryInfo(gid, token),
) : GalleryInfo by galleryInfo
private val RESTORE_NOT_FOUND = appCtx.getString(R.string.settings_download_restore_not_found)
private val RESTORE_COUNT_MSG = { cnt: Int -> if (cnt == 0) RESTORE_NOT_FOUND else appCtx.getString(R.string.settings_download_restore_successfully, cnt) }
private val NO_REDUNDANCY = appCtx.getString(R.string.settings_download_clean_redundancy_no_redundancy)
private val CLEAR_REDUNDANCY_DONE = { cnt: Int -> appCtx.getString(R.string.settings_download_clean_redundancy_done, cnt) }
private val FINAL_CLEAR_REDUNDANCY_MSG = { cnt: Int -> if (cnt == 0) NO_REDUNDANCY else CLEAR_REDUNDANCY_DONE(cnt) }
