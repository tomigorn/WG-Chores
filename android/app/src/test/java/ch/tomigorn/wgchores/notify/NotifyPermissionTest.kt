package ch.tomigorn.wgchores.notify

import org.junit.Assert.assertEquals
import org.junit.Test

class NotifyPermissionTest {
    private fun s(sdk: Int = 36, enabled: Boolean = false, granted: Boolean = false, rationale: Boolean = false, asked: Boolean = false) =
        NotifyPermission.state(sdk, enabled, granted, rationale, asked)

    @Test fun enabledIsAllowed() {
        assertEquals(OsNotify.Allowed, s(enabled = true, granted = true))
        assertEquals(OsNotify.Allowed, s(sdk = 26, enabled = true))
    }

    @Test fun neverAskedAsks() = assertEquals(OsNotify.Ask, s())

    @Test fun deniedOnceExplains() = assertEquals(OsNotify.Rationale, s(rationale = true, asked = true))

    @Test fun deniedForGoodNeedsTheSystemSettings() = assertEquals(OsNotify.Blocked, s(asked = true))

    @Test fun grantedButTurnedOffInTheSystem() = assertEquals(OsNotify.Blocked, s(granted = true, asked = true))

    @Test fun olderAndroidTurnedOffInTheSystem() = assertEquals(OsNotify.Blocked, s(sdk = 32))
}
