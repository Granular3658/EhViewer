package com.hippo.ehviewer.client.exception

import androidx.annotation.StringRes
import com.ehviewer.core.i18n.R

sealed class FatalException : EhException {
    constructor(message: String) : super(message)
    constructor(@StringRes messageId: Int) : super(messageId)
}

class IpBannedException(message: String) : FatalException(message)
class InsufficientGpException : FatalException(R.string.insufficient_gp)
class QuotaExceededException : FatalException(R.string.error_509)

/**
 * A download directory that could not be created.
 *
 * A share mounted read-only and a card that is no longer there fail the same way
 * and used to fail quietly: the create reports nothing, and every page after it
 * fails on its own, which looks like a download that runs and never progresses.
 * The path is the message, because which location it was is the whole answer.
 */
class DownloadLocationNotWritable(path: String) : FatalException(path)
