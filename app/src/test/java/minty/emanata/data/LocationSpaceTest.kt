package minty.emanata.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Free space of locations kept between runs, and when it is old enough to ask again. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class LocationSpaceTest {
    private var now = 1_000_000L
    private val spaces = LocationSpace(ApplicationProvider.getApplicationContext()) { now }

    @Test
    fun keptValuesGoStaleAfterAnHour() {
        assertNull(spaces.get("/net/s1"))
        assertTrue(spaces.stale(null))
        spaces.put("/net/s1", 3, 8)
        val kept = spaces.get("/net/s1")!!
        assertEquals(LocationSpace.Space(3, 8, now), kept)
        now += LocationSpace.MAX_AGE_MS - 1
        assertFalse(spaces.stale(kept))
        now += 1
        assertTrue(spaces.stale(kept))
        // A clock set back counts as stale too.
        now = 0
        assertTrue(spaces.stale(kept))
        spaces.remove("/net/s1")
        assertNull(spaces.get("/net/s1"))
    }
}
