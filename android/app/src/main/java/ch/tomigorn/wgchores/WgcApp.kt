package ch.tomigorn.wgchores

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.datastore.preferences.preferencesDataStore
import ch.tomigorn.wgchores.auth.AuthController
import ch.tomigorn.wgchores.data.SessionStore
import ch.tomigorn.wgchores.data.SettingsStore
import ch.tomigorn.wgchores.data.TokenCipher

private val Context.sessionPrefs by preferencesDataStore("session")
private val Context.settingsPrefs by preferencesDataStore("settings")

class AppContainer(context: Context) {
    val session = SessionStore(context.sessionPrefs, TokenCipher { TokenCipher.keystoreKey() })
    val settings = SettingsStore(context.settingsPrefs)
    val auth = AuthController(
        session,
        appVersion = BuildConfig.VERSION_NAME,
        deviceName = Build.MODEL.ifBlank { "Android" },
        allowLocalHttp = BuildConfig.DEBUG,
    )
}

class WgcApp : Application() {
    val container by lazy { AppContainer(this) }
}
