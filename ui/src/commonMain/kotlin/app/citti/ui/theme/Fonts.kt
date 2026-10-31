package app.citti.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import app.citti.ui.resources.Res
import app.citti.ui.resources.ibm_plex_mono_medium
import app.citti.ui.resources.ibm_plex_mono_regular
import app.citti.ui.resources.ibm_plex_mono_semibold
import app.citti.ui.resources.ibm_plex_sans
import app.citti.ui.resources.newsreader
import app.citti.ui.resources.public_sans
import app.citti.ui.resources.space_grotesk
import app.citti.ui.resources.space_mono_bold
import app.citti.ui.resources.space_mono_regular
import org.jetbrains.compose.resources.FontResource
import org.jetbrains.compose.resources.Font as resourceFont

private val variableWeights = listOf(400, 500, 600, 700)

@Composable
private fun variableFamily(resource: FontResource): FontFamily {
    val fonts =
        variableWeights.map { weight ->
            resourceFont(
                resource = resource,
                weight = FontWeight(weight),
                style = FontStyle.Normal,
                variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
            )
        }
    return remember(fonts) { FontFamily(fonts) }
}

/** Resolves the family keys of `tokens.json` to the bundled faces. */
@Composable
fun rememberFontResolver(): FontResolver {
    val signal = variableFamily(Res.font.space_grotesk)
    val signalMono =
        FontFamily(
            resourceFont(Res.font.space_mono_regular, FontWeight.Normal),
            resourceFont(Res.font.space_mono_bold, FontWeight.Bold),
        )
    val paperSerif = variableFamily(Res.font.newsreader)
    val paperSans = variableFamily(Res.font.public_sans)
    val index = variableFamily(Res.font.ibm_plex_sans)
    val indexMono =
        FontFamily(
            resourceFont(Res.font.ibm_plex_mono_regular, FontWeight.Normal),
            resourceFont(Res.font.ibm_plex_mono_medium, FontWeight.Medium),
            resourceFont(Res.font.ibm_plex_mono_semibold, FontWeight.SemiBold),
        )
    return remember(signal, signalMono, paperSerif, paperSans, index, indexMono) {
        FontResolver { key ->
            when (key) {
                "signal" -> signal
                "signal-mono" -> signalMono
                "paper-serif" -> paperSerif
                "paper-sans" -> paperSans
                "index" -> index
                "index-mono" -> indexMono
                else -> FontFamily.Default
            }
        }
    }
}
