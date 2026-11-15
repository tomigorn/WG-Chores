package ch.tomigorn.wgchores.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

sealed interface ServerUrlResult {
    data class Ok(val url: String) : ServerUrlResult
    data object Empty : ServerUrlResult
    data object Invalid : ServerUrlResult
    data object NotHttps : ServerUrlResult
}

object ServerUrl {
    private val localHosts = setOf("localhost", "127.0.0.1", "10.0.2.2")
    private val hostPattern = Regex("[a-z0-9.-]+")

    fun normalize(input: String, allowLocalHttp: Boolean = false): ServerUrlResult {
        val text = input.trim()
        if (text.isEmpty() || text.equals("https://", ignoreCase = true)) return ServerUrlResult.Empty
        if (text.any { it.isWhitespace() }) return ServerUrlResult.Invalid
        val scheme = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://").find(text)?.groupValues?.get(1)?.lowercase()
        val withScheme = when (scheme) {
            null -> "https://$text"
            "https", "http" -> text
            else -> return ServerUrlResult.Invalid
        }
        val url = withScheme.toHttpUrlOrNull() ?: return ServerUrlResult.Invalid
        if (url.query != null || url.fragment != null || withScheme.contains('#') || withScheme.contains('?')) return ServerUrlResult.Invalid
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return ServerUrlResult.Invalid
        val local = url.host in localHosts
        if (!local && (!url.host.contains('.') || !url.host.matches(hostPattern))) return ServerUrlResult.Invalid
        if (url.scheme == "http" && !(allowLocalHttp && local)) return ServerUrlResult.NotHttps
        val path = url.encodedPath.trimEnd('/')
        val port = if (url.port == okhttp3.HttpUrl.defaultPort(url.scheme)) "" else ":${url.port}"
        return ServerUrlResult.Ok("${url.scheme}://${url.host}$port$path")
    }
}
