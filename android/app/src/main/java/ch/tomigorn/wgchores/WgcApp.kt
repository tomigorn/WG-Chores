package ch.tomigorn.wgchores

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import ch.tomigorn.wgchores.auth.AuthController
import ch.tomigorn.wgchores.data.Account
import ch.tomigorn.wgchores.notify.NotifyCheck
import ch.tomigorn.wgchores.notify.NotifyJob
import ch.tomigorn.wgchores.notify.NotifyPlan
import ch.tomigorn.wgchores.notify.NotifyScheduler
import ch.tomigorn.wgchores.notify.NotifyStore
import ch.tomigorn.wgchores.notify.NotifyTexts
import ch.tomigorn.wgchores.notify.NotifyWatch
import ch.tomigorn.wgchores.notify.NotifyWorkerFactory
import ch.tomigorn.wgchores.notify.Notifier
import ch.tomigorn.wgchores.ui.account.AppLanguage
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
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
private val Context.notifyPrefs by preferencesDataStore("notify")

class AppContainer(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val session = SessionStore(context.sessionPrefs, TokenCipher(forget = TokenCipher::deleteKeystoreKey) { TokenCipher.keystoreKey() }) // leak-check: ok
    val settings = SettingsStore(context.settingsPrefs)
    val notifyStore = NotifyStore(context.notifyPrefs)
    val auth = AuthController(
        session,
        appVersion = BuildConfig.VERSION_NAME,
        deviceName = Build.MODEL.ifBlank { "Android" },
        allowLocalHttp = BuildConfig.ALLOW_LOCAL_HTTP,
        onUser = { server, u -> notifyStore.saveFlags(Account.keyOf(server, u.id), u.notifyDone, u.notifyDue) },
    )
    val db = WgcDatabase.open(context)
    /** Waiting done marks are sent on app start, when a network comes up, after each loaded list and by the background check. */
    val sync = Sync(session, auth::api, db, scope)
    val notifier = Notifier(context)
    val notifyScheduler = NotifyScheduler({ WorkManager.getInstance(context) }, notifyStore)
    private val notifyCheck = NotifyCheck(
        account = sync::account,
        flush = sync::flush,
        store = notifyStore,
        show = notifier,
        lang = ::notifyLang,
    )
    val notifyJob = object : NotifyJob {
        override suspend fun check(): NotifyCheck.Outcome {
            notifier.createChannels(notifyLang())
            return notifyCheck.run()
        }
        override suspend fun scheduleAfter(ran: Instant) {
            notifyPlan()?.let { notifyScheduler.next(it, ran) }
        }
    }
    /** A notification tap asked for this household; the signed-in area opens it. */
    val openHousehold = MutableStateFlow<Long?>(null)

    suspend fun notifyLang(): String = NotifyTexts.resolve(Build.VERSION.SDK_INT, notifyStore.language(), AppLanguage.current(), Locale.getDefault())

    /** Settings changed the app language: remembered for background checks on Android 8–12, channel names follow. */
    fun languageChanged(tag: String) {
        scope.launch {
            notifyStore.saveLanguage(tag)
            notifier.createChannels(notifyLang())
        }
    }

    private suspend fun notifyPlan(): NotifyPlan? {
        val s = session.read()
        val server = s.server ?: return null
        val id = s.userId ?: return null
        val config = s.config ?: return null
        if (s.token == null) return null
        return NotifyPlan(Account.keyOf(server, id), config.notifyTimes, config.tz)
    }

    private val notifyWatch = NotifyWatch(
        state = auth.state,
        configs = session.configs,
        enabled = notifyStore::enabled,
        signedOut = {
            guarded {
                notifier.cancelAll()
                notifyStore.forgetChecks()
            }
        },
        apply = { plan -> guarded { notifyScheduler.apply(plan) } },
    )

    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("WgChores", "notification schedule failed", e)
        }
    }

    fun watchNotifications() {
        scope.launch { notifyWatch.run() }
        scope.launch { notifier.createChannels(notifyLang()) }
    }

    /** From MainActivity: a notification tap counts only for the account signed in now and a household it knows (cached list or the server's list). */
    fun openFromNotification(intent: Intent?) {
        if (intent?.action != Notifier.ACTION_OPEN) return
        scope.launch {
            val a = sync.account() ?: return@launch
            val known: suspend (Long) -> Boolean = { hid ->
                sync.loadList(a, hid) != null || try {
                    a.api.households().any { it.id == hid }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
            }
            Notifier.household(intent, a.key, known)?.let { openHousehold.value = it }
        }
    }

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

class WgcApp : Application(), Configuration.Provider {
    val container by lazy { AppContainer(this) }

    val workerFactory: WorkerFactory by lazy { NotifyWorkerFactory { container.notifyJob } }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        container.watchNotifications()
        container.sync.kick()
        runCatching {
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = container.sync.kick()
            })
        }
    }
}
