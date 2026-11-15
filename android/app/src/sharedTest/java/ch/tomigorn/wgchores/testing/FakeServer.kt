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
import ch.tomigorn.wgchores.domain.Device
import ch.tomigorn.wgchores.domain.Member
import ch.tomigorn.wgchores.domain.Category
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

    data class FakeInvite(val id: Long, val hid: Long, val token: String, val by: By, val expiresAt: String, var open: Boolean = true) // leak-check: ok

    val members = java.util.concurrent.ConcurrentHashMap<Long, MutableList<Member>>()
    val users = listOf(Member(1, "Jane Doe", "anna"), Member(2, "Sam", "sam"), Member(3, "Lena Example", "lena"), Member(4, "Max Example", "max"))
    val invites = CopyOnWriteArrayList<FakeInvite>()
    val devices = CopyOnWriteArrayList<Device>()
    @Volatile var memberDirectory = true
    @Volatile var refuseDeletes = false
    @Volatile var refuseRename = false
    @Volatile var deleted = false
    @Volatile var notifyDone = true
    @Volatile var notifyDue = true
    @Volatile var displayName = "Jane Doe"
    @Volatile var kind = "local"

    data class PollDone(val seq: Long, val hid: Long, val choreId: Long, val by: String, val doneOn: String)

    /** Done events of the other members, for the background check. */
    val pollDone = CopyOnWriteArrayList<PollDone>()
    /** Chores newly due that this device has not acknowledged yet. */
    val dueChores = CopyOnWriteArrayList<Long>()
    private val offered = java.util.concurrent.ConcurrentHashMap<Long, Int>()
    @Volatile var pollPage = 50
    /** The next n polls are handled but their answers are lost on the way (the app sees offline). */
    @Volatile var dropPollAnswers = 0
    /** Poll numbers (1 = the first poll) whose answers are lost. */
    @Volatile var dropPolls = emptySet<Int>()
    private var polls = 0

    fun doneBy(by: String, choreId: Long) = synchronized(pollDone) {
        val c = chores.first { it.id == choreId }
        pollDone += PollDone((pollDone.maxOfOrNull { it.seq } ?: 0) + 1, c.householdId, choreId, by, today.toString())
    }

    private fun householdName(id: Long) = households.find { it.id == id }?.name ?: "Household $id"

    private fun str(s: String) = Json.encodeToString(String.serializer(), s)

    /** Like the server: no done events without a cursor, due notices acknowledged by the next cursor. */
    private fun poll(r: RecordedRequest): MockResponse = synchronized(pollDone) {
        val raw = r.url.queryParameter("since")
        val since = raw?.let { Regex("c(\\d+)\\.(\\d+)").matchEntire(it) ?: return error(400, "invalid_cursor") }
            ?.let { it.groupValues[1].toLong() to it.groupValues[2].toInt() }
        if (since != null) {
            val acked = offered.filterValues { it == since.second }.keys
            dueChores.removeAll(acked)
            acked.forEach { offered.remove(it) }
        }
        val poll = ++polls
        val max = pollDone.maxOfOrNull { it.seq } ?: 0
        var cursorSeq = max
        var more = false
        var done = emptyList<PollDone>()
        if (since != null) {
            val after = pollDone.filter { it.seq > since.first }.sortedBy { it.seq }
            val page = after.take(pollPage)
            more = after.size > pollPage
            cursorSeq = if (more) page.last().seq else maxOf(max, since.first)
            if (notifyDone) done = page
        }
        val due = if (!notifyDue) emptyMap<Long, List<Chore>>() else dueChores.mapNotNull { id -> chores.find { it.id == id } }.groupBy { it.householdId }
        due.values.flatten().forEach { offered[it.id] = poll }
        val doneJson = done.joinToString(",") { d ->
            """{"household":{"id":${d.hid},"name":${str(householdName(d.hid))}},"chore":{"id":${d.choreId},"name":${str(chores.first { it.id == d.choreId }.name)}},"by":${str(d.by)},"done_on":"${d.doneOn}"}"""
        }
        val dueJson = due.entries.joinToString(",") { (hid, list) ->
            """{"household":{"id":$hid,"name":${str(householdName(hid))}},"chores":[${list.joinToString(",") { """{"id":${it.id},"name":${str(it.name)}}""" }}]}"""
        }
        if (dropPollAnswers > 0 || poll in dropPolls) {
            if (poll !in dropPolls) dropPollAnswers--
            return MockResponse(503, headersOf("Content-Type", "text/html"), "<h1>lost</h1>")
        }
        json(200, """{"cursor":"c$cursorSeq.$poll","done":[$doneJson],"due":[$dueJson],"more":$more}""")
    }

    init {
        seed()
    }

    fun seed() {
        chores.clear()
        completions.clear()
        members.clear()
        members[1] = mutableListOf(users[0], users[1])
        members[2] = mutableListOf(users[0], users[2], users[3])
        invites.clear()
        invites += FakeInvite(1, 1, "open-1", sam, java.time.Instant.now().plusSeconds(5 * 86400 + 3600).toString())
        invites += FakeInvite(2, 9, "join-me", sam, java.time.Instant.now().plusSeconds(6 * 86400 + 3600).toString())
        devices.clear()
        devices += Device("d-this", "android", "Pixel 9", "2026-11-14T10:00:00Z", current = true)
        devices += Device("d-web", "web", null, "2026-11-10T10:00:00Z")
        devices += Device("d-tab", "android", "Tablet", "2026-11-01T10:00:00Z")
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

    /** [n] older completions of chore [id], one every 7 days before its seeded one. */
    fun seedHistory(id: Long, n: Int) {
        val first = completions["seed-$id"]?.doneOn?.let(LocalDate::parse) ?: today
        for (i in 1..n) completions["hist-$id-$i"] = Done(id, first.minusDays(7L * i).toString(), if (i == 1) "used the new cleaner" else null, if (i % 2 == 0) me else sam)
    }

    /** Completions the app created (not the seeded ones). */
    fun created(): Map<String, Done> = completions.filterKeys { !it.startsWith("seed-") && !it.startsWith("hist-") }

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

    private val userJson get() = """{"id":1,"username":"anna","display_name":"$displayName","kind":"$kind","picture_url":null,"account_url":null,"notify_due":$notifyDue,"notify_done":$notifyDone}"""

    private val open = setOf("/api/v1/config", "/api/v1/auth/login", "/api/v1/auth/register", "/api/v1/auth/app-token")

    private fun completionJson(id: String, d: Done) =
        """{"id":"$id","done_on":"${d.doneOn}","note":${d.note?.let { Json.encodeToString(String.serializer(), it) } ?: "null"},""" +
            """"by":${Json.encodeToString(By.serializer(), d.by)},"created_at":"2026-11-14T10:00:00Z"}"""

    fun choresOf(hid: Long, archived: Boolean = false): List<Chore> = chores.filter { it.householdId == hid && it.archived == archived }.map { c -> withLast(c) }

    private fun withLast(c: Chore): Chore {
        val last = completions.values.filter { it.choreId == c.id }.maxByOrNull { it.doneOn }
        return if (last == null) c.copy(last = null, daysSince = null, dueLevel = Due.NEVER, daysOverdue = 0)
        else Chores.applyCompletion(c.copy(last = null), last.doneOn, last.by, today)
    }

    private fun noContent() = MockResponse(204, headersOf(), "")

    private fun <T> list(ser: kotlinx.serialization.KSerializer<T>, items: List<T>) = json(200, Json.encodeToString(ListSerializer(ser), items))

    private fun categoriesOf(hid: Long): List<Category> =
        chores.filter { it.householdId == hid && !it.archived && it.category != null }.groupBy { it.category!! }.map { (k, v) -> Category(k, v.size) }.sortedBy { it.name }

    private fun inviteJson(i: FakeInvite) = """{"id":${i.id},"created_by":{"id":${i.by.id},"display_name":"${i.by.displayName}"},"created_at":"2026-11-14T10:00:00Z","expires_at":"${i.expiresAt}"}"""

    /** Chore page, editing, household settings, invites, account and devices. */
    private fun extra(r: RecordedRequest, path: String): MockResponse? {
        val m = r.method
        Regex("/api/v1/chores/(\\d+)").matchEntire(path)?.let { g ->
            val c = chores.find { it.id == g.groupValues[1].toLong() } ?: return error(404, "not_found")
            if (m == "PATCH") {
                val b = body(r)
                val name = b["name"]?.jsonPrimitive?.content?.trim() ?: c.name
                if (name.isEmpty()) return error(400, "invalid_name")
                val u = c.copy(name = name, category = if ("category" in b) b["category"]?.jsonPrimitive?.contentOrNull else c.category, interval = if ("interval" in b) b["interval"]?.jsonPrimitive?.contentOrNull else c.interval)
                chores[chores.indexOf(c)] = u
                return json(200, Json.encodeToString(Chore.serializer(), withLast(u)))
            }
            val hist = completions.values.filter { it.choreId == c.id }.map { it.doneOn }.sortedDescending()
            return json(200, Json.encodeToString(Chore.serializer(), withLast(c).copy(usualInterval = ch.tomigorn.wgchores.domain.Due.usualInterval(hist))))
        }
        Regex("/api/v1/chores/(\\d+)/(archive|restore)").matchEntire(path)?.let { g ->
            val c = chores.find { it.id == g.groupValues[1].toLong() } ?: return error(404, "not_found")
            val u = c.copy(archived = g.groupValues[2] == "archive")
            chores[chores.indexOf(c)] = u
            return json(200, Json.encodeToString(Chore.serializer(), withLast(u)))
        }
        if (m == "GET") Regex("/api/v1/chores/(\\d+)/completions").matchEntire(path)?.let { g ->
            val cid = g.groupValues[1].toLong()
            val limit = r.url.queryParameter("limit")?.toInt() ?: 30
            val all = completions.entries.filter { it.value.choreId == cid }.sortedWith(compareByDescending<Map.Entry<String, Done>> { it.value.doneOn }.thenByDescending { it.key })
            val before = r.url.queryParameter("before")
            val from = if (before == null) 0 else all.indexOfFirst { "${it.value.doneOn},${it.key}" == before } + 1
            val page = all.drop(from).take(limit)
            val next = if (from + limit < all.size) page.last().let { "\"${it.value.doneOn},${it.key}\"" } else "null"
            return json(200, """{"items":[${page.joinToString(",") { completionJson(it.key, it.value) }}],"next":$next}""")
        }
        Regex("/api/v1/households/(\\d+)/(categories|members|invites|leave)").matchEntire(path)?.let { g ->
            val hid = g.groupValues[1].toLong()
            if (households.none { it.id == hid }) return error(404, "not_found")
            val mem = members.getOrPut(hid) { mutableListOf(users[0]) }
            return when ("$m ${g.groupValues[2]}") {
                "GET categories" -> list(Category.serializer(), categoriesOf(hid))
                "PATCH categories" -> {
                    if (refuseRename) return error(500, "http_500")
                    val b = body(r)
                    val from = b["from"]!!.jsonPrimitive.content
                    val to = b["to"]!!.jsonPrimitive.content
                    chores.toList().forEach { c -> if (c.householdId == hid && c.category.equals(from, true)) chores[chores.indexOf(c)] = c.copy(category = to) }
                    list(Category.serializer(), categoriesOf(hid))
                }
                "GET members" -> list(Member.serializer(), mem)
                "POST members" -> {
                    val b = body(r)
                    val u = b["user_id"]?.jsonPrimitive?.content?.toLong()?.let { id -> users.find { it.id == id } }
                        ?: b["username"]?.jsonPrimitive?.content?.let { n -> users.find { it.username == n.trim() } }
                        ?: return error(404, "user_not_found")
                    if (mem.any { it.id == u.id }) return error(409, "already_member")
                    mem += u
                    json(201, Json.encodeToString(Member.serializer(), u))
                }
                "GET invites" -> json(200, "[" + invites.filter { it.hid == hid && it.open }.joinToString(",") { inviteJson(it) } + "]")
                "POST invites" -> {
                    val i = FakeInvite((invites.maxOfOrNull { it.id } ?: 0) + 1, hid, "tok${invites.size + 1}", me, java.time.Instant.now().plusSeconds(7 * 86400).toString())
                    invites += i
                    json(201, """{"id":${i.id},"url":"$base/invite/${i.token}","expires_at":"${i.expiresAt}"}""")
                }
                "POST leave" -> {
                    if (mem.size == 1 && body(r)["confirm_delete"]?.jsonPrimitive?.content != "true") return error(409, "last_member_confirm")
                    mem.removeAll { it.id == me.id }
                    households.removeAll { it.id == hid }
                    noContent()
                }
                else -> error(405, "method")
            }
        }
        Regex("/api/v1/households/(\\d+)/members/(\\d+)").matchEntire(path)?.let { g ->
            val mem = members[g.groupValues[1].toLong()] ?: return error(404, "not_found")
            mem.removeAll { it.id == g.groupValues[2].toLong() }
            return noContent()
        }
        Regex("/api/v1/households/(\\d+)/invites/(\\d+)").matchEntire(path)?.let { g ->
            invites.find { it.id == g.groupValues[2].toLong() }?.open = false
            return noContent()
        }
        if (path == "/api/v1/users") {
            if (!memberDirectory) return error(404, "not_found")
            val q = r.url.queryParameter("q").orEmpty().trim().lowercase()
            if (q.length < 2) return error(400, "query_too_short")
            return list(Member.serializer(), users.filter { it.id != me.id && (it.displayName.lowercase().contains(q) || it.username.orEmpty().contains(q)) })
        }
        Regex("/api/v1/invites/([^/]+)(/accept)?").matchEntire(path)?.let { g ->
            val i = invites.find { it.token == g.groupValues[1] } ?: return error(404, "invite_not_found")
            if (g.groupValues[2].isEmpty()) {
                return json(200, """{"household_name":"Lake House","invited_by":"${i.by.displayName}","expires_at":"${i.expiresAt}","valid":${i.open},"reason":${if (i.open) "null" else "\"used\""},"can_register":false}""")
            }
            if (!i.open) return error(410, "invite_used")
            i.open = false
            if (households.none { it.id == i.hid }) households += Household(i.hid, "Lake House", 2)
            return json(200, """{"household_id":${i.hid}}""")
        }
        Regex("/api/v1/me/devices/([^/]+)").matchEntire(path)?.let { g ->
            if (!devices.removeIf { it.id == g.groupValues[1] }) return error(404, "device_not_found")
            return noContent()
        }
        return null
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
        if (path == "/api/v1/notifications/poll") return poll(r)
        extra(r, path)?.let { return it }
        Regex("/api/v1/households/(\\d+)").matchEntire(path)?.let { m ->
            val h = households.find { it.id == m.groupValues[1].toLong() } ?: return error(404, "not_found")
            if (r.method == "PATCH") {
                val n = body(r)["name"]!!.jsonPrimitive.content.trim()
                if (n.isEmpty()) return error(400, "invalid_name")
                val renamed = h.copy(name = n)
                households[households.indexOf(h)] = renamed
                return json(200, Json.encodeToString(Household.serializer(), renamed))
            }
            return json(200, Json.encodeToString(Household.serializer(), h))
        }
        Regex("/api/v1/households/(\\d+)/chores").matchEntire(path)?.let { m ->
            val hid = m.groupValues[1].toLong()
            if (households.none { it.id == hid }) return error(404, "not_found")
            if (r.method == "POST") {
                val b = body(r)
                val c = Chore(
                    id = (chores.maxOfOrNull { it.id } ?: 0) + 1, householdId = hid, name = b["name"]!!.jsonPrimitive.content,
                    category = b["category"]?.jsonPrimitive?.contentOrNull, interval = b["interval"]?.jsonPrimitive?.contentOrNull,
                )
                chores += c
                return json(201, Json.encodeToString(Chore.serializer(), choresOf(hid, true).single { it.id == c.id }))
            }
            val archived = r.url.queryParameter("archived") == "true"
            return json(200, Json.encodeToString(ListSerializer(Chore.serializer()), choresOf(hid, archived)))
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
                    if (refuseDeletes) return error(403, "forbidden")
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
            "PATCH /api/v1/me" -> {
                val b = body(r)
                b["display_name"]?.jsonPrimitive?.content?.let { displayName = it.trim() }
                b["notify_done"]?.jsonPrimitive?.content?.let { notifyDone = it == "true" }
                b["notify_due"]?.jsonPrimitive?.content?.let { notifyDue = it == "true" }
                if (b["new_password"] != null && b["current_password"]?.jsonPrimitive?.content != pw) return error(403, "wrong_password")
                json(200, userJson)
            }
            "DELETE /api/v1/me" -> {
                val b = body(r)
                val ok = if (kind == "sso") b["confirm_username"]?.jsonPrimitive?.content == displayName else b["current_password"]?.jsonPrimitive?.content == pw
                if (!ok) return error(403, if (kind == "sso") "confirmation_mismatch" else "wrong_password")
                deleted = true
                issued.clear()
                MockResponse(204, headersOf(), "")
            }
            "GET /api/v1/me/devices" -> json(200, Json.encodeToString(ListSerializer(Device.serializer()), devices))
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
