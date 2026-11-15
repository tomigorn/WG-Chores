package ch.tomigorn.wgchores.ui

import ch.tomigorn.wgchores.auth.AuthState
import ch.tomigorn.wgchores.auth.LoginError
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

    @Test fun login() = Shots.capture("login_de", 2f, dark = false) {
        Shots.Login(AuthState.Login(Shots.SERVER, Shots.config, LoginError.Code("not_allowed")))
    }
}
