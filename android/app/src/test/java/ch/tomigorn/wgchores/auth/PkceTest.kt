package ch.tomigorn.wgchores.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PkceTest {
    @Test fun verifierHasTheAllowedLengthAndCharacters() {
        val v = Pkce.newVerifier()
        assertTrue(v.length in 43..128)
        assertTrue(v, v.matches(Regex("[A-Za-z0-9._~-]+")))
    }

    @Test fun verifiersAreRandom() = assertNotEquals(Pkce.newVerifier(), Pkce.newVerifier())

    @Test fun challengeIsUnpaddedBase64UrlOfSha256() =
        assertEquals(
            "hah6J_uZgk5kuAXUUoYWKvbmjBsvkOhy6u3bGWMgqt0",
            Pkce.challenge("dBjftJeZ4CVP-mJ92K-1-9-l2gpXT4-L5TAUH8HJzEs"),
        )
}
