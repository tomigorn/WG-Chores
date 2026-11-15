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
import ch.tomigorn.wgchores.domain.By
import ch.tomigorn.wgchores.domain.Chore
import ch.tomigorn.wgchores.domain.Chores
import ch.tomigorn.wgchores.domain.Days
import ch.tomigorn.wgchores.domain.Due
import ch.tomigorn.wgchores.domain.Household
import java.time.LocalDate
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.contentOrNull

/** A small stand-in for the WG Chores server API: sign-in, households, chores and completions. */
class FakeServer : Closeable {
    private var server = MockWebServer()
    private var port = 0

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

    private var base = ""
    val url: String get() = base

    /** Every request answers like a gateway with the server gone (the app sees offline). */
    @Volatile var down = false
    val tz = "Europe/Zurich"
    val today: LocalDate get() = Days.today(tz)
    @Volatile var lastHousehold: Long? = 1
    val households = java.util.concurrent.CopyOnWriteArrayList(listOf(Household(1, "WG Zürich", 2), Household(2, "Family", 3)))
    val chores = java.util.concurrent.CopyOnWriteArrayList<Chore>()
    /** uuid → completion; the latest per chore decides days since. */
    val completions = java.util.concurrent.ConcurrentHashMap<String, Done>()

    data class Done(val choreId: Long, val doneOn: String, val note: String?, val by: By)

    val me = By(1, "Jane Doe")
    val sam = By(2, "Sam")

    init {
        seed()
    }

    fun seed() {
        chores.clear()
        completions.clear()
        fun add(id: Long, name: String, cat: String?, iv: String?, ago: Int?, by: By) {
            chores += Chore(id = id, householdId = 1, name = name, category = cat, interval = iv)
            if (ago != null) completions["seed-$id"] = Done(id, today.minusDays(ago.toLong()).toString(), null, by)
        }
        add(1, "Clean shower", "Bathroom", "1w", 11, sam)
        add(2, "Clean toilet", "Bathroom", "1w", 26, me)
        add(3, "Restock toilet paper", "Bathroom", "2w", 1, sam)
        add(4, "Clean fridge", "Kitchen", "1m", null, me)
        add(5, "Descale kettle", "Kitchen", "1m", 13, me)
        add(6, "Wipe stove", "Kitchen", "3d", 0, sam)
        add(7, "Water balcony plants", "Plants", "1w", 2, me)
        add(8, "Water living room plants", "Plants", "1w", 5, sam)
    }

    /** Completions the app created (not the seeded ones). */
    fun created(): Map<String, Done> = completions.filterKeys { !it.startsWith("seed-") }

