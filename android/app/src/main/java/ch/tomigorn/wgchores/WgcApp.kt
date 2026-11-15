package ch.tomigorn.wgchores

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import androidx.datastore.preferences.preferencesDataStore
import ch.tomigorn.wgchores.auth.AuthController
import ch.tomigorn.wgchores.data.SessionStore
import ch.tomigorn.wgchores.data.SettingsStore
import ch.tomigorn.wgchores.data.Sync
import ch.tomigorn.wgchores.data.TokenCipher
import ch.tomigorn.wgchores.data.db.WgcDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val Context.sessionPrefs by preferencesDataStore("session")
private val Context.settingsPrefs by preferencesDataStore("settings")

class AppContainer(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val session = SessionStore(context.sessionPrefs, TokenCipher(forget = TokenCipher::deleteKeystoreKey) { TokenCipher.keystoreKey() }) // leak-check: ok
    val settings = SettingsStore(context.settingsPrefs)
    val auth = AuthController(
        session,
        appVersion = BuildConfig.VERSION_NAME,
        deviceName = Build.MODEL.ifBlank { "Android" },
        allowLocalHttp = BuildConfig.DEBUG,
    )
    val db = WgcDatabase.open(context)
    /** Waiting done marks are sent on app start, when a network comes up, after each loaded list and by the background check. */
    val sync = Sync(session, auth::api, db, scope)

    /** An SSO callback from the fallback alias; finished here so it survives the trampoline activity. */
    fun deliverSso(uri: String) {
        scope.launch {
            try {
                auth.finishSso(uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                auth.failed()
            }
        }
    }
}

class WgcApp : Application() {
    val container by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        container.sync.kick()
        runCatching {
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = container.sync.kick()
            })
        }
    }
}
