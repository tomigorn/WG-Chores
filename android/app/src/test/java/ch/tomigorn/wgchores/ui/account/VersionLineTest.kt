package ch.tomigorn.wgchores.ui.account

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.text.TextLayoutResult
import ch.tomigorn.wgchores.data.ThemeMode
import ch.tomigorn.wgchores.ui.Shots
import ch.tomigorn.wgchores.ui.noop
import ch.tomigorn.wgchores.ui.theme.WgcTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The version line shows "App x · Server y" (only "App x" without a server version) and never truncates a value. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class VersionLineTest(private val fontScale: Float) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "font{0}")
        fun params() = listOf(arrayOf<Any>(1f), arrayOf<Any>(2f))
    }

    @get:Rule val compose = createComposeRule()

    private fun show(server: String?) {
        Shots.setUp(fontScale, dark = false)
        val ui = SettingsUi(loaded = true, appVersion = "1.0.0", serverVersion = server)
        compose.setContent { WgcTheme(dark = false) { SettingsScreen(ui, ThemeMode.System, noop()) } }
    }

    private fun lines() = compose.onAllNodesWithTag("version-text", useUnmergedTree = true).fetchSemanticsNodes().map { n ->
        val layouts = mutableListOf<TextLayoutResult>()
        n.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        assertEquals(1, layouts.single().lineCount)
        assertFalse(layouts.single().multiParagraph.didExceedMaxLines)
        assertTrue(layouts.single().size.width <= layouts.single().layoutInput.constraints.maxWidth)
        n.config[SemanticsProperties.Text].joinToString("")
    }

    @Test fun appAndServer() {
        show("1.0.0")
        val l = lines()
        assertEquals("App 1.0.0 · Server 1.0.0", l.joinToString(" · ").let { if (l.size == 1) l[0] else it })
    }

    @Test fun serverUnknownShowsOnlyApp() {
        show(null)
        assertEquals(listOf("App 1.0.0"), lines())
    }
}
