package ch.tomigorn.wgchores.auth

import okhttp3.HttpUrl.Companion.toHttpUrl

sealed interface SsoCallback {
    data class Code(val code: String) : SsoCallback
    data class Error(val code: String) : SsoCallback

    companion object {
        const val SCHEME = "wgchores"
        private const val HOST = "auth"

        fun parse(uri: String): SsoCallback? {
            val prefix = "$SCHEME://$HOST"
            if (!uri.startsWith(prefix)) return null
            val rest = uri.removePrefix(prefix)
            if (rest.isNotEmpty() && rest[0] != '?' && rest[0] != '/') return null
            // HttpUrl only parses http(s); the query is all that matters here
            val url = "https://$HOST$rest".toHttpUrl()
            url.queryParameter("error")?.let { return Error(it) }
            return url.queryParameter("code")?.takeIf { it.isNotEmpty() }?.let(::Code) ?: Error("sso_failed")
        }

        const val FALLBACK_ALIAS = "SsoCallback"

        /** The callback URI, only when Android delivered it through the fallback alias (never via the launcher activity). */
        fun fromIntent(component: String?, packageName: String, uri: String?): String? {
            if (component != "$packageName.$FALLBACK_ALIAS") return null
            return uri?.takeIf { parse(it) != null }
        }

        fun startUrl(server: String, challenge: String): String =
            server.toHttpUrl().newBuilder()
                .addPathSegments("auth/oidc/start")
                .addQueryParameter("client", "android")
                .addQueryParameter("challenge", challenge)
                .build()
                .toString()
    }
}
