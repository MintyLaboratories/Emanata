package minty.emanata.debug

import android.app.Application
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The `a3` bench's stop times and memory peaks ([A3Runs]). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class A3RunsTest {
    private fun longs(a: JSONArray) = (0 until a.length()).map { a.getLong(it) }

    @Test
    fun stopTimeIsTheMedianOfTheStops() {
        val j = A3Runs.stopsJson(listOf(A3Runs.Stop(900, 40, 3), A3Runs.Stop(900, 2, 4), A3Runs.Stop(900, 15, 3), A3Runs.Stop(900, 300, 5), A3Runs.Stop(900, 9, 4)))
        assertEquals(5, j.getInt("n"))
        assertEquals(0, j.getInt("ranToEnd"))
        assertEquals(15L, j.getLong("stopMs"))
        assertEquals(listOf(40L, 2L, 15L, 300L, 9L), longs(j.getJSONArray("stopsMs")))
        assertEquals(listOf(900L, 900L, 900L, 900L, 900L), longs(j.getJSONArray("atMs")))
        assertEquals(listOf(3L, 4L, 3L, 5L, 4L), longs(j.getJSONArray("tilesRun")))
    }

    @Test
    fun evenCountsTakeTheLowerMiddle() {
        val j = A3Runs.stopsJson(listOf(A3Runs.Stop(500, 30, 1), A3Runs.Stop(500, 10, 1), A3Runs.Stop(500, 20, 1), A3Runs.Stop(500, 40, 1)))
        assertEquals(20L, j.getLong("stopMs"))
    }

    @Test
    fun pagesThatRanToTheEndCountApart() {
        val j = A3Runs.stopsJson(listOf(null, A3Runs.Stop(500, 7, 2), null))
        assertEquals(3, j.getInt("n"))
        assertEquals(2, j.getInt("ranToEnd"))
        assertEquals(7L, j.getLong("stopMs"))
        assertEquals(listOf(7L), longs(j.getJSONArray("stopsMs")))
    }

    @Test
    fun noStopGivesNullStopTime() {
        val j = A3Runs.stopsJson(listOf(null, null))
        assertEquals(2, j.getInt("ranToEnd"))
        assertTrue(j.has("stopMs") && j.isNull("stopMs"))
        assertEquals(0, j.getJSONArray("stopsMs").length())
    }

    @Test
    fun peaksKeepTheMostOfEachKindFromTheBase() {
        val p = A3Runs.Peaks(A3Runs.Mem(300 * 1024L, 250 * 1024L, 20 * 1024L))
        p.seeRss(280 * 1024L)
        p.see(A3Runs.Mem(310 * 1024L, 240 * 1024L, 90 * 1024L))
        p.seeRss(330 * 1024L)
        p.see(A3Runs.Mem(305 * 1024L, 260 * 1024L, 70 * 1024L))
        val j = p.toJson()
        assertEquals(300, j.getInt("rssBase"))
        assertEquals(330, j.getInt("rssPeak"))
        assertEquals(250, j.getInt("pssBase"))
        assertEquals(260, j.getInt("pssPeak"))
        assertEquals(20, j.getInt("gfxBase"))
        assertEquals(90, j.getInt("gfxPeak"))
    }
}
