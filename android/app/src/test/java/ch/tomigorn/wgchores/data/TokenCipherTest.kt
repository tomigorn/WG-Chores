package ch.tomigorn.wgchores.data

import javax.crypto.KeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TokenCipherTest {
    private fun newKey() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val key = newKey()
    private val cipher = TokenCipher { key }

    @Test fun roundTrips() = assertEquals("tok-123", cipher.decrypt(cipher.encrypt("tok-123")!!))

    @Test fun ciphertextHidesTheTokenAndUsesAFreshIv() {
        val a = cipher.encrypt("tok-123")!!
        assertFalse(a.contains("tok-123"))
        assertNotEquals(a, cipher.encrypt("tok-123"))
    }

    @Test fun tamperedCiphertextIsRejected() {
        val blob = cipher.encrypt("tok-123")!!
        val flipped = blob.substring(0, blob.length - 2) + (if (blob[blob.length - 2] == 'A') 'B' else 'A') + blob.last()
        assertNull(cipher.decrypt(flipped))
    }

    @Test fun anotherKeyCannotRead() {
        val other = newKey()
        assertNull(TokenCipher { other }.decrypt(cipher.encrypt("tok-123")!!))
    }

    @Test fun keystoreFailureIsNoCrash() {
        val broken = TokenCipher { throw java.security.KeyStoreException("keystore gone") }
        assertNull(broken.encrypt("tok-123"))
        assertNull(broken.decrypt(cipher.encrypt("tok-123")!!))
    }

    @Test fun garbageIsRejected() = assertNull(cipher.decrypt("not base64 !!"))
}
