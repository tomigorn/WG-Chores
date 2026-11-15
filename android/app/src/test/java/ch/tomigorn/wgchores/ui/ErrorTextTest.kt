package ch.tomigorn.wgchores.ui

import ch.tomigorn.wgchores.R
import ch.tomigorn.wgchores.auth.LoginError
import ch.tomigorn.wgchores.auth.ServerError
import org.junit.Assert.assertEquals
import org.junit.Test

class ErrorTextTest {
    @Test fun knownServerCodesHaveTheirOwnText() {
        assertEquals(R.string.error_invalid_credentials, ErrorText.of(LoginError.Code("invalid_credentials")))
        assertEquals(R.string.error_not_allowed, ErrorText.of(LoginError.Code("not_allowed")))
        assertEquals(R.string.error_too_many_attempts, ErrorText.of(LoginError.Code("too_many_attempts")))
        assertEquals(R.string.error_registration_closed, ErrorText.of(LoginError.Code("registration_closed")))
    }

    @Test fun unknownCodesAreGeneric() = assertEquals(R.string.error_generic, ErrorText.of(LoginError.Code("busy")))

    @Test fun keystoreFailureHasItsText() = assertEquals(R.string.error_keystore_failed, ErrorText.of(LoginError.Code("keystore_failed")))

    @Test fun unexpectedIsGeneric() {
        assertEquals(R.string.error_generic, ErrorText.of(LoginError.Code("unexpected")))
        assertEquals(R.string.error_generic, ErrorText.of(ServerError.Unexpected))
    }

    @Test fun offlineHasItsText() = assertEquals(R.string.error_offline, ErrorText.of(LoginError.Offline))

    @Test fun serverPickerErrors() {
        assertEquals(R.string.server_not_https, ErrorText.of(ServerError.NotHttps))
        assertEquals(R.string.server_not_wgc, ErrorText.of(ServerError.NotWgChores))
    }
}
