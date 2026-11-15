package ch.tomigorn.wgchores.notify

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Per account: the poll cursor (stored only after its answer was shown, which acknowledges it), the last check and the two switches. */
class NotifyStore(private val prefs: DataStore<Preferences>) {
    private fun cursorKey(account: String) = stringPreferencesKey("cursor|$account")
    private fun lastKey(account: String) = longPreferencesKey("last|$account")
    private fun doneKey(account: String) = booleanPreferencesKey("done|$account")
    private fun dueKey(account: String) = booleanPreferencesKey("due|$account")
    private val signatureKey = stringPreferencesKey("signature")
    private val askedKey = booleanPreferencesKey("asked")
    private val languageKey = stringPreferencesKey("language")

    suspend fun cursor(account: String): String? = prefs.data.first()[cursorKey(account)]

    suspend fun saveCursor(account: String, cursor: String?) {
        prefs.edit { if (cursor != null) it[cursorKey(account)] = cursor else it.remove(cursorKey(account)) }
    }

    suspend fun lastCheck(account: String): Long? = prefs.data.first()[lastKey(account)]

    suspend fun saveLastCheck(account: String, at: Long) {
        prefs.edit { it[lastKey(account)] = at }
    }

    suspend fun saveFlags(account: String, done: Boolean, due: Boolean) {
        prefs.edit {
            it[doneKey(account)] = done
            it[dueKey(account)] = due
        }
    }

    /** True unless the server said both switches are off; unknown counts as on (the poll filters anyway). */
    fun enabled(account: String): Flow<Boolean> =
        prefs.data.map { (it[doneKey(account)] ?: true) || (it[dueKey(account)] ?: true) }.distinctUntilChanged()

    suspend fun signature(): String? = prefs.data.first()[signatureKey]

    suspend fun saveSignature(s: String?) {
        prefs.edit { if (s != null) it[signatureKey] = s else it.remove(signatureKey) }
    }

    /** The app language picked in Settings ("system", "en", "de"); null before the first pick. */
    suspend fun language(): String? = prefs.data.first()[languageKey]

    suspend fun saveLanguage(tag: String) {
        prefs.edit { it[languageKey] = tag }
    }

    val asked: Flow<Boolean> = prefs.data.map { it[askedKey] ?: false }.distinctUntilChanged()

    suspend fun markAsked() {
        prefs.edit { it[askedKey] = true }
    }

    /** Signed out: a later sign-in is a new device session and starts without old events. */
    suspend fun forgetChecks() {
        prefs.edit { p ->
            p.asMap().keys.filter { it.name.startsWith("cursor|") || it.name.startsWith("last|") }.forEach { p.remove(it) }
        }
    }
}
