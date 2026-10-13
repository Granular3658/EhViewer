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

import android.util.Log
import arrow.autoCloseScope
import com.hippo.ehviewer.image.ImageSource
import com.hippo.ehviewer.image.PathSource
import kotlinx.coroutines.coroutineScope
import moe.tarsin.kt.install
import okio.Path

/**
 * Where each gallery of images was left off, by the directory it came from.
 *
 * The listing and the gallery are the same pictures in the same order, so the
 * page a reader was left on is a position in the listing. Keeping it here is
 * what lets the listing go back to it. It is deliberately in memory: it says
 * where the eye was, which is not something to remember across a restart.
 */
val lastPageByDirectory = mutableMapOf<String, Int>()

/**
 * Opens a list of images as a gallery, the way an archive is opened.
 *
 * The pages are the files given, in the order given, and the caller does the
 * listing: a browser already has the entries of the directory on screen, and
 * asking the share for them again would be a second round trip for a list that
 * is already in hand. The order is therefore the order they are shown in, so
 * the page a tap opens is the page that was tapped, and the page the reader is
 * left on is a position the listing can return to -- which is what [directory]
 * is for.
 *
 * A page is read through [PathSource], which is the same route a downloaded
 * gallery's images are read by, so a share is read through the filesystem that
 * understands it rather than through anything this file has to know about.
 *
 * Saving a page is not supported: it would mean copying a file back out of a
 * share through a reader that has no reason to write one, so the save action
 * reports the failure rather than pretending.
 */
suspend inline fun <T> useImagesPageLoader(
    paths: List<Path>,
    directory: String,
    name: String,
    startPage: Int = 0,
    crossinline block: suspend (PageLoader) -> T,
) = autoCloseScope {
    coroutineScope {
        require(paths.isNotEmpty()) { "A gallery of images needs at least one image" }
        val loader = install(
            object : PageLoader(this, null, startPage, paths.size) {
                override val title by lazy { name }

                override val positionKey = directory

                override fun close() {
                    lastPageByDirectory[directory] = startPage
                    Log.d("ehviewer-images", "close $directory at page $startPage")
                    super.close()
                }

                override fun getImageExtension(index: Int) = paths[index].name.substringAfterLast('.', "")

                override fun save(index: Int, file: Path) = false

                override fun openSource(index: Int): ImageSource {
                    val path = paths[index]
                    return object : PathSource {
                        override val source = path
                        override val type by lazy { path.name.substringAfterLast('.', "") }

                        // A path holds nothing to release: the file is opened
                        // when it is read, by the filesystem that understands
                        // the scheme it names.
                        override fun close() = Unit
                    }
                }

                override fun prefetchPages(pages: List<Int>, bounds: IntRange) = Unit

                override fun onRequest(index: Int, force: Boolean, orgImg: Boolean) = notifySourceReady(index)
            },
        )
        block(loader)
    }
}
