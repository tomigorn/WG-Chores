package ch.tomigorn.wgchores.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PicturesTest {
    @Test fun onlyPermanentFailuresAreRemembered() {
        assertTrue(Pictures.permanent(Pictures.Failure.Status(404)))
        assertTrue(Pictures.permanent(Pictures.Failure.Status(403)))
        assertTrue(Pictures.permanent(Pictures.Failure.Undecodable))
        assertTrue(Pictures.permanent(Pictures.Failure.TooBig))
        assertTrue(Pictures.permanent(Pictures.Failure.NotHttps))
        assertFalse(Pictures.permanent(Pictures.Failure.Status(500)))
        assertFalse(Pictures.permanent(Pictures.Failure.Status(429)))
        assertFalse(Pictures.permanent(Pictures.Failure.Network))
    }
}
