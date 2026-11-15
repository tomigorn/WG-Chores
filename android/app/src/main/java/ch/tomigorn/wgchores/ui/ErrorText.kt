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
        "keystore_failed" to R.string.error_keystore_failed,
        "wrong_password" to R.string.error_wrong_password,
        "cannot_add" to R.string.error_cannot_add,
        "too_many_invites" to R.string.error_too_many_invites,
        "confirmation_mismatch" to R.string.error_confirmation_mismatch,
        "invalid_name" to R.string.error_invalid_name,
        "not_found" to R.string.error_not_found,
        "invite_used" to R.string.error_invite_used,
        "invite_expired" to R.string.error_invite_expired,
        "invite_cancelled" to R.string.error_invite_cancelled,
        "invite_not_found" to R.string.error_invite_not_found,
    )

    /** A failed request as text; saves pass "Connect to the internet to save this" as [offline]. */
    fun of(e: Throwable, offline: Int = R.string.error_offline): Int = when (e) {
        is ch.tomigorn.wgchores.api.OfflineException -> offline
        is ch.tomigorn.wgchores.api.ApiException -> codes[e.code] ?: R.string.error_generic
        else -> R.string.error_generic
    }

    fun of(error: LoginError): Int = when (error) {
        LoginError.Offline -> R.string.error_offline
        is LoginError.Code -> codes[error.code] ?: R.string.error_generic
    }

    fun of(error: ServerError): Int = when (error) {
        ServerError.Invalid -> R.string.server_invalid
        ServerError.NotHttps -> R.string.server_not_https
        ServerError.NotWgChores -> R.string.server_not_wgc
        ServerError.Unreachable -> R.string.server_unreachable
        ServerError.Unexpected -> R.string.error_generic
    }
}
