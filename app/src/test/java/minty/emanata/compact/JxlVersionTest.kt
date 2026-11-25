package minty.emanata.compact

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** libjxl is used only in the release its binding is compiled against. */
class JxlVersionTest {
    @Test
    fun onlyTheReleaseOfTheHeadersMatches() {
        assertTrue(JxlVersion.matches(12_000))
        assertTrue(JxlVersion.matches(12_007))
        assertFalse(JxlVersion.matches(0))
        assertFalse(JxlVersion.matches(11_001))
        assertFalse(JxlVersion.matches(13_000))
        assertFalse(JxlVersion.matches(1_012_000))
    }
}
