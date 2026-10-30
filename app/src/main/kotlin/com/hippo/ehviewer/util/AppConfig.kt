/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hippo.ehviewer.util

import android.os.Build
import android.os.Environment
import com.ehviewer.core.files.exists
import com.ehviewer.core.files.isDirectory
import com.ehviewer.core.files.mkdirs
import com.hippo.ehviewer.BuildConfig
import com.hippo.ehviewer.client.parser.ParserUtils
import java.io.File
import okio.Path
import okio.Path.Companion.toOkioPath
import splitties.init.appCtx

fun File.ensureDirectory() = if (exists()) isDirectory else mkdirs()

fun Path.ensureDirectory() = if (exists()) isDirectory else mkdirs().let { true }

object AppConfig {
    /**
     * The answer [warmDefaultDownloadDir] found, once it has found one.
     *
     * Volatile rather than a `lazy`: a `lazy` makes every other thread wait for
     * whichever one is computing, and the computation can block on a storage
     * provider for seconds -- on the main thread that is an ANR, while reading a
     * null and computing it again is merely the work this used to do every time.
     */
    @Volatile
    private var defaultDownloadDirCache: File? = null

    const val APP_DIRNAME = "EhViewer"
    private const val DOWNLOAD = "download"
    private const val TEMP = "temp"
    private const val PARSE_ERROR = "parse_error"
    private const val CRASH = "crash"
    private const val TAG_TRANSLATIONS = "tag-translations"

    private val abi = Build.SUPPORTED_ABIS[0].takeIf { it in ["arm64-v8a", "x86_64", "armeabi-v7a"] } ?: "universal"

    val isBenchmark = "nonMinified" in BuildConfig.BUILD_TYPE || "benchmark" in BuildConfig.BUILD_TYPE

    fun matchVariant(name: String) = abi in name

    val commitTime = ParserUtils.formatDate(BuildConfig.COMMIT_TIME * 1000)

    private val externalAppDir: File?
        get() {
            if (Environment.MEDIA_MOUNTED == Environment.getExternalStorageState()) {
                return appCtx.getExternalFilesDir(null)?.takeIf { it.ensureDirectory() }
            }
            return null
        }

    private fun getDirInExternalAppDir(filename: String, create: Boolean = true) = externalAppDir?.run { File(this, filename).takeIf { if (create) it.ensureDirectory() else it.isDirectory } }

    /**
     * The directory downloads go to when nothing else is configured.
     *
     * Created on demand: a null here used to leave `downloadLocation` as the
     * empty path, which resolves to `.` and makes every `mkdirs()` fail relative
     * to the process working directory. A fresh install has no such directory
     * yet, so the first download of every install failed.
     *
     * Resolving it asks the system for the external app directory and creates the
     * child, which is disk work -- and it used to be repeated on every read,
     * including reads from composition. [warmDefaultDownloadDir] resolves it once
     * on a worker thread at startup and leaves the answer here; the getter still
     * computes it when the cache is cold, so a caller that arrives first gets the
     * same answer it always did rather than waiting for someone else's work.
     */
    val defaultDownloadDir: File?
        get() = defaultDownloadDirCache ?: getDirInExternalAppDir(DOWNLOAD)

    /**
     * Resolves [defaultDownloadDir] ahead of the first reader.
     *
     * Called from a coroutine of its own at startup: the work belongs off the main
     * thread, and it must not be able to delay anything else -- a wedged external
     * storage provider blocks inside here for as long as it likes.
     *
     * Only a directory that resolved is remembered. A null means "not right now",
     * and the next reader should be free to ask again.
     */
    fun warmDefaultDownloadDir() {
        getDirInExternalAppDir(DOWNLOAD)?.let { defaultDownloadDirCache = it }
    }
    val externalTempPersistDir
        get() = getDirInExternalAppDir(TEMP)?.toOkioPath()
    val externalParseErrorDir: File?
        get() = getDirInExternalAppDir(PARSE_ERROR)
    val externalCrashDir: File?
        get() = getDirInExternalAppDir(CRASH)
    val tagTranslationsDir
        get() = (appCtx.filesDir.toOkioPath() / TAG_TRANSLATIONS).apply { check(ensureDirectory()) }

    // Following locations will be clear on app startup
    val tempDir
        get() = (appCtx.cacheDir.toOkioPath() / TEMP).apply { check(ensureDirectory()) }
    val externalTempDir
        get() = appCtx.externalCacheDir?.toOkioPath()?.let { it / TEMP }?.apply { check(ensureDirectory()) }
}
