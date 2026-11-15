package ch.tomigorn.wgchores.ui.chore

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.onNodeWithTag
import ch.tomigorn.wgchores.ui.Screens
import ch.tomigorn.wgchores.ui.Shots
import ch.tomigorn.wgchores.ui.noop
import ch.tomigorn.wgchores.ui.theme.WgcTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Chore page footer: "Other date…" is a square calendar button as tall as "Done today", which takes the rest of the row. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ChoreFooterTest(private val lang: String, private val fontScale: Float) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}_font{1}")
        fun params() = listOf("en", "de").flatMap { l -> listOf(1f, 2f).map { arrayOf<Any>(l, it) } }
    }

    @get:Rule val compose = createComposeRule()

    @Test fun otherDateIsAnIconButtonBesideDoneToday() {
        Shots.setUp(fontScale, dark = false, locale = if (lang == "de") "de" else null)
        compose.setContent { WgcTheme(dark = false) { ChoreScreen(Screens.chore, noop()) } }
        val other = compose.onNodeWithTag("chore-other-date").fetchSemanticsNode()
        val done = compose.onNodeWithTag("chore-done").fetchSemanticsNode()
        val label = if (lang == "de") "Anderes Datum…" else "Other date…"
        assertEquals(listOf(label), other.config.getOrNull(SemanticsProperties.ContentDescription))
        assertNull(other.config.getOrNull(SemanticsProperties.Text))
        val o = other.boundsInRoot
        val d = done.boundsInRoot
        assertEquals("same row", d.top, o.top, 1f)
        assertEquals("same height", d.height, o.height, 1f)
        assertEquals("square", o.height, o.width, 1f)
        assertTrue("Done today fills the rest", d.width > o.width * 2)
        assertTrue("calendar right of Done today", o.left >= d.right)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNode(hasText(if (lang == "de") "Heute erledigt" else "Done today") and hasAnyAncestor(hasTestTag("chore-done")), useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val t = layouts.single()
        assertFalse("Done today is cut off", (0 until t.lineCount).any { t.isLineEllipsized(it) })
    }
}
