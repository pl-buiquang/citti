package app.citti

import android.util.Log
import app.citti.core.log.SyncLog

/** Sync diagnostics in logcat: `adb logcat -s CittiSync`. */
object AndroidSyncLog : SyncLog {
    override fun info(msg: String) {
        Log.i(SyncLog.TAG, msg)
    }

    override fun warn(msg: String) {
        Log.w(SyncLog.TAG, msg)
    }

    override fun error(
        msg: String,
        cause: Throwable?,
    ) {
        // Only the exception type: its message can name the server host or a path.
        Log.e(SyncLog.TAG, if (cause == null) msg else "$msg cause=${cause::class.simpleName}")
    }
}
