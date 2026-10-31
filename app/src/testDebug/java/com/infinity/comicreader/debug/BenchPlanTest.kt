package com.infinity.comicreader.debug

import android.app.Application
import android.content.Intent
import com.infinity.comicreader.reader.CoreSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BenchPlanTest {
    private fun read() = Intent().putExtra("do", "read").putExtra("path", "/books/a.cbz")

    @Test
    fun decodeKnobsAreReadAndBounded() {
        val p = BenchPlan.of(read().putExtra("platformcores", "big").putExtra("decoders", 12).putExtra("preload", 3).putExtra("seams", "on"))!!
        assertEquals(CoreSet.BIG, p.platformCores)
        assertEquals(8, p.decodeThreads)
        assertEquals(3, p.preload)
        assertEquals(true, p.seams)
        assertEquals("big", p.toJson().getString("platformCores"))
    }

    @Test
    fun absentKnobsKeepTheSettings() {
        val p = BenchPlan.of(read())!!
        assertNull(p.platformCores)
        assertNull(p.decodeThreads)
        assertNull(p.preload)
        assertNull(p.seams)
    }

    @Test
    fun unknownValuesRefuseThePlan() {
        assertNull(BenchPlan.of(read().putExtra("platformcores", "little")))
        assertNull(BenchPlan.of(read().putExtra("seams", "maybe")))
    }
}
