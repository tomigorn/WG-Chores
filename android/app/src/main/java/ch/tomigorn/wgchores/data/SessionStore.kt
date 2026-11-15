package ch.tomigorn.wgchores.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

data class Session(
    val server: String? = null,
    val config: ServerConfig? = null,
    val token: String? = null, // leak-check: ok
    val userName: String? = null,
)

class SessionStore(private val prefs: DataStore<Preferences>, private val cipher: TokenCipher) {
    private object Keys {
        val server = stringPreferencesKey("server")
        val config = stringPreferencesKey("config")
        val token = stringPreferencesKey("token") // leak-check: ok
        val userName = stringPreferencesKey("user_name")
        val verifier = stringPreferencesKey("sso_verifier")
    }

    suspend fun read(): Session {
        val p = prefs.data.first()
        return Session(
            server = p[Keys.server],
            config = p[Keys.config]?.let(ServerConfig::parse),
            token = p[Keys.token]?.let(cipher::decrypt), // leak-check: ok
            userName = p[Keys.userName],
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

    suspend fun saveSignIn(bearer: String, userName: String) {
        val sealed = cipher.encrypt(bearer)
        prefs.edit {
            it[Keys.token] = sealed
            it[Keys.userName] = userName
        }
    }

    suspend fun clearSignIn() {
        prefs.edit {
            it.remove(Keys.token)
            it.remove(Keys.userName)
            it.remove(Keys.verifier)
        }
    }

    suspend fun saveVerifier(verifier: String) {
        val sealed = cipher.encrypt(verifier)
        prefs.edit { it[Keys.verifier] = sealed }
    }

    /** The pending SSO verifier, removed as it is read: a callback is accepted once. */
    suspend fun takeVerifier(): String? {
        var sealed: String? = null
        prefs.edit {
            sealed = it[Keys.verifier]
            it.remove(Keys.verifier)
        }
        return sealed?.let(cipher::decrypt)
    }

    suspend fun clearAll() {
        prefs.edit { it.clear() }
    }
}
