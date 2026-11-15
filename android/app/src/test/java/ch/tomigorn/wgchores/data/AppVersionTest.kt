package ch.tomigorn.wgchores.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {
    @Test fun olderMajor() = assertTrue(AppVersion.isOlder("0.9.0", "1.0.0"))

    @Test fun comparesNumbersNotText() = assertFalse(AppVersion.isOlder("1.10.0", "1.9.0"))

    @Test fun olderPatch() = assertTrue(AppVersion.isOlder("1.2.3", "1.2.4"))

    @Test fun equalIsFine() = assertFalse(AppVersion.isOlder("1.2.0", "1.2.0"))

    @Test fun newerIsFine() = assertFalse(AppVersion.isOlder("2.0.0", "1.9.9"))

    @Test fun ignoresASuffix() = assertFalse(AppVersion.isOlder("1.0.0-debug", "1.0.0"))

    @Test fun unreadableMinimumNeverBlocks() = assertFalse(AppVersion.isOlder("1.0.0", "soon"))
}
