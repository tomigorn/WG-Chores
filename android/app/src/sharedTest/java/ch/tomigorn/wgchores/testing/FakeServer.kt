package ch.tomigorn.wgchores.testing

import java.io.Closeable
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers.Companion.headersOf

/** A small stand-in for the WG Chores server API, enough for sign-in flows. */
class FakeServer : Closeable {
    private val server = MockWebServer()

    var localAccounts = true
    var openRegistration = true
    var oidcName: String? = "Holy Grail"
    var minAppVersion = "0.0.1"
    var appName = "WG Chores"
    val pw = "correct horse battery"
    val issued: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val ssoCodes = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var nextToken = 1

    val url: String get() = server.url("/").toString().trimEnd('/')

    fun start(): FakeServer = apply {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return handle(request)
            }
        }
        server.start()
    }

    /** Registers a one-time SSO code bound to the challenge the app sent. */
    fun issueSsoCode(challenge: String): String = "code-${ssoCodes.size + 1}".also { ssoCodes[it] = challenge }

    fun lastRequest(path: String): RecordedRequest? = requests.lastOrNull { it.url.encodedPath == path }

    override fun close() = server.close()

    private val userJson = """{"id":1,"username":"anna","display_name":"Jane Doe","kind":"local","picture_url":null,"account_url":null}"""

    private fun json(code: Int, body: String) = MockResponse(code, headersOf("Content-Type", "application/json"), body)

    private fun error(code: Int, err: String) = json(code, """{"error":"$err","message":"x"}""")

    private fun body(r: RecordedRequest): JsonObject = Json.parseToJsonElement(r.body?.utf8() ?: "{}").jsonObject

    private fun bearer(r: RecordedRequest): String? = r.headers["Authorization"]?.removePrefix("Bearer ")?.takeIf { it in issued }

    private fun newToken(): String = "tok-${nextToken++}".also { issued += it }

    private fun handle(r: RecordedRequest): MockResponse {
        val path = r.url.encodedPath
        if (r.method == "POST" && r.headers["Authorization"] == null && r.headers["X-WGC"] != "1") return error(403, "csrf")
        return when ("${r.method} $path") {
            "GET /api/v1/config" -> json(
                200,
                """{"name":"$appName","local_accounts":$localAccounts,"open_registration":$openRegistration,""" +
                    """"oidc":${oidcName?.let { """{"name":"$it","account_url":null}""" } ?: "null"},""" +
                    """"notify_times":["14:00","19:00"],"tz":"Europe/Zurich","version":"1.0.0","min_app_version":"$minAppVersion"}""",
            )
            "POST /api/v1/auth/login" -> {
                val b = body(r)
                if (b["username"]?.jsonPrimitive?.content == "anna" && b["password"]?.jsonPrimitive?.content == pw) {
                    json(200, """{"token":"${newToken()}","user":$userJson}""")
                } else {
                    error(401, "invalid_credentials")
                }
            }
            "POST /api/v1/auth/register" ->
                if (openRegistration) json(201, """{"token":"${newToken()}","user":$userJson}""") else error(403, "registration_closed")
            "POST /api/v1/auth/app-token" -> {
                val b = body(r)
                val challenge = ssoCodes.remove(b["code"]?.jsonPrimitive?.content)
                val verifier = b["verifier"]?.jsonPrimitive?.content.orEmpty()
                val sent = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
                if (challenge != null && challenge == sent) json(200, """{"token":"${newToken()}"}""") else error(400, "invalid_code")
            }
            "GET /api/v1/me" -> if (bearer(r) != null) json(200, userJson) else error(401, "unauthenticated")
            "POST /api/v1/auth/logout" -> {
                val t = bearer(r) ?: return error(401, "unauthenticated")
                issued -= t
                MockResponse(204, headersOf(), "")
            }
            else -> error(404, "not_found")
        }
    }
}
