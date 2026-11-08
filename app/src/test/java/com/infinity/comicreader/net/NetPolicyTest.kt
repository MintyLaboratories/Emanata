package com.infinity.comicreader.net

import com.infinity.comicreader.net.NetPolicy.Why
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Optional network work held back by the skip choices: each kind, metered or not, fast or slow, with and without "Any". */
class NetPolicyTest {

    @After
    fun tearDown() {
        NetPolicy.rules = NetRules()
        Metered.probe = { Metered.Link(connected = true, notMetered = true, activeMetered = false) }
        Metered.forget()
        Remotes.serve(emptyList()) { throw AssertionError("no shares") }
    }

    /** The defaults, with "Any network use I didn't start" ticked or not on both lists. */
    private fun rules(any: Boolean) = NetRules(
        metered = NetRules.METERED_DEFAULT - NetSkip.ANY + if (any) setOf(NetSkip.ANY) else emptySet(),
        slow = NetRules.SLOW_DEFAULT + if (any) setOf(NetSkip.ANY) else emptySet()
    )

    /** What each kind of work expects, written out apart from the rule. */
    private fun expected(work: NetWork, metered: Boolean, slow: Boolean, any: Boolean): Why? {
        if (metered && (any || work != NetWork.OTHER)) return Why.METERED
        if (slow && (any || work in setOf(NetWork.COVER_OFF_SCREEN, NetWork.HASHING, NetWork.READ_AHEAD))) return Why.SLOW
        return null
    }

    @Test
    fun everyKindMeteredOrNotFastOrSlowWithAndWithoutAny() {
        var cases = 0
        for (any in listOf(false, true)) for (metered in listOf(false, true)) for (slow in listOf(false, true)) for (work in NetWork.entries) {
            assertEquals("$work metered=$metered slow=$slow any=$any", expected(work, metered, slow, any), NetPolicy.why(work, metered, slow, rules(any)))
            cases++
        }
        assertEquals(2 * 2 * 2 * NetWork.entries.size, cases)
    }

    @Test
    fun theDefaultsSkipEverythingOptionalOnMeteredConnections() {
        for (work in NetWork.entries) assertEquals(work.name, Why.METERED, NetPolicy.why(work, metered = true, slow = false, rules = NetRules()))
        for (work in NetWork.entries) assertNull(work.name, NetPolicy.why(work, metered = false, slow = false, rules = NetRules()))
    }

    @Test
    fun onSlowSharesTheDefaultsKeepCoversOnScreenAndOtherReads() {
        val rules = NetRules()
        assertNull(NetPolicy.why(NetWork.COVER, metered = false, slow = true, rules = rules))
        assertNull(NetPolicy.why(NetWork.OTHER, metered = false, slow = true, rules = rules))
        assertNull(NetPolicy.why(NetWork.DOWNLOAD, metered = false, slow = true, rules = rules))
        assertEquals(Why.SLOW, NetPolicy.why(NetWork.COVER_OFF_SCREEN, metered = false, slow = true, rules = rules))
        assertEquals(Why.SLOW, NetPolicy.why(NetWork.HASHING, metered = false, slow = true, rules = rules))
        assertEquals(Why.SLOW, NetPolicy.why(NetWork.READ_AHEAD, metered = false, slow = true, rules = rules))
    }

    @Test
    fun eachChoiceAloneSkipsOnlyItsWork() {
        val covers = setOf(NetWork.COVER, NetWork.COVER_OFF_SCREEN)
        val of = mapOf(
            NetSkip.COVERS to covers, NetSkip.HASHING to setOf(NetWork.HASHING),
            NetSkip.READ_AHEAD to setOf(NetWork.READ_AHEAD), NetSkip.DOWNLOADS to setOf(NetWork.DOWNLOAD)
        )
        for ((choice, works) in of) {
            val rules = NetRules(metered = setOf(choice), slow = emptySet())
            for (work in NetWork.entries) {
                assertEquals("$choice $work", work in works, NetPolicy.why(work, metered = true, slow = true, rules = rules) != null)
            }
        }
        val none = NetRules(emptySet(), emptySet())
        for (work in NetWork.entries) assertNull(NetPolicy.why(work, metered = true, slow = true, rules = none))
    }

