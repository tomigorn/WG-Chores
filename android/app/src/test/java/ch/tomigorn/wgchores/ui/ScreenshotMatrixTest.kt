package ch.tomigorn.wgchores.ui

import ch.tomigorn.wgchores.auth.AuthState
import ch.tomigorn.wgchores.auth.LoginError
import ch.tomigorn.wgchores.auth.ServerError
import ch.tomigorn.wgchores.ui.list.DoneSheet
import ch.tomigorn.wgchores.ui.list.Snack
import ch.tomigorn.wgchores.ui.screens.ServerScreen
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Server picker and login: font 1.0 and 2.0, light and dark. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ScreenshotMatrixTest(private val fontScale: Float, private val dark: Boolean) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "font{0}_dark{1}")
        fun params() = listOf(arrayOf<Any>(1f, false), arrayOf<Any>(1f, true), arrayOf<Any>(2f, false), arrayOf<Any>(2f, true))
    }

    @Before fun setUp() = Shots.setUp(fontScale, dark)

    @Test fun serverPicker() = Shots.capture("server_picker", fontScale, dark) {
        ServerScreen(AuthState.PickServer("https://chores.example", ServerError.NotWgChores), onConnect = {})
    }

    @Test fun choreList() = Shots.capture("list", fontScale, dark) { Shots.ListScreen(Shots.list) }

    @Test fun snackbar() = Shots.capture("list_snackbar", fontScale, dark) {
        val prev = Shots.chores[1]
        val done = Shots.list.copy(
            chores = Shots.chores.map { if (it.id == 2L) ch.tomigorn.wgchores.domain.Chores.applyCompletion(it, "2026-11-14", prev.last!!.by, Shots.today) else it },
            snack = Snack.Done(prev, "u", "2026-11-14", null, otherDate = true),
            pending = 1,
        )
        Shots.ListScreen(done)
    }

    @Test fun doneSheet() = Shots.capture("list_sheet", fontScale, dark) {
        val c = Shots.chores[1]
        Shots.ListScreen(Shots.list.copy(sheet = DoneSheet(c, c, null, "2026-11-14", null)))
    }

    @Test fun login() = Shots.capture("login", fontScale, dark) {
        Shots.Login(AuthState.Login(Shots.SERVER, Shots.config, LoginError.Code("invalid_credentials")))
    }
}
