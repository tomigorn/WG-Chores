package ch.tomigorn.wgchores

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.browser.auth.AuthTabIntent
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.tomigorn.wgchores.auth.SsoCallback
import ch.tomigorn.wgchores.data.ThemeMode
import ch.tomigorn.wgchores.ui.AppActions
import ch.tomigorn.wgchores.ui.AppRoot
import ch.tomigorn.wgchores.ui.theme.WgcTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    private val authTab = AuthTabIntent.registerActivityResultLauncher(this) { result ->
        if (result.resultCode == AuthTabIntent.RESULT_OK) result.resultUri?.let { vm.finishSso(it.toString()) }
    }

    private val actions = object : AppActions {
        override fun connect(url: String) = vm.connect(url)
        override fun login(username: String, pw: String) = vm.login(username, pw)
        override fun register(displayName: String, username: String, pw: String) = vm.register(displayName, username, pw)
        override fun sso() = vm.beginSso(::openSso)
        override fun retry() = vm.retry()
        override fun changeServer() = vm.changeServer()
        override fun signOut() = vm.signOut()
        override fun setTheme(mode: ThemeMode) = vm.setTheme(mode)
        override fun openStore() {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, PLAY_STORE_URL.toUri())) }
        }
    }

    private var fallbackOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fallbackOpen = savedInstanceState?.getBoolean(FALLBACK_OPEN) ?: false
        if (!handleCallback(intent) && !fallbackOpen) setFallbackCallback(false)
        setContent {
            val state by vm.auth.collectAsStateWithLifecycle()
            val theme by vm.theme.collectAsStateWithLifecycle()
            val dark = when (theme) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            LaunchedEffect(dark) {
                val bars = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            WgcTheme(dark = dark) { AppRoot(state, theme, actions) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleCallback(intent)
    }

    override fun onResume() {
        super.onResume()
        // back from the fallback Custom Tab, with or without a callback: close the wgchores://auth door again
        if (fallbackOpen) {
            fallbackOpen = false
            setFallbackCallback(false)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(FALLBACK_OPEN, fallbackOpen)
    }

    override fun onDestroy() {
        if (isFinishing) setFallbackCallback(false)
        super.onDestroy()
    }

    private fun handleCallback(intent: Intent?): Boolean {
        val uri = SsoCallback.fromIntent(intent?.component?.className, packageName, intent?.data?.toString()) ?: return false
        setFallbackCallback(false)
        intent?.data = null
        vm.finishSso(uri)
        return true
    }

    /** Auth Tab returns the callback only to this app; browsers without it get a Custom Tab and the wgchores://auth filter, enabled just for that. */
    private fun openSso(url: String) {
        val browser = CustomTabsClient.getPackageName(this, null)
        if (browser != null && CustomTabsClient.isAuthTabSupported(this, browser)) {
            AuthTabIntent.Builder().build().launch(authTab, url.toUri(), SsoCallback.SCHEME)
        } else {
            setFallbackCallback(true)
            fallbackOpen = true
            CustomTabsIntent.Builder().build().launchUrl(this, url.toUri())
        }
    }

    private fun setFallbackCallback(enabled: Boolean) {
        packageManager.setComponentEnabledSetting(
            ComponentName(this, "$packageName.${SsoCallback.FALLBACK_ALIAS}"),
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    companion object {
        private const val FALLBACK_OPEN = "fallback_open"
        const val PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=ch.tomigorn.wgchores"
    }
}