    @Test
    fun meteredIsTheReasonWhenBothHold() {
        assertEquals(Why.METERED, NetPolicy.why(NetWork.HASHING, metered = true, slow = true, rules = NetRules()))
        // Not skipped on metered: the slow share's own choice holds it back.
        val rules = NetRules(metered = emptySet(), slow = NetRules.SLOW_DEFAULT)
        assertEquals(Why.SLOW, NetPolicy.why(NetWork.HASHING, metered = true, slow = true, rules = rules))
    }

    @Test
    fun ticksAndUnticksFollowAny() {
        val offered = NetSkip.METERED_CHOICES
        assertEquals(offered.toSet(), NetSkip.toggle(setOf(NetSkip.COVERS), NetSkip.ANY, true, offered))
        assertEquals(offered.toSet() - NetSkip.HASHING - NetSkip.ANY, NetSkip.toggle(offered.toSet(), NetSkip.HASHING, false, offered))
        assertEquals(setOf(NetSkip.COVERS, NetSkip.HASHING), NetSkip.toggle(setOf(NetSkip.COVERS), NetSkip.HASHING, true, offered))
        assertEquals(offered.toSet() - NetSkip.ANY, NetSkip.toggle(offered.toSet(), NetSkip.ANY, false, offered))
        // The slow list offers no downloads, so Any ticks only what it offers.
        assertFalse(NetSkip.DOWNLOADS in NetSkip.toggle(emptySet(), NetSkip.ANY, true, NetSkip.SLOW_CHOICES))
    }

    @Test
    fun choicesAreStoredByName() {
        val all = NetSkip.entries.toSet()
        assertEquals(all, NetSkip.parse(NetSkip.format(all)))
        assertEquals(emptySet<NetSkip>(), NetSkip.parse(""))
        assertEquals(setOf(NetSkip.HASHING), NetSkip.parse("hashing, unknown"))
        assertEquals("covers,read_ahead", NetSkip.format(setOf(NetSkip.READ_AHEAD, NetSkip.COVERS)))
    }

    @Test
    fun aShareSetSlowOrFastDecidesOverWhatItMeasured() {
        val slowOne = FakeRemoteFs.share("s1").copy(speed = ShareSpeed.SLOW)
        val fastOne = FakeRemoteFs.share("s2").copy(speed = ShareSpeed.FAST)
        val auto = FakeRemoteFs.share("s3")
        Remotes.serve(listOf(slowOne, fastOne, auto)) { throw AssertionError("no reads") }
        var now = 0L
        ShareSpeeds.clock = { now }
        try {
            // Every share measured slow: 0.5 MB/s.
            for (id in listOf("s1", "s2", "s3")) repeat(SpeedMeter.MIN_SAMPLES) {
                val meter = ShareSpeeds.meter(id)
                meter.bulkStarted()
                now += 2_000
                meter.bulkEnded(1L shl 20)
            }
            assertTrue(ShareSpeeds.slow("s1"))
            assertFalse(ShareSpeeds.slow("s2"))
            assertTrue(ShareSpeeds.slow("s3"))
            assertFalse(NetPolicy.may(NetWork.HASHING, "s3"))
            assertTrue(NetPolicy.may(NetWork.HASHING, "s2"))
            assertFalse(NetPolicy.mayAt(NetWork.READ_AHEAD, "/net/s1/a.cbz"))
            // Device files never touch the network.
            assertTrue(NetPolicy.mayAt(NetWork.READ_AHEAD, "/storage/emulated/0/a.cbz"))
        } finally {
            ShareSpeeds.clock = { System.nanoTime() / 1_000_000 }
        }
    }

    @Test
    fun theLiveDecisionAsksTheConnection() {
        Metered.probe = { Metered.Link(connected = true, notMetered = false, activeMetered = true, cellular = true) }
        Metered.forget()
        assertEquals(Why.METERED, NetPolicy.whyNot(NetWork.DOWNLOAD))
        NetPolicy.rules = NetRules(metered = setOf(NetSkip.COVERS), slow = emptySet())
        assertTrue(NetPolicy.may(NetWork.DOWNLOAD))
        assertFalse(NetPolicy.may(NetWork.COVER))
    }
}
