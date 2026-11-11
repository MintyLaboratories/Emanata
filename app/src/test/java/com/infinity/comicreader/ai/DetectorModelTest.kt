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

    private val model = DetectorModel(
        installed = { installed },
        start = { optional, done -> asked += optional to done },
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
    fun aModelFetchedBeforeAnyCoverNeededItArrivesSilently() {
        model.fetch()
        finish(true)
        assertEquals(0, arrivals)
        assertTrue(model.ready())
    }
}
