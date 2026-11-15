package ch.tomigorn.wgchores.ui

import ch.tomigorn.wgchores.R
import ch.tomigorn.wgchores.auth.LoginError
import ch.tomigorn.wgchores.auth.ServerError

/** Server error codes to texts, the same keys the web app uses. */
object ErrorText {
    private val codes = mapOf(
        "not_allowed" to R.string.error_not_allowed,
        "sso_failed" to R.string.error_sso_failed,
        "sso_unavailable" to R.string.error_sso_unavailable,
        "invalid_credentials" to R.string.error_invalid_credentials,
        "too_many_attempts" to R.string.error_too_many_attempts,
        "local_accounts_disabled" to R.string.error_local_accounts_disabled,
        "registration_closed" to R.string.error_registration_closed,
        "invalid_username" to R.string.error_invalid_username,
        "username_taken" to R.string.error_username_taken,
        "password_too_short" to R.string.error_password_too_short,
        "password_too_long" to R.string.error_password_too_long,
        "invalid_display_name" to R.string.error_invalid_display_name,
    )

    fun of(error: LoginError): Int = when (error) {
        LoginError.Offline -> R.string.error_offline
        is LoginError.Code -> codes[error.code] ?: R.string.error_generic
    }

    fun of(error: ServerError): Int = when (error) {
        ServerError.Invalid -> R.string.server_invalid
        ServerError.NotHttps -> R.string.server_not_https
        ServerError.NotWgChores -> R.string.server_not_wgc
        ServerError.Unreachable -> R.string.server_unreachable
    }
}
