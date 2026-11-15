package ch.tomigorn.wgchores.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class SsoCallbackTest {
    @Test fun readsTheCode() = assertEquals(SsoCallback.Code("abc123"), SsoCallback.parse("wgchores://auth?code=abc123"))

    @Test fun readsAnError() = assertEquals(SsoCallback.Error("not_allowed"), SsoCallback.parse("wgchores://auth?error=not_allowed"))

    @Test fun errorWinsOverCode() = assertEquals(SsoCallback.Error("sso_failed"), SsoCallback.parse("wgchores://auth?code=x&error=sso_failed"))

    @Test fun ignoresOtherSchemes() = assertEquals(null, SsoCallback.parse("https://auth?code=abc"))

    @Test fun ignoresOtherHosts() = assertEquals(null, SsoCallback.parse("wgchores://other?code=abc"))

    @Test fun missingCodeIsAFailure() = assertEquals(SsoCallback.Error("sso_failed"), SsoCallback.parse("wgchores://auth"))

    @Test fun acceptsTheCallbackOnlyThroughTheFallbackAlias() {
        val uri = "wgchores://auth?code=abc"
        assertEquals(uri, SsoCallback.fromIntent("ch.tomigorn.wgchores.SsoCallback", "ch.tomigorn.wgchores", uri))
        assertEquals(null, SsoCallback.fromIntent("ch.tomigorn.wgchores.MainActivity", "ch.tomigorn.wgchores", uri))
        assertEquals(null, SsoCallback.fromIntent(null, "ch.tomigorn.wgchores", uri))
        assertEquals(null, SsoCallback.fromIntent("ch.tomigorn.wgchores.SsoCallback", "ch.tomigorn.wgchores", "https://example.com"))
        assertEquals(null, SsoCallback.fromIntent("ch.tomigorn.wgchores.SsoCallback", "ch.tomigorn.wgchores", null))
    }

    @Test fun buildsTheStartUrl() =
        assertEquals(
            "https://chores.example.com/auth/oidc/start?client=android&challenge=abc_-1",
            SsoCallback.startUrl("https://chores.example.com", "abc_-1"),
        )
}
