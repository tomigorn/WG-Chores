package ch.tomigorn.wgchores.data

import ch.tomigorn.wgchores.data.ServerUrlResult.Empty
import ch.tomigorn.wgchores.data.ServerUrlResult.Invalid
import ch.tomigorn.wgchores.data.ServerUrlResult.NotHttps
import ch.tomigorn.wgchores.data.ServerUrlResult.Ok
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerUrlTest {
    private fun n(input: String, local: Boolean = false) = ServerUrl.normalize(input, allowLocalHttp = local)

    @Test fun addsHttpsToABareHost() = assertEquals(Ok("https://chores.example.com"), n("chores.example.com"))

    @Test fun trimsLowercasesAndDropsTrailingSlash() =
        assertEquals(Ok("https://chores.example.com"), n("  https://Chores.Example.COM/  "))

    @Test fun keepsAPathPrefix() = assertEquals(Ok("https://example.com/wg"), n("https://example.com/wg/"))

    @Test fun keepsAPort() = assertEquals(Ok("https://example.com:8443"), n("https://example.com:8443"))

    @Test fun prefillAloneIsEmpty() {
        assertEquals(Empty, n("https://"))
        assertEquals(Empty, n("   "))
    }

    @Test fun refusesHttp() = assertEquals(NotHttps, n("http://chores.example.com"))

    @Test fun refusesOtherSchemes() = assertEquals(Invalid, n("ftp://chores.example.com"))

    @Test fun refusesSpacesInTheHost() = assertEquals(Invalid, n("https://chores example.com"))

    @Test fun refusesQueryAndFragment() {
        assertEquals(Invalid, n("https://example.com/?a=1"))
        assertEquals(Invalid, n("https://example.com/#x"))
    }

    @Test fun refusesCredentials() = assertEquals(Invalid, n("https://jane:secret@example.com"))

    @Test fun refusesAHostWithoutADot() = assertEquals(Invalid, n("https://chores"))

    @Test fun allowsLocalHttpOnlyWhenEnabled() {
        assertEquals(Ok("http://127.0.0.1:8080"), n("http://127.0.0.1:8080", local = true))
        assertEquals(Ok("http://localhost:8080"), n("http://localhost:8080/", local = true))
        assertEquals(NotHttps, n("http://127.0.0.1:8080"))
        assertEquals(NotHttps, n("http://chores.example.com", local = true))
    }
}
