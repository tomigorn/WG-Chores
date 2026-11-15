package ch.tomigorn.wgchores.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

data class Session(
    val server: String? = null,
    val config: ServerConfig? = null,
    val token: String? = null, // leak-check: ok
    val userName: String? = null,
    val userId: Long? = null,
    val lastHousehold: Long? = null,
)

class SessionStore(private val prefs: DataStore<Preferences>, private val cipher: TokenCipher) {
    private object Keys {
        val server = stringPreferencesKey("server")
        val config = stringPreferencesKey("config")
        val token = stringPreferencesKey("token") // leak-check: ok
        val userName = stringPreferencesKey("user_name")
        val verifier = stringPreferencesKey("sso_verifier")
        val userId = longPreferencesKey("user_id")
        val lastHousehold = longPreferencesKey("last_household")
    }

    /** Sealed blob and its plain value: the Keystore is asked once per stored token, off the main thread. */
    @Volatile private var cached: Pair<String, String?>? = null

    private suspend fun unseal(blob: String?): String? {
        if (blob == null) return null
        cached?.let { (b, plain) -> if (b == blob) return plain }
        return withContext(Dispatchers.Default) { cipher.decrypt(blob) }.also { cached = blob to it }
    }

    suspend fun read(): Session {
        val p = prefs.data.first()
        return Session(
            server = p[Keys.server],
            config = p[Keys.config]?.let(ServerConfig::parse),
            token = unseal(p[Keys.token]), // leak-check: ok
            userName = p[Keys.userName],
            userId = p[Keys.userId],
            lastHousehold = p[Keys.lastHousehold],
        )
    }

    suspend fun saveServer(url: String, config: ServerConfig) {
        prefs.edit {
            if (it[Keys.server] != url) it.clear()
            it[Keys.server] = url
            it[Keys.config] = config.toJson()
        }
    }

    suspend fun saveConfig(config: ServerConfig) {
        prefs.edit { it[Keys.config] = config.toJson() }
    }

    suspend fun saveSignIn(bearer: String, userName: String, userId: Long? = null): Boolean {
        val sealed = withContext(Dispatchers.Default) { cipher.encrypt(bearer) } ?: return false
        prefs.edit {
            it[Keys.token] = sealed
            it[Keys.userName] = userName
            if (userId != null) it[Keys.userId] = userId else it.remove(Keys.userId)
            it.remove(Keys.lastHousehold)
        }
        cached = sealed to bearer
        return true
    }

    /** Who is signed in and which household they had open, kept for offline starts. */
    suspend fun saveUser(userId: Long, userName: String, lastHousehold: Long?) {
        prefs.edit {
            it[Keys.userId] = userId
            it[Keys.userName] = userName
            if (lastHousehold != null) it[Keys.lastHousehold] = lastHousehold else it.remove(Keys.lastHousehold)
        }
    }

    suspend fun saveLastHousehold(id: Long?) {
        prefs.edit { if (id != null) it[Keys.lastHousehold] = id else it.remove(Keys.lastHousehold) }
    }

    suspend fun clearSignIn() {
        cached = null
        prefs.edit {
            it.remove(Keys.token)
            it.remove(Keys.userName)
            it.remove(Keys.userId)
            it.remove(Keys.lastHousehold)
            it.remove(Keys.verifier)
        }
    }

    suspend fun saveVerifier(verifier: String): Boolean {
        val sealed = withContext(Dispatchers.Default) { cipher.encrypt(verifier) } ?: return false
        prefs.edit { it[Keys.verifier] = sealed }
        return true
    }

    /** The pending SSO verifier, removed as it is read: a callback is accepted once. */
    suspend fun takeVerifier(): String? {
        var sealed: String? = null
        prefs.edit {
            sealed = it[Keys.verifier]
            it.remove(Keys.verifier)
        }
        return sealed?.let { withContext(Dispatchers.Default) { cipher.decrypt(it) } }
    }

    /** Change server or a deleted account: everything goes, the token key included. */
    suspend fun clearAll() {
        cached = null
        prefs.edit { it.clear() }
        withContext(Dispatchers.Default) { cipher.forgetKey() }
    }
}
