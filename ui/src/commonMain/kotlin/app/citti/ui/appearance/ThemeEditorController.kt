package app.citti.ui.appearance

import androidx.compose.ui.graphics.toArgb
import app.citti.core.appearance.AccentSource
import app.citti.core.appearance.Appearance
import app.citti.core.appearance.BaseTheme
import app.citti.core.appearance.ColorToken
import app.citti.core.appearance.Corners
import app.citti.core.appearance.CustomTheme
import app.citti.core.appearance.DecodeResult
import app.citti.core.appearance.Density
import app.citti.core.appearance.FontFace
import app.citti.core.appearance.FontRole
import app.citti.core.appearance.MAX_TEXT_SCALE
import app.citti.core.appearance.MIN_BODY_CONTRAST
import app.citti.core.appearance.MIN_TEXT_SCALE
import app.citti.core.appearance.RoleOverrides
import app.citti.core.appearance.ThemeFileCodec
import app.citti.core.appearance.compositeOver
import app.citti.core.appearance.contrastRatio
import app.citti.ui.settings.AppearanceStore
import app.citti.ui.theme.get
import app.citti.ui.theme.resolveColors
import app.citti.ui.theme.toFamily
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlin.math.roundToInt

/** A bottom sheet over the editor. */
sealed interface EditorSheet {
    data class PickColor(
        val token: ColorToken,
    ) : EditorSheet

    data class PickFont(
        val role: FontRole,
    ) : EditorSheet
}

/** What an import of a Theme file did. */
sealed interface ImportOutcome {
    data object Imported : ImportOutcome

    /** The slot already has overrides: ask, then [ThemeEditorController.confirmImport]. */
    data class NeedsConfirm(
        val theme: CustomTheme,
    ) : ImportOutcome

    data object NotAThemeFile : ImportOutcome
}

data class ThemeEditorState(
    val appearance: Appearance = Appearance(),
    val custom: CustomTheme = CustomTheme(BaseTheme.SIGNAL),
    /** Which variant the Colors rows and the preview show; null follows the current resolved Mode. */
    val editDark: Boolean? = null,
    val sheet: EditorSheet? = null,
    val importError: Boolean = false,
    /** Set by a base change: how many overrides were kept (a snackbar line while it is > 0). */
    val keptOverrides: Int = 0,
    val pendingImport: CustomTheme? = null,
    val confirmResetAll: Boolean = false,
) {
    val overrideCount: Int get() = custom.overrideCount

    fun colors(dark: Boolean): Map<ColorToken, Long> = if (dark) custom.dark else custom.light
}

/** The contrast of `ink-muted` in the [dark] variant of [custom]: the lower of its ratios on `bg` and on `surface`. */
fun inkMutedContrast(
    custom: CustomTheme,
    dark: Boolean,
): Double {
    val c = resolveColors(custom.base.toFamily(), dark, custom, AccentSource.THEME, null)
    // Custom colors may be translucent (#RRGGBBAA): composite over bg (surface) before measuring.
    val bg = c.bg.toArgb().toLong() and 0xFFFFFFFFL
    val surface = compositeOver(c.surface.toArgb().toLong() and 0xFFFFFFFFL, bg)
    val muted = c.inkMuted.toArgb().toLong() and 0xFFFFFFFFL
    return minOf(contrastRatio(compositeOver(muted, bg), bg), contrastRatio(compositeOver(muted, surface), surface))
}

fun isLowContrast(ratio: Double) = ratio < MIN_BODY_CONTRAST

/**
 * State holder of the theme editor. There is no draft: every setter writes through [store] and the state follows
 * the store's flow, so the editor and the app never disagree.
 */
