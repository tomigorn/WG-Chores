package ch.tomigorn.wgchores.api

import ch.tomigorn.wgchores.data.ServerConfig
import ch.tomigorn.wgchores.data.outbox.CompletionApi
import ch.tomigorn.wgchores.domain.Category
import ch.tomigorn.wgchores.domain.Chore
import ch.tomigorn.wgchores.domain.CompletionPage
import ch.tomigorn.wgchores.domain.Device
import ch.tomigorn.wgchores.domain.Household
import ch.tomigorn.wgchores.domain.Invite
import ch.tomigorn.wgchores.domain.InviteAccepted
import ch.tomigorn.wgchores.domain.InvitePreview
import ch.tomigorn.wgchores.domain.Member
import ch.tomigorn.wgchores.domain.NewInvite
import ch.tomigorn.wgchores.notify.PollAnswer
import java.net.URLEncoder
import kotlinx.serialization.builtins.ListSerializer
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

@Serializable
data class User(
    val id: Long,
    val username: String? = null,
    @SerialName("display_name") val displayName: String,
    val kind: String = "",
    @SerialName("picture_url") val pictureUrl: String? = null,
    @SerialName("account_url") val accountUrl: String? = null,
    @SerialName("last_household_id") val lastHouseholdId: Long? = null,
    @SerialName("notify_due") val notifyDue: Boolean = false,
    @SerialName("notify_done") val notifyDone: Boolean = false,
)

@Serializable
data class AuthResult(val token: String, val user: User? = null) // leak-check: ok

class ApiException(val status: Int, val code: String, message: String = code) : Exception(message)

/** No network, no answer in time, or a gateway in front of the server failed. */
class OfflineException(cause: Throwable? = null) : IOException("offline", cause)

class NotWgChoresException : Exception("not a WG Chores server")

