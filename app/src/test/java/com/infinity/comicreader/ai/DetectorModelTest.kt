package com.infinity.comicreader.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The text detector's model downloads when first needed, as optional work, without asking too often, and tells of its arrival once. */
class DetectorModelTest {
    private var installed = false
    private var clock = 0L
    private var arrivals = 0

    /** Downloads asked for: whether optional, and the call that ends each. */
    private val asked = ArrayList<Pair<Boolean, (Boolean) -> Unit>>()

    /** True while the network policy holds optional downloads back; [holds] counts the requests it held. */
    private var held = false
    private var holds = 0

    /** Runs once, inside the next look at whether the model is in place. */
    private var duringLook: (() -> Unit)? = null

    private val model = DetectorModel(
        installed = {
            val now = installed
            duringLook?.also { duringLook = null }?.invoke()
            now
        },
        start = { optional, done ->
            if (optional && held) {
                holds++
                false
            } else {
                asked += optional to done
                true
            }
        },
        arrived = { arrivals++ },
        now = { clock }
    )

    /** Ends the last download asked for: the model in place when [ok]. */
    private fun finish(ok: Boolean) {
        if (ok) installed = true
        asked.last().second(ok)
    }

    @Test
    fun aModelInPlaceIsReadyWithoutADownload() {
        installed = true
        assertTrue(model.ready())
        assertTrue(asked.isEmpty())
        assertEquals("no cover went without it", 0, arrivals)
    }

    @Test
    fun aMissingModelDownloadsOnceAsOptionalWorkAndArrivesOnce() {
        assertFalse(model.ready())
        assertFalse(model.ready())
        assertEquals("never twice at once", listOf(true), asked.map { it.first })
        finish(true)
        assertEquals(1, arrivals)
        assertTrue(model.ready())
        assertEquals(1, arrivals)
        assertEquals(1, asked.size)
    }

    @Test
    fun aModelNoticedInPlaceBeforeItsDownloadEndsArrivesOnce() {
        assertFalse(model.ready())
        installed = true
        assertTrue(model.ready())
        assertEquals(1, arrivals)
        asked.last().second(true)
        assertEquals(1, arrivals)
    }

    @Test
    fun aFailedDownloadIsAskedForAgainOnlyAfterAWhileAndNotPastItsTries() {
        assertFalse(model.ready())
        finish(false)
        assertFalse(model.ready())
        assertEquals(1, asked.size)
        repeat(DetectorModel.TRIES - 1) {
            clock += DetectorModel.RETRY_MS
            assertFalse(model.ready())
            finish(false)
        }
        assertEquals(DetectorModel.TRIES, asked.size)
        clock += DetectorModel.RETRY_MS
        assertFalse(model.ready())
        assertEquals("given up for the session", DetectorModel.TRIES, asked.size)
        assertEquals(0, arrivals)
    }

    @Test
    fun theUserCanAskForItWhateverTheTries() {
        assertFalse(model.ready())
        finish(false)
        model.fetch()
        assertEquals(listOf(true, false), asked.map { it.first })
        model.fetch()
        assertEquals("not while one is on its way", 2, asked.size)
        finish(true)
        assertEquals(1, arrivals)
        model.fetch()
        assertEquals("nothing to fetch once in place", 2, asked.size)
    }

    @Test
    fun aDownloadThatDoesNotStartEndsAsFailed() {
        var tries = 0
        val broken = DetectorModel({ false }, { _, _ -> tries++; throw IllegalStateException("no") }, {}, now = { clock })
        assertFalse(broken.ready())
        broken.fetch()
        assertEquals("not left running", 2, tries)
    }

    @Test
    fun aDownloadHeldBackByTheNetworkPolicyIsNoFailureAndIsAskedAgainSoon() {
        held = true
        repeat(DetectorModel.TRIES + 2) {
            assertFalse(model.ready())
            assertFalse("not again at once", model.ready())
            clock += DetectorModel.HELD_MS
        }
        assertEquals(DetectorModel.TRIES + 2, holds)
        assertTrue(asked.isEmpty())
        // On a link the policy allows, the next cover asks at once: no wait, no tries used up.
        held = false
        assertFalse(model.ready())
        assertEquals(listOf(true), asked.map { it.first })
        finish(true)
        assertEquals(1, arrivals)
    }

    @Test
    fun aHoldDoesNotStopTheUser() {
        held = true
        assertFalse(model.ready())
        model.fetch()
        assertEquals(listOf(false), asked.map { it.first })
    }

    @Test
    fun aDownloadEndingWhileACoverLooksLeavesTheModelReady() {
        model.fetch()
        // The user's download ends after the cover found the model missing, before it noted so.
        duringLook = { finish(true) }
        installed = false
        assertTrue(model.ready())
        assertEquals("no cover went without it", 0, arrivals)
        assertTrue(model.ready())
    }

    @Test
    fun aModelFetchedBeforeAnyCoverNeededItArrivesSilently() {
        model.fetch()
        finish(true)
        assertEquals(0, arrivals)
        assertTrue(model.ready())
    }
}
