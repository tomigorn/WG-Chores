package ch.tomigorn.wgchores.ui

import androidx.compose.runtime.Composable
import ch.tomigorn.wgchores.auth.AuthState
import ch.tomigorn.wgchores.auth.LoginError
import ch.tomigorn.wgchores.auth.ServerError
import ch.tomigorn.wgchores.data.ServerConfig
import ch.tomigorn.wgchores.data.ThemeMode
import ch.tomigorn.wgchores.ui.screens.HomeScreen
import ch.tomigorn.wgchores.ui.screens.LoginScreen
import ch.tomigorn.wgchores.ui.screens.ServerScreen
import ch.tomigorn.wgchores.ui.screens.UpdateScreen
import ch.tomigorn.wgchores.ui.theme.WgcTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Screens at the narrowest supported width (320 dp), normal and doubled font, light and dark. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ScreenshotTest(private val fontScale: Float, private val dark: Boolean) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "font{0}_dark{1}")
        fun params() = listOf(arrayOf<Any>(1f, false), arrayOf<Any>(1f, true), arrayOf<Any>(2f, false), arrayOf<Any>(2f, true))

        private val config = ServerConfig(
            name = "WG Chores",
            localAccounts = true,
            openRegistration = true,
            oidc = ServerConfig.Oidc("Holy Grail"),
            minAppVersion = "1.0.0",
        )
        private const val SERVER = "https://chores.example.com"
    }

    private val variant = "${if (fontScale > 1f) "font2x" else "font1x"}_${if (dark) "dark" else "light"}"

    @Before fun setUp() {
        val height = if (fontScale > 1f) 1500 else 800
        RuntimeEnvironment.setQualifiers("w320dp-h${height}dp-${if (dark) "night" else "notnight"}-xhdpi")
        RuntimeEnvironment.setFontScale(fontScale)
    }

    private fun shot(name: String, content: @Composable () -> Unit) =
        captureRoboImage("src/test/screenshots/${name}_$variant.png") { WgcTheme(dark = dark) { content() } }

    private fun firstVariantOnly() = assumeTrue(fontScale == 1f)

    @Test fun serverPicker() = shot("server_picker") {
        ServerScreen(AuthState.PickServer("https://chores.example", ServerError.NotWgChores), onConnect = {})
    }

    @Test fun login() = shot("login") {
        LoginScreen(
            AuthState.Login(SERVER, config, LoginError.Code("invalid_credentials")),
            onSso = {}, onLogin = { _, _ -> }, onRegister = { _, _, _ -> }, onRetry = {}, onChangeServer = {},
        )
    }

    @Test fun loginSsoOnly() {
        firstVariantOnly()
        shot("login_sso_only") {
            LoginScreen(
                AuthState.Login(SERVER, config.copy(localAccounts = false, openRegistration = false)),
                onSso = {}, onLogin = { _, _ -> }, onRegister = { _, _, _ -> }, onRetry = {}, onChangeServer = {},
            )
        }
    }

    @Test fun loginOffline() {
        firstVariantOnly()
        shot("login_offline") {
            LoginScreen(
                AuthState.Login(SERVER, null, LoginError.Offline),
                onSso = {}, onLogin = { _, _ -> }, onRegister = { _, _, _ -> }, onRetry = {}, onChangeServer = {},
            )
        }
    }

    @Test fun loginGerman() {
        assumeTrue(fontScale > 1f && !dark)
        RuntimeEnvironment.setQualifiers("+de")
        shot("login_de") {
            LoginScreen(
                AuthState.Login(SERVER, config, LoginError.Code("not_allowed")),
                onSso = {}, onLogin = { _, _ -> }, onRegister = { _, _, _ -> }, onRetry = {}, onChangeServer = {},
            )
        }
    }

    @Test fun updateRequired() {
        firstVariantOnly()
        shot("update") { UpdateScreen(AuthState.UpdateRequired(SERVER, "1.2.0", "1.0.0"), onOpenStore = {}, onChangeServer = {}) }
    }

    @Test fun home() {
        firstVariantOnly()
        shot("home") { HomeScreen("Jane Doe", ThemeMode.System, onTheme = {}, onSignOut = {}) }
    }
}
