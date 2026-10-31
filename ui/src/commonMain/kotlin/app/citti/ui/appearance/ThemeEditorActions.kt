package app.citti.ui.appearance

import app.citti.core.appearance.BaseTheme
import app.citti.core.appearance.ColorToken
import app.citti.core.appearance.Corners
import app.citti.core.appearance.Density
import app.citti.core.appearance.FontFace
import app.citti.core.appearance.FontRole

/** The actions of the theme editor; the app binds them to a [ThemeEditorController]. */
class ThemeEditorActions(
    val onBack: () -> Unit,
    val onBase: (BaseTheme) -> Unit,
    val onEditDark: (Boolean) -> Unit,
    val onOpenColor: (ColorToken) -> Unit,
    val onResetColor: (ColorToken, Boolean) -> Unit,
    val onOpenFont: (FontRole) -> Unit,
    val onResetFont: (FontRole) -> Unit,
    val onTextScaleStep: (Int) -> Unit,
    val onResetTextScale: () -> Unit,
    val onDensity: (Density) -> Unit,
    val onResetDensity: () -> Unit,
    val onCorners: (Corners) -> Unit,
    val onResetCorners: () -> Unit,
    val onCapitals: (Boolean) -> Unit,
    val onResetCapitals: () -> Unit,
    val onAskResetAll: () -> Unit,
    val onConfirmResetAll: () -> Unit,
    val onDismissResetAll: () -> Unit,
    val onDismissImportError: () -> Unit,
    // The sheets.
    val onCloseSheet: () -> Unit,
    val onSetColor: (ColorToken, Boolean, Long) -> Unit,
    val onSetFont: (FontRole, FontFace) -> Unit,
    // Import and export through the system file picker.
    val onImport: () -> Unit,
    val onExport: () -> Unit,
    val onConfirmImport: () -> Unit,
    val onDismissImport: () -> Unit,
)

fun ThemeEditorController.actions(
    onBack: () -> Unit,
    onImport: () -> Unit = {},
    onExport: () -> Unit = {},
): ThemeEditorActions =
    ThemeEditorActions(
        onBack = onBack,
        onBase = ::setBase,
        onEditDark = ::setEditDark,
        onOpenColor = ::openColor,
        onResetColor = ::resetColor,
        onOpenFont = ::openFont,
        onResetFont = ::resetFont,
        onTextScaleStep = ::stepTextScale,
        onResetTextScale = ::resetTextScale,
        onDensity = ::setDensity,
        onResetDensity = ::resetDensity,
        onCorners = ::setCorners,
        onResetCorners = ::resetCorners,
        onCapitals = ::setCapitals,
        onResetCapitals = ::resetCapitals,
        onAskResetAll = ::askResetAll,
        onConfirmResetAll = ::confirmResetAll,
        onDismissResetAll = ::dismissResetAll,
        onDismissImportError = ::dismissImportError,
        onCloseSheet = ::closeSheet,
        onSetColor = { token, dark, argb -> setColor(token, dark, argb) },
        onSetFont = ::setFont,
        onImport = onImport,
        onExport = onExport,
        onConfirmImport = ::confirmImport,
        onDismissImport = ::dismissImport,
    )
