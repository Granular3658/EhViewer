/*
 * Copyright 2023-2024 Tarsin Norbin
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
package com.hippo.ehviewer.gallery

import android.os.Process
import arrow.autoCloseScope
import com.ehviewer.core.files.isSmb
import com.ehviewer.core.files.openFileDescriptor
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.image.ImageSource
import com.hippo.ehviewer.image.byteBufferSource
import com.hippo.ehviewer.jni.dwarfsClose
import com.hippo.ehviewer.jni.dwarfsOpen
import com.hippo.ehviewer.jni.dwarfsOpenSmb
import com.hippo.ehviewer.jni.dwarfsPageCount
import com.hippo.ehviewer.jni.dwarfsPageName
import com.hippo.ehviewer.jni.dwarfsReadPage
import com.hippo.ehviewer.jni.dwarfsReadPageToFd
import com.hippo.ehviewer.jni.dwarfsReleaseByteBuffer
import com.hippo.ehviewer.smb.SmbLocation
import com.hippo.ehviewer.smb.SmbRepository
import com.hippo.ehviewer.util.FileUtils
import com.hippo.ehviewer.util.displayName
import kotlinx.coroutines.coroutineScope
import moe.tarsin.kt.install
import okio.Path

/**
 * Whether a path names a DwarFS image, which is how the reader picks a loader.
 *
 * Also requires a 64-bit process: the reader is built on `xxhash-rust`'s xxh3,
 * which does not compile for 32-bit ARM at all, so on a 32-bit build the file
 * falls through to the archive reader rather than reaching a missing symbol.
 *
 * The name is read only up to a query or fragment, because this is also asked
 * about the string form of a `content://` URI another app handed over, and a
 * provider may append one (`.../1.dwarfs?size=...&time=...`). Without the trim
 * such a file no longer looks like an image and the archive reader is given it
 * instead, which then reports an archive with no content.
 */
fun Path.isDwarfs() = name.substringBefore('?').substringBefore('#')
    .endsWith(".dwarfs", ignoreCase = true) && Process.is64Bit()

/**
 * Opens a DwarFS image as a gallery, the way an archive is opened.
 *
 * The image is read through the descriptor rather than through a mount, so this
 * works on a stock device. Pages come back as direct buffers allocated on the
 * other side and released by [dwarfsReleaseByteBuffer] -- not by the archive
 * pool's `releaseByteBuffer`, whose buffers are all one fixed size.
 */
suspend inline fun <T> useDwarfsPageLoader(
    file: Path,
    info: GalleryInfo? = null,
    startPage: Int = 0,
    hasAds: Boolean = false,
    crossinline block: suspend (PageLoader) -> T,
) = autoCloseScope {
    coroutineScope {
        // An image on a share is read through an SMB handle, not a descriptor:
        // the descriptor for a remote file is a pipe, and a pipe cannot serve
        // the positional reads an image is made of. The handle reads through the
        // same window cache an archive on a share reads through.
        val location = if (file.isSmb) SmbLocation.parse(file) else null
        val handle = if (location != null) {
            val smb = SmbRepository.open(location)
            install(
                { dwarfsOpenSmb(smb.value, smb.size, Settings.dwarfsCacheSize.value) },
                { h, _ ->
                    dwarfsClose(h)
                    SmbRepository.close(smb.value)
                },
            )
        } else {
            val pfd = install(file.openFileDescriptor("r"))
            install(
                { dwarfsOpen(pfd.fd, Settings.dwarfsCacheSize.value) },
                { h, _ -> dwarfsClose(h) },
            )
        }
        val size = dwarfsPageCount(handle)
        check(size > 0) { "DwarFS image have no content!" }
        val loader = install(
            object : PageLoader(this, info, startPage, size, hasAds) {
                override val title by lazy {
                    if (info != null) {
                        EhUtils.getSuitableTitle(info)
                    } else {
                        FileUtils.getNameFromFilename(file.displayName)!!
                    }
                }

                override fun getImageExtension(index: Int) = dwarfsPageName(handle, index)
                    .substringAfterLast('.', "")

                override fun save(index: Int, file: Path) = runCatching {
                    file.openFileDescriptor("w").use {
                        dwarfsReadPageToFd(handle, index, it.fd)
                    }
                }.getOrElse {
                    logcat(it)
                    false
                }

                override fun openSource(index: Int): ImageSource {
                    val buffer = dwarfsReadPage(handle, index)
                    check(buffer.isDirect)
                    return byteBufferSource(buffer) { dwarfsReleaseByteBuffer(buffer) }
                }

                override fun prefetchPages(pages: List<Int>, bounds: IntRange) = Unit

                override fun onRequest(index: Int, force: Boolean, orgImg: Boolean) = notifySourceReady(index)
            },
        )
        block(loader)
    }
}