class ThemeEditorController(
    scope: CoroutineScope,
    private val store: AppearanceStore,
) {
    private val local = MutableStateFlow(ThemeEditorState())

    val state: StateFlow<ThemeEditorState> =
        combine(store.appearance(), local) { a, l ->
            l.copy(appearance = a, custom = a.custom ?: CustomTheme(BaseTheme.SIGNAL))
        }.stateIn(scope, SharingStarted.Eagerly, ThemeEditorState())

    /** Called when the editor opens: a fresh state (the Edit variant follows the Mode again). */
    fun reset() {
        local.value = ThemeEditorState()
    }

    private fun edit(
        keepNotices: Boolean = false,
        change: (CustomTheme) -> CustomTheme,
    ) {
        store.updateCustom(BaseTheme.SIGNAL, change)
        local.update { if (keepNotices) it else it.copy(importError = false, keptOverrides = 0) }
    }

    fun setBase(base: BaseTheme) {
        // Read the stored theme inside the update: the state flow lags the write.
        var kept: Int? = null
        edit(keepNotices = true) {
            if (it.base == base) {
                it
            } else {
                kept = it.overrideCount
                it.copy(base = base)
            }
        }
        val keptCount = kept ?: return
        local.update { it.copy(importError = false, keptOverrides = keptCount) }
    }

    fun setEditDark(dark: Boolean) = local.update { it.copy(editDark = dark) }

    fun openColor(token: ColorToken) = local.update { it.copy(sheet = EditorSheet.PickColor(token)) }

    fun openFont(role: FontRole) = local.update { it.copy(sheet = EditorSheet.PickFont(role)) }

    fun closeSheet() = local.update { it.copy(sheet = null) }

    /** Handles the system Back: true when it closed a sheet or a dialog. */
    fun onBack(): Boolean {
        val l = local.value
        return when {
            l.confirmResetAll -> {
                local.value = l.copy(confirmResetAll = false)
                true
            }

            l.pendingImport != null -> {
                local.value = l.copy(pendingImport = null)
                true
            }

            l.sheet != null -> {
                local.value = l.copy(sheet = null)
                true
            }

            else -> {
                false
            }
        }
    }

    fun setColor(
        token: ColorToken,
        dark: Boolean,
        argb: Long,
    ) = edit {
        if (dark) it.copy(dark = it.dark + (token to argb)) else it.copy(light = it.light + (token to argb))
    }

    fun resetColor(
        token: ColorToken,
        dark: Boolean,
    ) = edit { if (dark) it.copy(dark = it.dark - token) else it.copy(light = it.light - token) }

    fun setFont(
        role: FontRole,
        face: FontFace,
    ) = edit { it.withRoles { r -> r.copy(fonts = r.fonts + (role to face)) } }

    fun resetFont(role: FontRole) = edit { it.withRoles { r -> r.copy(fonts = r.fonts - role) } }

    /** Snaps to the 5 % steps of 85 %..130 %; 100 % is stored as an override only when it was asked for. */
    fun setTextScale(scale: Double) {
        edit { it.withRoles { r -> r.copy(textScale = snapTextScale(scale)) } }
    }

    /** Steps from the stored value (not the lagging state), so quick taps add up. */
    fun stepTextScale(delta: Int) = edit { it.withRoles { r -> r.copy(textScale = snapTextScale((r.textScale ?: 1.0) + delta * 0.05)) } }

    private fun snapTextScale(scale: Double) = ((scale * 20).roundToInt() / 20.0).coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE)

    fun resetTextScale() = edit { it.withRoles { r -> r.copy(textScale = null) } }

    fun setDensity(density: Density) = edit { it.withRoles { r -> r.copy(density = density, rowMin = null, gutter = null) } }

    fun resetDensity() = edit { it.withRoles { r -> r.copy(density = null, rowMin = null, gutter = null) } }

    fun setCorners(corners: Corners) =
        edit {
            it.withRoles { r -> r.copy(corners = corners, radiusControl = null, radiusMedia = null, radiusNew = null) }
        }

    fun resetCorners() = edit { it.withRoles { r -> r.copy(corners = null, radiusControl = null, radiusMedia = null, radiusNew = null) } }

    fun setCapitals(on: Boolean) = edit { it.withRoles { r -> r.copy(labelCapitals = on, labelTracking = null) } }

    fun resetCapitals() = edit { it.withRoles { r -> r.copy(labelCapitals = null, labelTracking = null) } }

    fun askResetAll() {
        if (state.value.overrideCount > 0) local.update { it.copy(confirmResetAll = true) }
    }

    fun dismissResetAll() = local.update { it.copy(confirmResetAll = false) }

    /** Clears every override and keeps the base. */
    fun confirmResetAll() {
        edit { CustomTheme(it.base) }
        local.update { it.copy(confirmResetAll = false) }
    }

    /** The Theme file: exactly what the Custom slot holds. */
    fun exportText(): String = ThemeFileCodec.encode(state.value.custom)

    fun importText(text: String): ImportOutcome =
        when (val result = ThemeFileCodec.decode(text)) {
            is DecodeResult.NotAThemeFile -> {
                local.update { it.copy(importError = true) }
                ImportOutcome.NotAThemeFile
            }

            is DecodeResult.Ok -> {
                if (state.value.overrideCount > 0) {
                    local.update { it.copy(pendingImport = result.theme, importError = false) }
                    ImportOutcome.NeedsConfirm(result.theme)
                } else {
                    apply(result.theme)
                    ImportOutcome.Imported
                }
            }
        }

    fun confirmImport() {
        val theme = local.value.pendingImport ?: return
        apply(theme)
    }

    fun dismissImport() = local.update { it.copy(pendingImport = null) }

    fun dismissImportError() = local.update { it.copy(importError = false) }

    private fun apply(theme: CustomTheme) {
        store.setCustomAndSelect(theme)
        local.update { it.copy(pendingImport = null, importError = false, keptOverrides = 0) }
    }

    private fun CustomTheme.withRoles(change: (RoleOverrides) -> RoleOverrides) = copy(roles = change(roles))
}
