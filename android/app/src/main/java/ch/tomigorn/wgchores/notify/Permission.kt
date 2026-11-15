package ch.tomigorn.wgchores.notify

/** What the phone allows: shown, ask, ask with an explanation, only the system settings can turn them on, or not known yet. */
enum class OsNotify { Allowed, Ask, Rationale, Blocked, Unknown }

object NotifyPermission {
    const val ASK_FROM_SDK = 33

    fun state(sdk: Int, enabled: Boolean, granted: Boolean, rationale: Boolean, askedBefore: Boolean?): OsNotify = when {
        enabled -> OsNotify.Allowed
        askedBefore == null -> OsNotify.Unknown
        sdk < ASK_FROM_SDK || granted -> OsNotify.Blocked
        rationale -> OsNotify.Rationale
        !askedBefore -> OsNotify.Ask
        else -> OsNotify.Blocked
    }
}
