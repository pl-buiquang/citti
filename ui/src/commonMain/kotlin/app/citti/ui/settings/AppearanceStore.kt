package app.citti.ui.settings

import app.citti.core.appearance.AccentSource
import app.citti.core.appearance.Appearance
import app.citti.core.appearance.AppearanceSettings
import app.citti.core.appearance.BaseTheme
import app.citti.core.appearance.CustomTheme
import app.citti.core.appearance.ModeChoice
import app.citti.core.appearance.ThemeChoice
import kotlinx.coroutines.flow.Flow

/** What Settings › Appearance and the theme editor read and change; [CoreAppearanceStore] adapts core, tests use a fake. */
interface AppearanceStore {
    fun appearance(): Flow<Appearance>

    fun current(): Appearance

    fun setTheme(theme: ThemeChoice)

    fun setMode(mode: ModeChoice)

    fun setAccent(accent: AccentSource)

    /** Replaces the Custom theme slot (null empties it). */
    fun setCustom(custom: CustomTheme?)

    /** Read-modify-write of the Custom theme in one step; an empty slot starts from [seedBase]. */
    fun updateCustom(
        seedBase: BaseTheme,
        change: (CustomTheme) -> CustomTheme,
    )

    /** The Custom theme; an empty slot is seeded with no overrides on [base] and Custom is selected. */
    fun selectCustomOrSeed(base: BaseTheme): CustomTheme
}

class CoreAppearanceStore(
    private val settings: AppearanceSettings,
) : AppearanceStore {
    override fun appearance(): Flow<Appearance> = settings.flow()

    override fun current(): Appearance = settings.current()

    override fun setTheme(theme: ThemeChoice) = settings.setTheme(theme)

    override fun setMode(mode: ModeChoice) = settings.setMode(mode)

    override fun setAccent(accent: AccentSource) = settings.setAccent(accent)

    override fun setCustom(custom: CustomTheme?) = settings.setCustom(custom)

    override fun updateCustom(
        seedBase: BaseTheme,
        change: (CustomTheme) -> CustomTheme,
    ) = settings.updateCustom(seedBase, change)

    override fun selectCustomOrSeed(base: BaseTheme): CustomTheme = settings.selectCustomOrSeed(base)
}
