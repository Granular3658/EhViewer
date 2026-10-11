package com.hippo.ehviewer.debug

import android.os.SystemClock
import android.util.Log

/**
 * Diagnostic-only tracing for the SMB restore path. Everything lands on logcat
 * under the tag [TAG], one line per step, with a monotonic timestamp, the
 * thread name and the process' live thread count, so a stall can be read back
 * as "which step never logged its end, and how many threads were alive then".
 *
 * Not for merge: delete together with its call sites once the write-back hang
 * is understood.
 */
object RestoreTrace {
    const val TAG = "RestoreTrace"
    private val start = SystemClock.uptimeMillis()

    fun event(name: String, detail: String = "") {
        Log.d(TAG, "${SystemClock.uptimeMillis() - start}ms|$name|tid=${Thread.currentThread().name}|threads=${Thread.activeCount()}|$detail")
    }
}
