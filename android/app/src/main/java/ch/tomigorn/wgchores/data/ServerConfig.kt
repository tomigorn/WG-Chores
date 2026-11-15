package ch.tomigorn.wgchores.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ServerConfig(
    val name: String,
    @SerialName("local_accounts") val localAccounts: Boolean,
    @SerialName("open_registration") val openRegistration: Boolean,
    val oidc: Oidc? = null,
    @SerialName("notify_times") val notifyTimes: List<String> = emptyList(),
    val tz: String = "UTC",
    val version: String = "",
    @SerialName("min_app_version") val minAppVersion: String,
) {
    @Serializable
    data class Oidc(val name: String, @SerialName("account_url") val accountUrl: String? = null)

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        const val APP_NAME = "WG Chores"
        private val json = Json { ignoreUnknownKeys = true }

        /** Null unless [body] is the public config of a WG Chores server. */
        fun parse(body: String): ServerConfig? =
            runCatching { json.decodeFromString(serializer(), body) }.getOrNull()?.takeIf { it.name == APP_NAME }
    }
}

object AppVersion {
    private fun parts(v: String): List<Int>? =
        v.substringBefore('-').split('.').map { it.toIntOrNull() ?: return null }.takeIf { it.isNotEmpty() }

    fun isOlder(app: String, min: String): Boolean {
        val a = parts(app) ?: return false
        val m = parts(min) ?: return false
        for (i in 0 until maxOf(a.size, m.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = m.getOrElse(i) { 0 }
            if (x != y) return x < y
        }
        return false
    }
}
