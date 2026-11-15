package ch.tomigorn.wgchores.api

import ch.tomigorn.wgchores.testing.FakeServer
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ApiClientTest {
    private val fake = FakeServer().start()
    private var bearer: String? = null
    private var unauthenticated = 0
    private val api = ApiClient(fake.url, bearer = { bearer }, onUnauthenticated = { unauthenticated++ })

    @After fun stop() = fake.close()

    private inline fun <reified T : Throwable> failsWith(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw e
        }
        fail("expected ${T::class.simpleName}")
        error("unreachable")
    }

    @Test fun probeReadsTheConfig() = runBlocking {
        val c = ApiClient.probe(fake.url)
        assertEquals("Holy Grail", c.oidc?.name)
        assertEquals("/api/v1/config", fake.requests.single().url.encodedPath)
    }

    @Test fun probeRejectsAnotherApp() {
        fake.appName = "Nextcloud"
        failsWith<NotWgChoresException> { runBlocking { ApiClient.probe(fake.url) } }
    }

    @Test fun probeRejectsANotFound() {
        MockWebServer().use { other ->
            other.enqueue(MockResponse(404, headersOf("Content-Type", "text/html"), "<h1>nope</h1>"))
            other.start()
            failsWith<NotWgChoresException> { runBlocking { ApiClient.probe(other.url("/").toString().trimEnd('/')) } }
        }
    }

    @Test fun probeOfAClosedPortIsOffline() {
        val url = fake.url
        fake.close()
        failsWith<OfflineException> { runBlocking { ApiClient.probe(url) } }
    }

    @Test fun loginSendsTheAppClientAndReturnsTheToken() = runBlocking {
        val r = api.login("anna", fake.pw, "Pixel 9")
        assertTrue(r.token in fake.issued)
        assertEquals("Jane Doe", r.user?.displayName)
        val req = fake.lastRequest("/api/v1/auth/login")!!
        assertEquals("1", req.headers["X-WGC"])
        val body = req.body!!.utf8()
        assertTrue(body, body.contains(""""client":"android"""") && body.contains(""""device_name":"Pixel 9""""))
    }

    @Test fun wrongPasswordIsAnApiErrorNotASignOut() {
        val e = failsWith<ApiException> { runBlocking { api.login("anna", "wrong password!", "Pixel 9") } }
        assertEquals("invalid_credentials", e.code)
        assertEquals(0, unauthenticated)
    }

    @Test fun registerReturnsTheToken() = runBlocking {
        assertTrue(api.register("anna", fake.pw, "Jane Doe", "Pixel 9").token in fake.issued)
    }

    @Test fun meSendsTheBearerToken() = runBlocking {
        bearer = api.login("anna", fake.pw, "Pixel 9").token
        assertEquals("Jane Doe", api.me().displayName)
        assertEquals("Bearer $bearer", fake.lastRequest("/api/v1/me")!!.headers["Authorization"])
    }

    @Test fun unauthenticatedCallsTheHandler() {
        bearer = "revoked"
        val e = failsWith<ApiException> { runBlocking { api.me() } }
        assertEquals(401, e.status)
        assertEquals(1, unauthenticated)
    }

    @Test fun appTokenNeedsTheMatchingVerifier() = runBlocking {
        val verifier = "v".repeat(43)
        val code = fake.issueSsoCode(ch.tomigorn.wgchores.auth.Pkce.challenge(verifier))
        assertNotNull(api.appToken(code, verifier, "Pixel 9"))
        val e = failsWith<ApiException> { runBlocking { api.appToken(code, verifier, "Pixel 9") } }
        assertEquals("invalid_code", e.code)
    }

    @Test fun logoutEndsTheDeviceSession() = runBlocking {
        bearer = api.login("anna", fake.pw, "Pixel 9").token
        api.logout()
        assertTrue(fake.issued.isEmpty())
    }

    @Test fun gatewayErrorWithoutJsonIsOffline() {
        MockWebServer().use { other ->
            other.enqueue(MockResponse(502, headersOf("Content-Type", "text/html"), "Bad Gateway"))
            other.start()
            val a = ApiClient(other.url("/").toString().trimEnd('/'), bearer = { "t" })
            failsWith<OfflineException> { runBlocking { a.me() } }
        }
    }

    private val shortTimeout = okhttp3.OkHttpClient.Builder().callTimeout(300, java.util.concurrent.TimeUnit.MILLISECONDS).build()

    private fun slowBody(body: String) = MockResponse.Builder()
        .code(200)
        .setHeader("Content-Type", "application/json")
        .body(body)
        .throttleBody(1, 1, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    @Test fun probeWithAStalledBodyIsOffline() {
        MockWebServer().use { other ->
            other.enqueue(slowBody("""{"name":"WG Chores","local_accounts":true,"open_registration":false,"min_app_version":"1.0.0"}"""))
            other.start()
            failsWith<OfflineException> { runBlocking { ApiClient.probe(other.url("/").toString().trimEnd('/'), shortTimeout) } }
        }
    }

    @Test fun stalledBodyOfACallIsOffline() {
        MockWebServer().use { other ->
            other.enqueue(slowBody("""{"id":1,"display_name":"Jane Doe"}"""))
            other.start()
            val a = ApiClient(other.url("/").toString().trimEnd('/'), bearer = { "t" }, http = shortTimeout)
            failsWith<OfflineException> { runBlocking { a.me() } }
        }
    }

    @Test fun probeRejectsHtmlWithoutReadingIt() {
        MockWebServer().use { other ->
            other.enqueue(MockResponse(200, headersOf("Content-Type", "text/html"), "<html>" + "x".repeat(200_000)))
            other.start()
            failsWith<NotWgChoresException> { runBlocking { ApiClient.probe(other.url("/").toString().trimEnd('/')) } }
        }
    }

    @Test fun probeRejectsAHugeJsonBody() {
        MockWebServer().use { other ->
            val padding = "\"pad\":\"" + "x".repeat(70_000) + "\","
            other.enqueue(
                MockResponse(
                    200,
                    headersOf("Content-Type", "application/json"),
                    """{$padding"name":"WG Chores","local_accounts":true,"open_registration":false,"min_app_version":"1.0.0"}""",
                ),
            )
            other.start()
            failsWith<NotWgChoresException> { runBlocking { ApiClient.probe(other.url("/").toString().trimEnd('/')) } }
        }
    }

    @Test fun emptySuccessForMeIsAnInvalidResponse() {
        MockWebServer().use { other ->
            other.enqueue(MockResponse(204, headersOf(), ""))
            other.start()
            val a = ApiClient(other.url("/").toString().trimEnd('/'), bearer = { "t" })
            assertEquals("invalid_response", failsWith<ApiException> { runBlocking { a.me() } }.code)
        }
    }

    @Test fun serverErrorWithoutJsonIsAnApiError() {
        MockWebServer().use { other ->
            other.enqueue(MockResponse(500, headersOf("Content-Type", "text/plain"), "boom"))
            other.start()
            val a = ApiClient(other.url("/").toString().trimEnd('/'), bearer = { "t" })
            assertEquals("http_500", failsWith<ApiException> { runBlocking { a.me() } }.code)
        }
    }
}
