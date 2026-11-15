package ch.tomigorn.wgchores.ui

import ch.tomigorn.wgchores.auth.AuthState
import ch.tomigorn.wgchores.auth.LoginError
import ch.tomigorn.wgchores.ui.list.DoneSheet
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The longest texts: German at font 2.0. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ScreenshotGermanTest {
    @Before fun setUp() = Shots.setUp(2f, dark = false, locale = "de")

    @Test fun choreList() = Shots.capture("list_de", 2f, dark = false) { Shots.ListScreen(Shots.list.copy(offline = true, pending = 2)) }

    @Test fun snackbarAndSheet() = Shots.capture("list_sheet_de", 2f, dark = false) {
        val c = Shots.chores[1]
        Shots.ListScreen(Shots.list.copy(sheet = DoneSheet(c, c, "u", "2026-11-10", "neuer Reiniger")))
    }

    @Test fun login() = Shots.capture("login_de", 2f, dark = false) {
        Shots.Login(AuthState.Login(Shots.SERVER, Shots.config, LoginError.Code("not_allowed")))
    }
}
