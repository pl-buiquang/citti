package app.citti.widget

import android.content.Context
import androidx.glance.appwidget.testing.unit.hasStartActivityClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.citti.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = android.app.Application::class)
class FavoritesWidgetTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private companion object {
        const val TODAY = "Saturday 31 October"
    }

    @Test
    fun rowsShowTheTitleAndTheFolderAndOpenTheirNote() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            val rows = listOf(FavoriteRow(7, "Groceries", ""), FavoriteRow(9, "Trip plan", "personal/trips"))
            provideComposable { FavoritesContent(FavoritesState.Loaded(rows, TODAY)) }

            onNode(hasText("Groceries")).assertExists()
            onNode(hasText(context.getString(R.string.widget_root_folder))).assertExists()
            onNode(hasText("Trip plan")).assertExists()
            onNode(hasText("personal/trips")).assertExists()
            onNode(hasStartActivityClickAction(WidgetIntents.note(context, 7))).assertExists()
            onNode(hasStartActivityClickAction(WidgetIntents.note(context, 9))).assertExists()
        }

    @Test
    fun theHeaderOpensTheApp() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            provideComposable { FavoritesContent(FavoritesState.Loaded(emptyList(), TODAY)) }

            onNode(hasText(context.getString(R.string.widget_favorites_name))).assertExists()
            onNode(hasStartActivityClickAction(WidgetIntents.app(context))).assertExists()
        }

    @Test
    fun noFavoritesShowsTheEmptyState() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            provideComposable { FavoritesContent(FavoritesState.Loaded(emptyList(), TODAY)) }

            onNode(hasText(context.getString(R.string.widget_favorites_empty))).assertExists()
        }

    @Test
    fun signedOutAsksToSignInAndOpensTheApp() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            provideComposable { FavoritesContent(FavoritesState.SignedOut) }

            onNode(hasText(context.getString(R.string.widget_signed_out))).assertExists()
            onNode(hasStartActivityClickAction(WidgetIntents.app(context))).assertExists()
            onNode(hasText(context.getString(R.string.widget_favorites_name))).assertDoesNotExist()
        }

    @Test
    fun theTodayRowIsThereWithTheDateAndOpensTheDailyLink() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            provideComposable { FavoritesContent(FavoritesState.Loaded(listOf(FavoriteRow(7, "Groceries", "")), TODAY)) }

            onNode(hasText(context.getString(R.string.widget_today_row))).assertExists()
            onNode(hasText(TODAY)).assertExists()
            onAllNodes(hasStartActivityClickAction(WidgetIntents.daily(context, resume = false))).assertCountEquals(1)
        }

    @Test
    fun theTodayRowStaysWhenThereAreNoFavorites() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            provideComposable { FavoritesContent(FavoritesState.Loaded(emptyList(), TODAY)) }

            onNode(hasText(context.getString(R.string.widget_today_row))).assertExists()
            onNode(hasText(context.getString(R.string.widget_favorites_empty))).assertExists()
        }

    @Test
    fun signedOutHasNoTodayRow() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            provideComposable { FavoritesContent(FavoritesState.SignedOut) }

            onNode(hasText(context.getString(R.string.widget_today_row))).assertDoesNotExist()
            onNode(hasStartActivityClickAction(WidgetIntents.daily(context, resume = false))).assertDoesNotExist()
        }
}
