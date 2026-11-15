package ch.tomigorn.wgchores.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerConfigTest {
    private val sample = """
        {"name":"WG Chores","local_accounts":true,"open_registration":false,
         "oidc":{"name":"Holy Grail","account_url":"https://sso.example.com/if/user/"},
         "notify_times":["14:00","19:00"],"tz":"Europe/Zurich","version":"1.2.0","min_app_version":"1.0.0",
         "something_new":42}
    """.trimIndent()

    @Test fun parsesTheServerConfig() {
        val c = ServerConfig.parse(sample)!!
        assertTrue(c.localAccounts)
        assertFalse(c.openRegistration)
        assertEquals("Holy Grail", c.oidc?.name)
        assertEquals("https://sso.example.com/if/user/", c.oidc?.accountUrl)
        assertEquals(listOf("14:00", "19:00"), c.notifyTimes)
        assertEquals("Europe/Zurich", c.tz)
        assertEquals("1.0.0", c.minAppVersion)
    }

    @Test fun ssoIsOptional() {
        val c = ServerConfig.parse(sample.replace(Regex(""""oidc":\{[^}]*\},"""), """"oidc":null,"""))!!
        assertNull(c.oidc)
    }

    @Test fun rejectsAnotherApp() = assertNull(ServerConfig.parse(sample.replace("WG Chores", "Nextcloud")))

    @Test fun rejectsHtml() = assertNull(ServerConfig.parse("<!doctype html><html></html>"))

    @Test fun rejectsEmptyJson() = assertNull(ServerConfig.parse("{}"))

    @Test fun rejectsMissingFields() = assertNull(ServerConfig.parse("""{"name":"WG Chores"}"""))

    @Test fun roundTripsForTheCache() {
        val c = ServerConfig.parse(sample)!!
        assertEquals(c, ServerConfig.parse(c.toJson()))
    }
}
