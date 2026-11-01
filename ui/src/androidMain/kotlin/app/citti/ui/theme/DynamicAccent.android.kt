package app.citti.ui.theme

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberDynamicAccent(dark: Boolean): DynamicAccent? {
    val context = LocalContext.current
    // Not keyed on the configuration: a wallpaper change recreates the activity, which recomposes this.
    return remember(context, dark) { dynamicAccentOf(context, dark) }
}

/** The wallpaper accent read from the `system_accent1_*` colors; usable outside a composition (the widgets). */
fun dynamicAccentOf(
    context: Context,
    dark: Boolean,
): DynamicAccent =
    dynamicAccentFrom(dark) { stop ->
        val res =
            when (stop) {
                AccentStop.S0 -> android.R.color.system_accent1_0
                AccentStop.S100 -> android.R.color.system_accent1_100
                AccentStop.S200 -> android.R.color.system_accent1_200
                AccentStop.S600 -> android.R.color.system_accent1_600
                AccentStop.S700 -> android.R.color.system_accent1_700
                AccentStop.S800 -> android.R.color.system_accent1_800
            }
        context.getColor(res).toLong() and 0xFFFFFFFFL
    }
