package app.citti.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.citti.MainActivity
import app.citti.ui.nav.DeepLink

/** The intents the widgets start. The deep link is the intent data, so every row's PendingIntent is distinct. */
object WidgetIntents {
    fun note(
        context: Context,
        localId: Long,
        line: Int? = null,
    ): Intent = view(context, DeepLink.noteUri(localId, line))

    fun daily(
        context: Context,
        resume: Boolean,
    ): Intent = view(context, DeepLink.dailyUri(resume))

    /** Opens the app where it was. */
    fun app(context: Context): Intent = Intent(context, MainActivity::class.java)

    private fun view(
        context: Context,
        uri: String,
    ) = Intent(Intent.ACTION_VIEW, Uri.parse(uri), context, MainActivity::class.java)
}
