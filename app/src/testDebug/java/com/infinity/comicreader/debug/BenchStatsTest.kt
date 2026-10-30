package com.infinity.comicreader.debug

import android.app.Application
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BenchStatsTest {
    @Test
    fun percentilesByNearestRank() {
        val sorted = (1L..10L).toList()
        assertEquals(5L, Stats.percentile(sorted, 50))
        assertEquals(9L, Stats.percentile(sorted, 90))
        assertEquals(10L, Stats.percentile(sorted, 99))
        assertEquals(1L, Stats.percentile(listOf(1L), 50))
        assertEquals(0L, Stats.percentile(emptyList(), 50))
    }

    @Test
    fun summariesLeaveOutTheUnknown() {
        val s = Stats.summary(listOf(30L, -1L, 10L, 20L))
        assertEquals(3, s.getInt("n"))
        assertEquals(20L, s.getLong("median"))
        assertEquals(30L, s.getLong("p90"))
        assertEquals(30L, s.getLong("max"))
    }

    @Test
    fun nanosecondsToMilliseconds() {
        assertEquals(17L, Stats.ms(16_600_000))
        assertEquals(-1L, Stats.ms(-1))
        assertEquals(2.5, Stats.round1(2.46), 0.0)
    }

    @Test
    fun framesPastTheirDeadlineAreJanky() {
        val f = FrameStats()
        f.add(8_000_000, 8_333_333)
        f.add(12_000_000, 8_333_333)
        f.add(4_000_000, 8_333_333)
        val json = f.toJson()
        assertEquals(3, json.getInt("frames"))
        assertEquals(1, json.getInt("janky"))
        assertEquals(8.0, json.getDouble("p50Ms"), 0.0)
        assertEquals(12.0, json.getDouble("maxMs"), 0.0)
        f.reset()
        assertEquals(0, f.toJson().getInt("frames"))
    }

    @Test
    fun aPlanFromItsExtras() {
        val plan = BenchPlan.of(
            Intent().putExtra("do", "read").putExtra("path", "/books/a.cbz").putExtra("layout", "two")
                .putExtra("turns", 4).putExtra("pace", 250).putExtra("fresh", true).putExtra("doubletap", "off")
        )!!
        assertEquals(BenchPlan.READ, plan.what)
        assertEquals(BenchPlan.TWO, plan.layout)
        assertEquals(BenchPlan.TAP, plan.input)
        assertEquals(4, plan.turns)
        assertEquals(250L, plan.pace)
        assertEquals(true, plan.fresh)
        assertEquals(false, plan.doubleTap)
        assertEquals(1, plan.reps)
        assertNull(BenchPlan.of(Intent().putExtra("do", "read").putExtra("path", "/a.cbz"))!!.doubleTap)
    }

    @Test
    fun aShareStandsInForAPathWhenReading() {
        val plan = BenchPlan.of(Intent().putExtra("do", "read").putExtra("share", DebugBooks.FIRST))!!
        assertNull(plan.path)
        assertEquals(DebugBooks.FIRST, plan.share)
        assertNull(BenchPlan.of(Intent().putExtra("do", "grid").putExtra("share", DebugBooks.FIRST)))
    }

    @Test
    fun noPlanWithoutWhatAndWhere() {
        assertNull(BenchPlan.of(Intent().putExtra("path", "/a.cbz")))
        assertNull(BenchPlan.of(Intent().putExtra("do", "read")))
        assertNull(BenchPlan.of(Intent().putExtra("do", "dance").putExtra("path", "/a.cbz")))
        assertNull(BenchPlan.of(Intent().putExtra("do", "read").putExtra("path", "/a.cbz").putExtra("layout", "sideways")))
        assertNull(BenchPlan.of(Intent().putExtra("do", "read").putExtra("path", "/a.cbz").putExtra("input", "voice")))
    }
}
