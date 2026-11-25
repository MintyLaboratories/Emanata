package minty.emanata.data

import minty.emanata.data.ResumeMode.Start
import org.junit.Assert.assertEquals
import org.junit.Test

class ResumeModeTest {

    @Test
    fun valuesRoundTripAndUnknownIsAsk() {
        for (m in ResumeMode.entries) assertEquals(m, ResumeMode.of(m.value))
        assertEquals(ResumeMode.ASK, ResumeMode.of(null))
        assertEquals(ResumeMode.ASK, ResumeMode.of("sometimes"))
    }

    @Test
    fun dontAskAgainMakesTheAnswerTheMode() {
        assertEquals(ResumeMode.YES, ResumeMode.ASK.afterAnswer(resume = true, dontAsk = true))
        assertEquals(ResumeMode.NO, ResumeMode.ASK.afterAnswer(resume = false, dontAsk = true))
        assertEquals(ResumeMode.ASK, ResumeMode.ASK.afterAnswer(resume = true, dontAsk = false))
        assertEquals(ResumeMode.ASK, ResumeMode.ASK.afterAnswer(resume = false, dontAsk = false))
    }

    @Test
    fun savedPositionFollowsTheMode() {
        assertEquals(Start(7, ask = true), ResumeMode.ASK.start(-1, saved = 7, pageCount = 20, finished = false))
        assertEquals(Start(7, ask = false), ResumeMode.YES.start(-1, saved = 7, pageCount = 20, finished = false))
        assertEquals(Start(0, ask = false), ResumeMode.NO.start(-1, saved = 7, pageCount = 20, finished = false))
    }

    @Test
    fun noSavedPositionNeverAsks() {
        for (m in ResumeMode.entries) {
            assertEquals(Start(0, ask = false), m.start(-1, saved = 0, pageCount = 20, finished = false))
            // A position past the end belongs to another version of the book.
            assertEquals(Start(0, ask = false), m.start(-1, saved = 20, pageCount = 20, finished = false))
            assertEquals(Start(0, ask = false), m.start(-1, saved = -1, pageCount = 20, finished = false))
        }
    }

    @Test
    fun finishedBookOpensAtTheFirstPage() {
        for (m in ResumeMode.entries) {
            assertEquals(Start(0, ask = false), m.start(-1, saved = 7, pageCount = 20, finished = true))
        }
    }

    @Test
    fun requestedPageWins() {
        for (m in ResumeMode.entries) {
            assertEquals(Start(3, ask = false), m.start(3, saved = 7, pageCount = 20, finished = false))
            assertEquals(Start(0, ask = false), m.start(0, saved = 7, pageCount = 20, finished = false))
            assertEquals(Start(5, ask = false), m.start(5, saved = 2, pageCount = 20, finished = true))
            assertEquals(Start(19, ask = false), m.start(40, saved = 2, pageCount = 20, finished = false))
        }
    }
}
