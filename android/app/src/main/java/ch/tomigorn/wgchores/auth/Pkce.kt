package ch.tomigorn.wgchores.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object Pkce {
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    /** 64 characters of base64url from 48 random bytes. */
    fun newVerifier(): String = encoder.encodeToString(ByteArray(48).also(random::nextBytes))

    fun challenge(verifier: String): String =
        encoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
}
