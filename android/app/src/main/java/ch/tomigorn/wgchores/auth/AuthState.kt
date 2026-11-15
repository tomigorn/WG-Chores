package ch.tomigorn.wgchores.auth

import ch.tomigorn.wgchores.data.ServerConfig

enum class ServerError { Invalid, NotHttps, NotWgChores, Unreachable, Unexpected }

sealed interface LoginError {
    data class Code(val code: String) : LoginError
    data object Offline : LoginError
}

sealed interface AuthState {
    data object Loading : AuthState
    data class PickServer(val input: String = "https://", val error: ServerError? = null, val busy: Boolean = false) : AuthState
    data class Login(val server: String, val config: ServerConfig?, val error: LoginError? = null, val busy: Boolean = false) : AuthState
    data class UpdateRequired(val server: String, val minVersion: String, val appVersion: String) : AuthState
    data class SignedIn(val server: String, val userName: String, val userId: Long? = null) : AuthState
}
