package ch.tomigorn.wgchores.ui

import ch.tomigorn.wgchores.auth.AuthState
import ch.tomigorn.wgchores.auth.LoginError
import ch.tomigorn.wgchores.data.ThemeMode
import ch.tomigorn.wgchores.ui.screens.HomeScreen
import ch.tomigorn.wgchores.ui.screens.UpdateScreen
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Further states at font 1.0, light and dark. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ScreenshotStatesTest(private val dark: Boolean) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "dark{0}")
        fun params() = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }

    @Before fun setUp() = Shots.setUp(1f, dark)

    @Test fun loginSsoOnly() = Shots.capture("login_sso_only", 1f, dark) {
        Shots.Login(AuthState.Login(Shots.SERVER, Shots.config.copy(localAccounts = false, openRegistration = false)))
    }

    @Test fun loginOffline() = Shots.capture("login_offline", 1f, dark) {
        Shots.Login(AuthState.Login(Shots.SERVER, null, LoginError.Offline))
    }

    @Test fun updateRequired() = Shots.capture("update", 1f, dark) {
        UpdateScreen(AuthState.UpdateRequired(Shots.SERVER, "1.2.0", "1.0.0"), onOpenStore = {}, onChangeServer = {})
    }

    @Test fun home() = Shots.capture("home", 1f, dark) {
        HomeScreen("Jane Doe", ThemeMode.System, onTheme = {}, onSignOut = {})
    }
}
