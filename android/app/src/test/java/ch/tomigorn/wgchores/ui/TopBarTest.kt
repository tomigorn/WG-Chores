package ch.tomigorn.wgchores.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import ch.tomigorn.wgchores.data.ThemeMode
import ch.tomigorn.wgchores.ui.components.TopBar
import ch.tomigorn.wgchores.ui.theme.WgcTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The household name stays readable: the full name or at least 8 characters, whatever the font scale or chip. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class TopBarTest(private val title: String, private val pending: Int, private val offline: Boolean, private val fontScale: Float) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}_pending{1}_offline{2}_font{3}")
        fun params() = listOf("WG Zürich", "Wohngemeinschaft Langstrasse").flatMap { t ->
            listOf(1f, 1.3f, 1.5f, 2f).flatMap { f -> listOf(arrayOf<Any>(t, 0, false, f), arrayOf<Any>(t, 3, false, f), arrayOf<Any>(t, 0, true, f)) }
        }
    }

    @get:Rule val compose = createComposeRule()

    @Test fun titleKeepsAtLeastEightCharacters() {
        Shots.setUp(fontScale, dark = false)
        compose.setContent { WgcTheme(dark = false) { TopBar(title, "Jane Doe", pending, offline, ThemeMode.System, Shots.bar) } }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("household-title", useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val l = layouts.single()
        val visible = l.getLineEnd(l.lineCount - 1, visibleEnd = true)
        assertTrue("only $visible of \"$title\" visible", visible >= minOf(8, title.length))
        for (line in 0 until l.lineCount - 1) {
            val end = l.getLineEnd(line)
            assertTrue("\"$title\" breaks inside a word after $end", title[end - 1].isWhitespace() || title[end].isWhitespace())
        }
    }
}