    fun start(): FakeServer = apply {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return handle(request)
            }
        }
        if (port == 0) {
            server.start()
            port = server.port
            base = server.url("/").toString().trimEnd('/')
        } else {
            server.start(port)
        }
    }

    /** Shuts the server down; [restart] brings it back on the same port with the same data. */
    fun stop() = server.close()

    fun restart(): FakeServer {
        server = MockWebServer()
        return start()
    }

    /** A bearer the fake accepts, for tests that skip the sign-in screens. */
    fun token(): String = newToken()

    /** Registers a one-time SSO code bound to the challenge the app sent. */
    fun issueSsoCode(challenge: String): String = "code-${ssoCodes.size + 1}".also { ssoCodes[it] = challenge }

    fun lastRequest(path: String): RecordedRequest? = requests.lastOrNull { it.url.encodedPath == path }

    override fun close() = server.close()

    private val userJson = """{"id":1,"username":"anna","display_name":"Jane Doe","kind":"local","picture_url":null,"account_url":null}"""

    private val open = setOf("/api/v1/config", "/api/v1/auth/login", "/api/v1/auth/register", "/api/v1/auth/app-token")

    private fun completionJson(id: String, d: Done) =
        """{"id":"$id","done_on":"${d.doneOn}","note":${d.note?.let { Json.encodeToString(String.serializer(), it) } ?: "null"},""" +
            """"by":${Json.encodeToString(By.serializer(), d.by)},"created_at":"2026-11-14T10:00:00Z"}"""

    fun choresOf(hid: Long): List<Chore> = chores.filter { it.householdId == hid }.map { c ->
        val last = completions.values.filter { it.choreId == c.id }.maxByOrNull { it.doneOn }
        if (last == null) c.copy(last = null, daysSince = null, dueLevel = Due.NEVER, daysOverdue = 0)
        else Chores.applyCompletion(c.copy(last = null), last.doneOn, last.by, today)
    }

    private fun json(code: Int, body: String) = MockResponse(code, headersOf("Content-Type", "application/json"), body)

    private fun error(code: Int, err: String) = json(code, """{"error":"$err","message":"x"}""")

    private fun body(r: RecordedRequest): JsonObject = Json.parseToJsonElement(r.body?.utf8() ?: "{}").jsonObject

    private fun bearer(r: RecordedRequest): String? = r.headers["Authorization"]?.removePrefix("Bearer ")?.takeIf { it in issued }

    private fun newToken(): String = "tok-${nextToken++}".also { issued += it }

    private fun handle(r: RecordedRequest): MockResponse {
        val path = r.url.encodedPath
        if (down) return MockResponse(503, headersOf("Content-Type", "text/html"), "<h1>down</h1>")
        if (r.method == "POST" && r.headers["Authorization"] == null && r.headers["X-WGC"] != "1") return error(403, "csrf")
        if (path.startsWith("/api/v1/") && path !in open && bearer(r) == null) return error(401, "unauthenticated")
        Regex("/api/v1/households/(\\d+)").matchEntire(path)?.let { m ->
            val h = households.find { it.id == m.groupValues[1].toLong() } ?: return error(404, "not_found")
            return json(200, Json.encodeToString(Household.serializer(), h))
        }
        Regex("/api/v1/households/(\\d+)/chores").matchEntire(path)?.let { m ->
            val hid = m.groupValues[1].toLong()
            if (households.none { it.id == hid }) return error(404, "not_found")
            return json(200, Json.encodeToString(ListSerializer(Chore.serializer()), choresOf(hid)))
        }
        Regex("/api/v1/chores/(\\d+)/completions").matchEntire(path)?.let { m ->
            if (r.method != "POST") return error(405, "method")
            val cid = m.groupValues[1].toLong()
            if (chores.none { it.id == cid }) return error(404, "not_found")
            val b = body(r)
            val id = b["id"]?.jsonPrimitive?.content ?: return error(400, "invalid_id")
            val doneOn = b["done_on"]?.jsonPrimitive?.contentOrNull ?: today.toString()
            if (doneOn > today.toString()) return error(400, "invalid_date")
            val note = b["note"]?.jsonPrimitive?.contentOrNull
            val old = completions[id]
            if (old != null) return if (old.choreId == cid) json(200, completionJson(id, old)) else error(409, "conflict")
            val d = Done(cid, doneOn, note, me)
            completions[id] = d
            return json(201, completionJson(id, d))
        }
        Regex("/api/v1/completions/([^/]+)").matchEntire(path)?.let { m ->
            val id = m.groupValues[1]
            val old = completions[id] ?: return error(404, "not_found")
            return when (r.method) {
                "DELETE" -> {
                    completions.remove(id)
                    MockResponse(204, headersOf(), "")
                }
                "PATCH" -> {
                    val b = body(r)
                    val d = old.copy(doneOn = b["done_on"]?.jsonPrimitive?.content ?: old.doneOn, note = b["note"]?.jsonPrimitive?.contentOrNull)
                    completions[id] = d
                    json(200, completionJson(id, d))
                }
                else -> error(405, "method")
            }
        }
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
            "GET /api/v1/me" -> json(200, userJson.dropLast(1) + ""","last_household_id":${lastHousehold ?: "null"}}""")
            "GET /api/v1/households" -> json(200, Json.encodeToString(ListSerializer(Household.serializer()), households))
            "POST /api/v1/households" -> {
                val h = Household((households.maxOfOrNull { it.id } ?: 0) + 1, body(r)["name"]!!.jsonPrimitive.content, 1)
                households += h
                json(201, Json.encodeToString(Household.serializer(), h))
            }
            "PUT /api/v1/me/last-household" -> {
                lastHousehold = body(r)["id"]!!.jsonPrimitive.content.toLong()
                MockResponse(204, headersOf(), "")
            }
            "POST /api/v1/auth/logout" -> {
                val t = bearer(r) ?: return error(401, "unauthenticated")
                issued -= t
                MockResponse(204, headersOf(), "")
            }
            else -> error(404, "not_found")
        }
    }
}
