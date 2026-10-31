package app.citti.core.log

/**
 * Sink for sync diagnostics (one line per event). Implementations must be thread-safe and cheap.
 *
 * Lines never carry credentials, URLs with query values, Note titles, categories or content: ids and counts only.
 */
interface SyncLog {
    fun info(msg: String)

    fun warn(msg: String)

    fun error(
        msg: String,
        cause: Throwable? = null,
    )

    /** Drops everything. The default everywhere, so existing call sites and tests are unchanged. */
    data object None : SyncLog {
        override fun info(msg: String) = Unit

        override fun warn(msg: String) = Unit

        override fun error(
            msg: String,
            cause: Throwable?,
        ) = Unit
    }

    companion object {
        const val TAG = "CittiSync"
    }
}