class ApiClient(
    private val baseUrl: String,
    private val bearer: suspend () -> String? = { null },
    private val onUnauthenticated: suspend () -> Unit = {},
    private val http: OkHttpClient = defaultHttp,
) : CompletionApi {
    suspend fun login(username: String, pw: String, deviceName: String): AuthResult =
        post("/api/v1/auth/login", AuthResult.serializer(), signedIn = false) {
            put("username", username)
            put("password", pw)
            put("client", "android")
            put("device_name", deviceName)
        }

    suspend fun register(username: String, pw: String, displayName: String, deviceName: String): AuthResult =
        post("/api/v1/auth/register", AuthResult.serializer(), signedIn = false) {
            put("username", username)
            put("password", pw)
            put("display_name", displayName)
            put("client", "android")
            put("device_name", deviceName)
        }

    suspend fun appToken(code: String, verifier: String, deviceName: String): String =
        post("/api/v1/auth/app-token", JsonObject.serializer(), signedIn = false) {
            put("code", code)
            put("verifier", verifier)
            put("device_name", deviceName)
        }["token"]?.jsonPrimitive?.content ?: throw ApiException(200, "invalid_response")

    suspend fun me(): User = call(Request.Builder().url(baseUrl + "/api/v1/me").get(), User.serializer(), signedIn = true)
        ?: throw ApiException(204, "invalid_response")

    suspend fun households(): List<Household> = get("/api/v1/households", ListSerializer(Household.serializer()))

    suspend fun household(id: Long): Household = get("/api/v1/households/$id", Household.serializer())

    suspend fun chores(householdId: Long): List<Chore> = get("/api/v1/households/$householdId/chores", ListSerializer(Chore.serializer()))

    suspend fun createHousehold(name: String): Household =
        post("/api/v1/households", Household.serializer(), signedIn = true) { put("name", name) }

    suspend fun setLastHousehold(id: Long) {
        send("PUT", "/api/v1/me/last-household", buildJsonObject { put("id", id) })
    }

    suspend fun chore(id: Long): Chore = get("/api/v1/chores/$id", Chore.serializer())

    suspend fun archivedChores(householdId: Long): List<Chore> =
        get("/api/v1/households/$householdId/chores?archived=true", ListSerializer(Chore.serializer()))

    suspend fun history(choreId: Long, before: String? = null, limit: Int = 20): CompletionPage =
        get("/api/v1/chores/$choreId/completions?limit=$limit" + (before?.let { "&before=" + enc(it) } ?: ""), CompletionPage.serializer())

    suspend fun createChore(householdId: Long, name: String, category: String?, interval: String?): Chore =
        request("POST", "/api/v1/households/$householdId/chores", Chore.serializer(), buildJsonObject {
            put("name", name)
            put("category", category)
            put("interval", interval)
        })

    suspend fun patchChore(id: Long, name: String, category: String?, interval: String?): Chore =
        request("PATCH", "/api/v1/chores/$id", Chore.serializer(), buildJsonObject {
            put("name", name)
            put("category", category)
            put("interval", interval)
        })

    suspend fun archiveChore(id: Long): Chore = request("POST", "/api/v1/chores/$id/archive", Chore.serializer(), null)

    suspend fun restoreChore(id: Long): Chore = request("POST", "/api/v1/chores/$id/restore", Chore.serializer(), null)

    suspend fun categories(householdId: Long): List<Category> =
        get("/api/v1/households/$householdId/categories", ListSerializer(Category.serializer()))

    suspend fun renameCategory(householdId: Long, from: String, to: String): List<Category> =
        request("PATCH", "/api/v1/households/$householdId/categories", ListSerializer(Category.serializer()), buildJsonObject {
            put("from", from)
            put("to", to)
        })

    suspend fun renameHousehold(id: Long, name: String): Household =
        request("PATCH", "/api/v1/households/$id", Household.serializer(), buildJsonObject { put("name", name) })

    suspend fun leaveHousehold(id: Long, confirmDelete: Boolean) {
        send("POST", "/api/v1/households/$id/leave", buildJsonObject { if (confirmDelete) put("confirm_delete", true) })
    }

    suspend fun members(householdId: Long): List<Member> = get("/api/v1/households/$householdId/members", ListSerializer(Member.serializer()))

    suspend fun addMember(householdId: Long, username: String? = null, userId: Long? = null): Member =
        request("POST", "/api/v1/households/$householdId/members", Member.serializer(), buildJsonObject {
            if (userId != null) put("user_id", userId) else put("username", username)
        })

    suspend fun removeMember(householdId: Long, userId: Long) {
        send("DELETE", "/api/v1/households/$householdId/members/$userId", buildJsonObject {})
    }

    suspend fun searchUsers(q: String): List<Member> = get("/api/v1/users?q=" + enc(q), ListSerializer(Member.serializer()))

    suspend fun invites(householdId: Long): List<Invite> = get("/api/v1/households/$householdId/invites", ListSerializer(Invite.serializer()))

    suspend fun createInvite(householdId: Long): NewInvite = request("POST", "/api/v1/households/$householdId/invites", NewInvite.serializer(), null)

    suspend fun cancelInvite(householdId: Long, inviteId: Long) {
        call<Unit>(Request.Builder().url("$baseUrl/api/v1/households/$householdId/invites/$inviteId").delete(), null, signedIn = true)
    }

    suspend fun invitePreview(token: String): InvitePreview = get("/api/v1/invites/" + enc(token), InvitePreview.serializer()) // leak-check: ok

    suspend fun acceptInvite(token: String): InviteAccepted = request("POST", "/api/v1/invites/" + enc(token) + "/accept", InviteAccepted.serializer(), null) // leak-check: ok

    suspend fun patchMe(body: JsonObject): User = request("PATCH", "/api/v1/me", User.serializer(), body)

    /** Local accounts confirm with their password, SSO accounts with their display name. */
    suspend fun deleteMe(currentPassword: String? = null, confirmUsername: String? = null) {
        send("DELETE", "/api/v1/me", buildJsonObject {
            if (currentPassword != null) put("current_password", currentPassword)
            if (confirmUsername != null) put("confirm_username", confirmUsername)
        })
    }

    suspend fun devices(): List<Device> = get("/api/v1/me/devices", ListSerializer(Device.serializer()))

    /** The background check: done by others since [since] and chores newly due; presenting a cursor acknowledges the due notices it answered. */
    suspend fun poll(since: String?): PollAnswer =
        get("/api/v1/notifications/poll" + (since?.let { "?since=" + enc(it) } ?: ""), PollAnswer.serializer())

    suspend fun deleteDevice(id: String) {
        call<Unit>(Request.Builder().url("$baseUrl/api/v1/me/devices/" + enc(id)).delete(), null, signedIn = true)
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private suspend fun <T> request(method: String, path: String, serializer: KSerializer<T>, body: JsonObject?): T =
        call(Request.Builder().url(baseUrl + path).method(method, (body ?: JsonObject(emptyMap())).toString().toRequestBody(JSON_TYPE)), serializer, signedIn = true)
            ?: throw ApiException(204, "invalid_response")

    override suspend fun complete(choreId: Long, uuid: String, doneOn: String, note: String?) {
        send("POST", "/api/v1/chores/$choreId/completions", buildJsonObject {
            put("id", uuid)
            put("done_on", doneOn)
            put("note", note)
        })
    }

    override suspend fun patchCompletion(uuid: String, doneOn: String, note: String?) {
        send("PATCH", "/api/v1/completions/$uuid", buildJsonObject {
            put("done_on", doneOn)
            put("note", note)
        })
    }

    override suspend fun deleteCompletion(uuid: String) {
        call<Unit>(Request.Builder().url("$baseUrl/api/v1/completions/$uuid").delete(), null, signedIn = true)
    }

    private suspend fun <T> get(path: String, serializer: KSerializer<T>): T =
        call(Request.Builder().url(baseUrl + path).get(), serializer, signedIn = true) ?: throw ApiException(204, "invalid_response")

    private suspend fun send(method: String, path: String, body: JsonObject) {
        call<Unit>(Request.Builder().url(baseUrl + path).method(method, body.toString().toRequestBody(JSON_TYPE)), null, signedIn = true)
    }

    suspend fun logout() {
        call<Unit>(Request.Builder().url(baseUrl + "/api/v1/auth/logout").post(EMPTY_JSON), null, signedIn = true)
    }

    private suspend fun <T> post(
        path: String,
        serializer: KSerializer<T>,
        signedIn: Boolean,
        body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): T {
        val json = buildJsonObject(body).toString().toRequestBody(JSON_TYPE)
        return call(Request.Builder().url(baseUrl + path).post(json), serializer, signedIn)
            ?: throw ApiException(204, "invalid_response")
    }

    private suspend fun <T> call(builder: Request.Builder, serializer: KSerializer<T>?, signedIn: Boolean): T? {
        if (signedIn) {
            bearer()?.let { builder.header("Authorization", "Bearer $it") }
        } else {
            builder.header("X-WGC", "1")
        }
        val result = runCatching { execute(http, builder.build()) { it.readResult(serializer) } }
        val error = result.exceptionOrNull()
        if (signedIn && error is ApiException && error.status == 401 && error.code == "unauthenticated") onUnauthenticated()
        return result.getOrThrow()
    }

    companion object {
        private const val MAX_CONFIG_BYTES = 64L * 1024
        private const val MAX_BODY_BYTES = 4L * 1024 * 1024
        private val JSON_TYPE = "application/json".toMediaType()
        private val EMPTY_JSON = "{}".toRequestBody(JSON_TYPE)
        internal val json = Json { ignoreUnknownKeys = true }

        val defaultHttp: OkHttpClient = OkHttpClient.Builder()
            .callTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()

        private val probeHttp: OkHttpClient = defaultHttp.newBuilder().callTimeout(5, TimeUnit.SECONDS).build()

        /** GET /api/v1/config; anything that is not a WG Chores server is [NotWgChoresException]. */
        suspend fun probe(baseUrl: String, http: OkHttpClient = probeHttp): ServerConfig {
            val request = Request.Builder().url("$baseUrl/api/v1/config").header("Accept", "application/json").build()
            return execute(http, request) { r ->
                if (!r.isSuccessful || !r.isJson()) throw NotWgChoresException()
                val source = r.body.source()
                if (source.request(MAX_CONFIG_BYTES + 1)) throw NotWgChoresException()
                ServerConfig.parse(source.buffer.readUtf8()) ?: throw NotWgChoresException()
            }
        }

        private suspend fun <T> execute(http: OkHttpClient, request: Request, read: (Response) -> T): T =
            withContext(Dispatchers.IO) {
                val response = try {
                    http.newCall(request).execute()
                } catch (e: IOException) {
                    throw OfflineException(e)
                }
                try {
                    response.use(read)
                } catch (e: OfflineException) {
                    throw e
                } catch (e: IOException) {
                    throw OfflineException(e)
                }
            }

        private fun Response.isJson(): Boolean = body.contentType()?.let { it.type == "application" && it.subtype == "json" } == true

        private fun <T> Response.readResult(serializer: KSerializer<T>?): T? {
            val text = try {
                val source = body.source()
                if (source.request(MAX_BODY_BYTES + 1)) null else source.buffer.readUtf8()
            } catch (e: IOException) {
                throw OfflineException(e)
            }
            if (text == null) throw ApiException(code, if (isSuccessful) "invalid_response" else "http_$code")
            val isJson = isJson()
            if (isSuccessful) {
                if (serializer == null || code == 204) return null
                if (!isJson) throw ApiException(code, "invalid_response")
                return runCatching { json.decodeFromString(serializer, text) }.getOrElse { throw ApiException(code, "invalid_response") }
            }
            if (!isJson && code in 502..504) throw OfflineException()
            val err = if (isJson) runCatching { json.decodeFromString(ErrorBody.serializer(), text) }.getOrNull() else null
            throw ApiException(code, err?.error ?: "http_$code", err?.message ?: message)
        }
    }

    @Serializable
    private data class ErrorBody(val error: String, val message: String = "")
}
