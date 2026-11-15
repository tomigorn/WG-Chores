package ch.tomigorn.wgchores.ui

import androidx.compose.runtime.Composable
import ch.tomigorn.wgchores.auth.AuthState
import ch.tomigorn.wgchores.data.ServerConfig
import ch.tomigorn.wgchores.ui.screens.LoginScreen
import ch.tomigorn.wgchores.ui.theme.WgcTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.robolectric.RuntimeEnvironment

/** Screens at the narrowest supported width (320 dp); the golden name carries font scale and theme. */
object Shots {
    const val SERVER = "https://chores.example.com"
    val config = ServerConfig(
        name = "WG Chores",
        localAccounts = true,
        openRegistration = true,
        oidc = ServerConfig.Oidc("Holy Grail"),
        minAppVersion = "1.0.0",
    )

    fun setUp(fontScale: Float, dark: Boolean, locale: String? = null) {
        val height = if (fontScale > 1f) 1500 else 800
        val lang = locale?.let { "$it-" } ?: ""
        RuntimeEnvironment.setQualifiers("${lang}w320dp-h${height}dp-${if (dark) "night" else "notnight"}-xhdpi")
        RuntimeEnvironment.setFontScale(fontScale)
    }

    fun capture(name: String, fontScale: Float, dark: Boolean, content: @Composable () -> Unit) {
        val variant = "${if (fontScale > 1f) "font2x" else "font1x"}_${if (dark) "dark" else "light"}"
        captureRoboImage("src/test/screenshots/${name}_$variant.png") { WgcTheme(dark = dark) { content() } }
    }

    @Composable
    fun Login(state: AuthState.Login) =
        LoginScreen(state, onSso = {}, onLogin = { _, _ -> }, onRegister = { _, _, _ -> }, onRetry = {}, onChangeServer = {})
}
