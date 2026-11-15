package ch.tomigorn.wgchores.domain

import ch.tomigorn.wgchores.api.ApiException
import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.coroutines.CancellationException

/** "Roughly how often" chips: the order of the web app; tapping the picked one unpicks it. */
object Intervals {
    val ORDER: List<String> = listOf("3d", "1w", "2w", "1m", "3m", "6m", "1y")

    fun toggle(current: String?, tapped: String): String? = if (current == tapped) null else tapped
}

sealed interface Expiry {
    data object Expired : Expiry
    data object Soon : Expiry
    data class Hours(val n: Int) : Expiry
    data class Days(val n: Int) : Expiry
}

enum class PasswordProblem { Short, Mismatch }

@kotlinx.serialization.Serializable
data class Category(val name: String, val chores: Int = 0)

object Rules {
    /** "expires in N days" of an invite, like the web app's expiresIn. */
    fun expiresIn(expiresAt: String, now: Instant): Expiry {
        val at = runCatching { OffsetDateTime.parse(expiresAt).toInstant() }.getOrNull() ?: return Expiry.Expired
        val ms = at.toEpochMilli() - now.toEpochMilli()
        if (ms <= 0) return Expiry.Expired
        val hours = (ms / 3_600_000).toInt()
        return when {
            hours < 1 -> Expiry.Soon
            hours < 24 -> Expiry.Hours(hours)
            else -> Expiry.Days(hours / 24)
        }
    }

    /** New password: at least 12 characters (code points), and the repeat must match. */
    fun passwordProblem(pw: String, repeat: String): PasswordProblem? = when {
        pw.codePointCount(0, pw.length) < 12 -> PasswordProblem.Short
        pw != repeat -> PasswordProblem.Mismatch
        else -> null
    }

    /** Delete account: SSO accounts type their display name, local accounts their password (checked by the server). */
    fun canConfirmDelete(kind: String, displayName: String, typed: String): Boolean =
        if (kind == "sso") typed.trim() == displayName else typed.isNotEmpty()

    /** The member directory is on when an empty search answers 200 or 400 (too short); 404 or any other failure = off. */
    suspend fun memberDirectory(search: suspend (String) -> Unit): Boolean = try {
        search("")
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e is ApiException && e.status == 400
    }

    /** Existing categories that fit what is typed, without the exact match. */
    fun categorySuggestions(typed: String, categories: List<Category>): List<String> {
        val q = typed.trim().lowercase()
        if (categories.any { it.name.lowercase() == q }) return emptyList()
        return categories.map { it.name }.filter { it.lowercase().contains(q) }
    }

    /** How many chores share [original]; renaming them all is offered when it is used more than once and the new name is not another category. */
    fun renameAllCount(original: String?, typed: String, categories: List<Category>): Int {
        val to = typed.trim()
        if (original == null || to.isEmpty() || to == original) return 0
        val same = { a: String, b: String -> a.lowercase() == b.lowercase() }
        if (categories.any { same(it.name, to) && !same(it.name, original) }) return 0
        val n = categories.find { same(it.name, original) }?.chores ?: 0
        return if (n > 1) n else 0
    }
}

sealed interface InviteLink {
    data class Ok(val token: String) : InviteLink // leak-check: ok
    data class OtherServer(val host: String) : InviteLink
    data object Invalid : InviteLink

    companion object {
        /** A pasted `https://<server>/invite/<token>` for [server]; links of other servers are refused. */
        fun parse(text: String, server: String): InviteLink {
            val raw = Regex("""https?://\S+""").find(text)?.value ?: return Invalid
            val uri = runCatching { URI(raw) }.getOrNull() ?: return Invalid
            val home = runCatching { URI(server) }.getOrNull() ?: return Invalid
            val token = Regex("""^/invite/([A-Za-z0-9_-]+)/?$""").find(uri.rawPath ?: "")?.groupValues?.get(1) ?: return Invalid // leak-check: ok
            val host = (uri.host ?: return Invalid).lowercase() + if (uri.port != -1) ":${uri.port}" else ""
            val sameScheme = uri.scheme.equals(home.scheme, ignoreCase = true)
            val sameHost = uri.host.equals(home.host, ignoreCase = true) && uri.port == home.port
            return if (sameScheme && sameHost) Ok(token) else OtherServer(host)
        }
    }
}
