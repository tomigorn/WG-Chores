package ch.tomigorn.wgchores.ui

import ch.tomigorn.wgchores.auth.AuthState
import ch.tomigorn.wgchores.auth.LoginError
import ch.tomigorn.wgchores.auth.ServerError
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

    @Test fun login() = Shots.capture("login", fontScale, dark) {
        Shots.Login(AuthState.Login(Shots.SERVER, Shots.config, LoginError.Code("invalid_credentials")))
    }
}
