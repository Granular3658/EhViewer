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
package com.hippo.ehviewer.client.parser

import arrow.core.Either.Companion.catch
import arrow.core.getOrElse
import arrow.core.partially3
import com.hippo.ehviewer.client.exception.ParseException
import java.nio.ByteBuffer
import kotlinx.serialization.Serializable

object ArchiveParser {
    /**
     * The archive the page says was already paid for.
     *
     * The archiver charges GP the first time an archive is built and serves it for
     * free afterwards, and the only sign of that on the page is this line: "You
     * unlocked a <strong>resample</strong> download of this archive on ...". It is
     * worth reading, because the cost next to the button keeps saying what it
     * always said.
     */
    private val UNLOCKED_REGEX = Regex("""You unlocked a <strong>(\w+)</strong> download of this archive""")

    fun parse(body: ByteBuffer, maybeFunds: Funds?): Result {
        val unlocked = unlockedFrom(body)
        return catch {
            if (maybeFunds != null) {
                val archives = unmarshalParsingAs<List<Archive>>(body, ::parseArchives)
                Result(archives, maybeFunds, unlocked)
            } else {
                unmarshalParsingAs<Result>(body, ::parseArchives.partially3(true)).copy(unlocked = unlocked)
            }
        }.getOrElse {
            throw ParseException("Can't parse archive list", it)
        }
    }

    /** Read on a duplicate: the buffer is still needed for the parse that follows. */
    private fun unlockedFrom(body: ByteBuffer): String? = runCatching {
        val buffer = body.duplicate()
        buffer.position(0)
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        UNLOCKED_REGEX.find(String(bytes, Charsets.UTF_8))?.groupValues?.get(1)
    }.getOrNull()

    fun parseArchiveUrl(body: ByteBuffer) = catch {
        unmarshalParsingAs<String?>(body, ::parseArchiveUrl)
    }.getOrElse {
        throw ParseException("Can't parse archive url", it)
    }

    @Serializable
    data class Result(val archiveList: List<Archive>, val funds: Funds, val unlocked: String? = null)
}

@Serializable
class Archive(
    val res: String,
    val name: String,
    val size: String,
    val cost: String,
    val isHath: Boolean,
)

private external fun parseArchives(body: ByteBuffer, size: Int = body.limit(), parseFunds: Boolean = false): Int
private external fun parseArchiveUrl(body: ByteBuffer, size: Int = body.limit()): Int
