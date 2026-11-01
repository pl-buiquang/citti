package app.citti.ui.appearance

import app.citti.core.appearance.Appearance
import app.citti.core.appearance.BaseTheme
import app.citti.core.appearance.ColorToken
import app.citti.core.appearance.Corners
import app.citti.core.appearance.CustomTheme
import app.citti.core.appearance.Density
import app.citti.core.appearance.FontFace
import app.citti.core.appearance.FontRole
import app.citti.core.appearance.ThemeChoice
import app.citti.ui.settings.FakeAppearanceStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ThemeEditorControllerTest {
    private val store = FakeAppearanceStore(Appearance(ThemeChoice.CUSTOM, custom = CustomTheme(BaseTheme.PAPER)))

    private fun TestScope.editor() = ThemeEditorController(backgroundScope, store)

    @Test
    fun settersWriteThroughTheStoreAndTheStateFollows() =
        runTest(UnconfinedTestDispatcher()) {
            val e = editor()
            e.setColor(ColorToken.BG, dark = false, argb = 0xFF101010)
            e.setColor(ColorToken.ACCENT, dark = true, argb = 0xFFFF0000)
            e.setFont(FontRole.UI, FontFace.SYSTEM)
            e.setTextScale(1.1)
            e.setDensity(Density.COMPACT)
            e.setCorners(Corners.SOFT)
            e.setCapitals(true)
            val c = e.state.value.custom
            assertEquals(mapOf(ColorToken.BG to 0xFF101010), c.light)
            assertEquals(mapOf(ColorToken.ACCENT to 0xFFFF0000), c.dark)
            assertEquals(FontFace.SYSTEM, c.roles.fonts[FontRole.UI])
            assertEquals(1.1, c.roles.textScale)
            assertEquals(Density.COMPACT, c.roles.density)
            assertEquals(Corners.SOFT, c.roles.corners)
            assertEquals(true, c.roles.labelCapitals)
            assertEquals(c, store.state.value.custom)
            assertEquals(7, e.state.value.overrideCount)
        }

    @Test
    fun resetsClearOneRowAndResetAllKeepsTheBase() =
        runTest(UnconfinedTestDispatcher()) {
            val e = editor()
            e.setColor(ColorToken.BG, false, 1)
            e.setColor(ColorToken.INK, false, 2)
            e.setTextScale(1.2)
            e.resetColor(ColorToken.BG, false)
            e.resetTextScale()
            assertEquals(1, e.state.value.overrideCount)
            e.askResetAll()
            assertTrue(e.state.value.confirmResetAll)
            e.confirmResetAll()
            assertEquals(CustomTheme(BaseTheme.PAPER), e.state.value.custom)
            assertFalse(e.state.value.confirmResetAll)
            e.askResetAll()
            assertFalse(e.state.value.confirmResetAll, "nothing to reset")
        }

    @Test
    fun setBaseKeepsOverridesAndReportsTheCount() =
        runTest(UnconfinedTestDispatcher()) {
            val e = editor()
            e.setColor(ColorToken.BG, false, 1)
            e.setTextScale(1.2)
            e.setBase(BaseTheme.INDEX)
            assertEquals(BaseTheme.INDEX, e.state.value.custom.base)
            assertEquals(2, e.state.value.overrideCount)
            assertEquals(2, e.state.value.keptOverrides)
            e.setColor(ColorToken.INK, false, 3)
            assertEquals(0, e.state.value.keptOverrides)
        }

    @Test
    fun textScaleSnapsToStepsAndClamps() =
        runTest(UnconfinedTestDispatcher()) {
            val e = editor()
            e.setTextScale(1.07)
            assertEquals(1.05, e.state.value.custom.roles.textScale)
            e.setTextScale(2.0)
            assertEquals(1.3, e.state.value.custom.roles.textScale)
            e.setTextScale(0.2)
            assertEquals(0.85, e.state.value.custom.roles.textScale)
            e.resetTextScale()
            e.stepTextScale(+1)
            assertEquals(1.05, e.state.value.custom.roles.textScale)
        }

    @Test
    fun badFileChangesNothing() =
        runTest(UnconfinedTestDispatcher()) {
            val e = editor()
            e.setColor(ColorToken.BG, false, 1)
            val before = store.state.value
            assertEquals(ImportOutcome.NotAThemeFile, e.importText("hello"))
            assertEquals(before, store.state.value)
            assertTrue(e.state.value.importError)
            e.dismissImportError()
            assertFalse(e.state.value.importError)
        }

    @Test
    fun importOverAnEmptySlotIsAppliedAtOnce() =
        runTest(UnconfinedTestDispatcher()) {
            store.state.value = Appearance(ThemeChoice.INDEX, custom = CustomTheme(BaseTheme.SIGNAL))
            val e = editor()
            val file = """{"schema":1,"base":"index","overrides":{"light":{"bg":"#101010"}}}"""
            assertEquals(ImportOutcome.Imported, e.importText(file))
            assertEquals(ThemeChoice.CUSTOM, store.state.value.theme)
            assertEquals(
                mapOf(ColorToken.BG to 0xFF101010),
                store.state.value.custom!!
                    .light,
            )
        }

    @Test
    fun importOverOverridesAsksThenApplies() =
        runTest(UnconfinedTestDispatcher()) {
            val e = editor()
            e.setColor(ColorToken.BG, false, 1)
            val file = """{"schema":1,"base":"index","overrides":{"dark":{"ink":"#ffffff"}}}"""
            val outcome = assertIs<ImportOutcome.NeedsConfirm>(e.importText(file))
            assertEquals(BaseTheme.INDEX, outcome.theme.base)
            assertEquals(
                mapOf(ColorToken.BG to 1L),
                store.state.value.custom!!
                    .light,
                "not applied yet",
            )
            e.confirmImport()
            assertEquals(outcome.theme, store.state.value.custom)
            assertNull(e.state.value.pendingImport)
        }

    @Test
    fun exportThenImportIsTheIdentity() =
        runTest(UnconfinedTestDispatcher()) {
            val e = editor()
            e.setColor(ColorToken.INK_MUTED, true, 0xFF808080)
            e.setFont(FontRole.READING, FontFace.NEWSREADER)
            e.setCorners(Corners.ROUND)
            e.setTextScale(0.9)
            val text = e.exportText()
            val original = store.state.value.custom!!
            e.confirmResetAll()
            e.importText(text)
            if (e.state.value.pendingImport != null) e.confirmImport()
            assertEquals(original, store.state.value.custom)
            assertEquals(text, e.exportText())
        }

    @Test
    fun lowContrastInkMutedIsDetected() {
        val ok = CustomTheme(BaseTheme.SIGNAL)
        assertFalse(isLowContrast(inkMutedContrast(ok, dark = false)))
        val bad = ok.copy(light = mapOf(ColorToken.INK_MUTED to 0xFFC8C8C0))
        val ratio = inkMutedContrast(bad, dark = false)
        assertTrue(isLowContrast(ratio), "ratio $ratio")
        assertTrue(ratio in 1.0..3.0)
    }

    @Test
    fun translucentInkMutedIsCompositedOverTheBackground() {
        // Opaque black on a white bg is fine; the same black at 10 % alpha is nearly invisible.
        val base =
            CustomTheme(
                BaseTheme.SIGNAL,
                light = mapOf(ColorToken.BG to 0xFFFFFFFFL, ColorToken.SURFACE to 0xFFFFFFFFL, ColorToken.INK_MUTED to 0xFF000000L),
            )
        assertFalse(isLowContrast(inkMutedContrast(base, dark = false)))
        val faint = base.copy(light = base.light + (ColorToken.INK_MUTED to 0x1A000000L))
        val ratio = inkMutedContrast(faint, dark = false)
        assertTrue(isLowContrast(ratio), "ratio $ratio")
        assertTrue(ratio < 1.5)
    }

    @Test
    fun backClosesTheSheetThenTheDialogs() =
        runTest(UnconfinedTestDispatcher()) {
            val e = editor()
            e.openColor(ColorToken.BG)
            assertEquals(EditorSheet.PickColor(ColorToken.BG), e.state.value.sheet)
            assertTrue(e.onBack())
            assertNull(e.state.value.sheet)
            assertFalse(e.onBack())
        }

    /** A store whose flow never emits again, like a state that lags the database write. */
    private fun laggingStore(): app.citti.ui.settings.AppearanceStore {
        val initial = store.state.value
        return object : app.citti.ui.settings.AppearanceStore by store {
            override fun appearance() = kotlinx.coroutines.flow.flowOf(initial)
        }
    }

    @Test
    fun quickTextSizeTapsAddUpDespiteALaggingState() =
        runTest(UnconfinedTestDispatcher()) {
            val e = ThemeEditorController(backgroundScope, laggingStore())
            e.stepTextScale(+1)
            e.stepTextScale(+1)
            assertEquals(
                1.1,
                store.state.value.custom!!
                    .roles.textScale,
            )
        }

    @Test
    fun setBaseCountsTheStoredOverridesDespiteALaggingState() =
        runTest(UnconfinedTestDispatcher()) {
            val e = ThemeEditorController(backgroundScope, laggingStore())
            e.setColor(ColorToken.BG, false, 1)
            e.setTextScale(1.2)
            e.setBase(BaseTheme.INDEX)
            assertEquals(2, e.state.value.keptOverrides)
            assertEquals(
                BaseTheme.INDEX,
                store.state.value.custom!!
                    .base,
            )
            assertEquals(
                2,
                store.state.value.custom!!
                    .overrideCount,
            )
        }

    @Test
    fun importEmitsOneAppearanceWithTheThemeAndCustomTogether() =
        runTest(UnconfinedTestDispatcher()) {
            store.state.value = Appearance(ThemeChoice.INDEX, custom = CustomTheme(BaseTheme.SIGNAL))
            val seen = mutableListOf<Appearance>()
            backgroundScope.launch { store.appearance().toList(seen) }
            val e = editor()
            e.importText("""{"schema":1,"base":"paper","overrides":{}}""")
            assertEquals(2, seen.size, "initial value plus one write")
            assertEquals(ThemeChoice.CUSTOM, seen.last().theme)
            assertEquals(BaseTheme.PAPER, seen.last().custom!!.base)
        }
}
