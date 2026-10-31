package app.citti.widget

import android.appwidget.AppWidgetManager
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

    /** The Note picker of one widget. The data makes the PendingIntent distinct per widget (extras are ignored for that). */
    fun picker(
        context: Context,
        appWidgetId: Int,
    ): Intent =
        Intent(context, NotePickerActivity::class.java)
            .setData(Uri.parse("citti://picker/$appWidgetId"))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)

    /** Opens the app where it was. */
    fun app(context: Context): Intent = Intent(context, MainActivity::class.java)

    private fun view(
        context: Context,
        uri: String,
    ) = Intent(Intent.ACTION_VIEW, Uri.parse(uri), context, MainActivity::class.java)
}
