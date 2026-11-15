package ch.tomigorn.wgchores.ui

import ch.tomigorn.wgchores.data.ThemeMode
import ch.tomigorn.wgchores.ui.components.TopBar
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Top bar at 320 dp ×2.0: en/de, light/dark, with and without the sync chip. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ScreenshotTopBarTest(private val locale: String, private val dark: Boolean) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}_dark{1}")
        fun params() = listOf("en", "de").flatMap { l -> listOf(arrayOf<Any>(l, false), arrayOf<Any>(l, true)) }
    }

    @Before fun setUp() = Shots.setUp(2f, dark, locale)

    @Test fun withChip() = Shots.capture("topbar_${locale}_chip", 2f, dark) {
        TopBar("WG Zürich", "Jane Doe", 3, false, ThemeMode.System, Shots.bar)
    }

    @Test fun offlineLongName() = Shots.capture("topbar_${locale}_offline", 2f, dark) {
        TopBar("Wohngemeinschaft Langstrasse", "Jane Doe", 0, true, ThemeMode.System, Shots.bar)
    }

    @Test fun withoutChip() = Shots.capture("topbar_${locale}_nochip", 2f, dark) {
        TopBar("WG Zürich", "Jane Doe", 0, false, ThemeMode.System, Shots.bar)
    }
}
