// citti Design System — generated from tokens.json
// Fonts: add the Google Fonts below to res/font (or use the downloadable-fonts provider) and map them in fontFor().
package app.citti.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Family keys from tokens.json: signal = "Space Grotesk", signal-mono = "Space Mono", paper-serif = Newsreader, paper-sans = "Public Sans", index = "IBM Plex Sans", index-mono = "IBM Plex Mono" */
fun interface FontResolver { fun fontFor(key: String): FontFamily }

class SignalType(private val fonts: FontResolver) {
    val display = TextStyle(fontFamily = fonts.fontFor("signal"), fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight(700), letterSpacing = -0.03.em)
    val search = TextStyle(fontFamily = fonts.fontFor("signal"), fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight(500), letterSpacing = -0.02.em)
    val rowTitle = TextStyle(fontFamily = fonts.fontFor("signal"), fontSize = 18.sp, lineHeight = 23.sp, fontWeight = FontWeight(500), letterSpacing = -0.01.em)
    val body = TextStyle(fontFamily = fonts.fontFor("signal"), fontSize = 17.sp, lineHeight = 27.sp, fontWeight = FontWeight(400))
    val ui = TextStyle(fontFamily = fonts.fontFor("signal"), fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight(500))
    val meta = TextStyle(fontFamily = fonts.fontFor("signal"), fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight(500))
    val label = TextStyle(fontFamily = fonts.fontFor("signal"), fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight(700), letterSpacing = 0.14.em)
    val syntax = TextStyle(fontFamily = fonts.fontFor("signal-mono"), fontSize = 14.sp, lineHeight = 27.sp, fontWeight = FontWeight(400))
}

class PaperType(private val fonts: FontResolver) {
    val display = TextStyle(fontFamily = fonts.fontFor("paper-serif"), fontSize = 36.sp, lineHeight = 40.sp, fontWeight = FontWeight(500), letterSpacing = -0.01.em)
    val rowTitle = TextStyle(fontFamily = fonts.fontFor("paper-serif"), fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight(500))
    val body = TextStyle(fontFamily = fonts.fontFor("paper-serif"), fontSize = 18.sp, lineHeight = 29.sp, fontWeight = FontWeight(400))
    val ui = TextStyle(fontFamily = fonts.fontFor("paper-sans"), fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight(500))
    val meta = TextStyle(fontFamily = fonts.fontFor("paper-sans"), fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight(400))
    val label = TextStyle(fontFamily = fonts.fontFor("paper-sans"), fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight(600))
    val caption = TextStyle(fontFamily = fonts.fontFor("paper-serif"), fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight(400))
}

class IndexType(private val fonts: FontResolver) {
    val title = TextStyle(fontFamily = fonts.fontFor("index"), fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight(600))
    val heading = TextStyle(fontFamily = fonts.fontFor("index"), fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight(600))
    val rowTitle = TextStyle(fontFamily = fonts.fontFor("index"), fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight(500))
    val body = TextStyle(fontFamily = fonts.fontFor("index"), fontSize = 15.sp, lineHeight = 23.sp, fontWeight = FontWeight(400))
    val ui = TextStyle(fontFamily = fonts.fontFor("index"), fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight(500))
    val meta = TextStyle(fontFamily = fonts.fontFor("index-mono"), fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight(400))
    val editor = TextStyle(fontFamily = fonts.fontFor("index-mono"), fontSize = 13.5.sp, lineHeight = 23.sp, fontWeight = FontWeight(400))
}

