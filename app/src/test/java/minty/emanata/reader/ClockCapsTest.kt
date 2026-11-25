package minty.emanata.reader

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ClockCapsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun policy(root: File, n: Int, cap: String) = File(root, "policy$n").apply {
        mkdirs()
        File(this, "scaling_max_freq").writeText(cap)
    }

    @Test
    fun readsEachDomainsCapInPolicyOrder() {
        val root = tmp.newFolder("cpufreq")
        policy(root, 7, "2995200\n")
        policy(root, 0, "1785600\n")
        policy(root, 4, "2496000\n")
        File(root, "boost").writeText("0")
        val caps = ClockCaps(root)
        assertArrayEquals(intArrayOf(1785600, 2496000, 2995200), caps.read())
        policy(root, 4, "2112000\n")
        assertArrayEquals(intArrayOf(1785600, 2112000, 2995200), caps.read())
    }

    @Test
    fun noneWhereThereAreNoDomainsOrOneCannotBeRead() {
        assertNull(ClockCaps(tmp.newFolder("empty")).read())
        val root = tmp.newFolder("broken")
        policy(root, 0, "1785600")
        policy(root, 4, "fast")
        assertNull(ClockCaps(root).read())
    }

    @Test
    fun eachSampleRestsAThirdOfTheOneBeforeWithinBounds() {
        assertEquals(Calibration.REST_MIN_MS, Calibration.restMs(0.0))
        assertEquals(Calibration.REST_MIN_MS, Calibration.restMs(150.0))
        assertEquals(150L, Calibration.restMs(500.0))
        assertEquals(Calibration.REST_MAX_MS, Calibration.restMs(1600.0))
    }

    @Test
    fun aCapBelowTheHighestSeenCountsAsCapped() {
        val top = intArrayOf(1785600, 2496000, 2995200)
        assertFalse(Calibration.capped(top, top.copyOf()))
        assertTrue(Calibration.capped(top, intArrayOf(1785600, 2112000, 2995200)))
        // Higher counts as not capped, and raises what later reads are held to.
        val higher = intArrayOf(1785600, 2496000, 3000000)
        assertFalse(Calibration.capped(top, higher))
        assertArrayEquals(higher, Calibration.raised(top, higher))
        assertArrayEquals(intArrayOf(1785600, 2496000, 2995200), Calibration.raised(top, intArrayOf(1000, 2112000, 2995200)))
        // Unknown either way, or a different count of domains: not capped.
        assertFalse(Calibration.capped(null, top))
        assertFalse(Calibration.capped(top, null))
        assertFalse(Calibration.capped(top, intArrayOf(1, 2)))
        assertArrayEquals(top, Calibration.raised(null, top))
        assertArrayEquals(top, Calibration.raised(top, null))
        assertArrayEquals(intArrayOf(1, 2), Calibration.raised(top, intArrayOf(1, 2)))
    }
}
