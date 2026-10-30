package com.infinity.comicreader.ui

import com.infinity.comicreader.files.FileClipboard
import com.infinity.comicreader.files.FileWork
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which bubbles the operation stack shows, in which order, and how many it folds away. */
class StackLayoutTest {

    private fun clip(id: Long) = FileClipboard.Clip(listOf(FileClipboard.Item("/s/$id.cbz", false)), move = id % 2 == 0L, id = id)

    private fun op(id: Long, outcome: FileWork.Outcome? = null) =
        FileWork.Status(FileWork.Kind.COPY, "$id.cbz", null, id = id, outcome = outcome)

    private fun orders(shown: StackLayout.Shown) = shown.bubbles.map { it.order }

    @Test
    fun picksAndOperationsShowOldestOnTopNewestAtTheBottom() {
        assertEquals(listOf(2L, 3L, 4L), orders(StackLayout.of(listOf(clip(2)), listOf(op(4), op(3)))))
        val shown = StackLayout.of(listOf(clip(2), clip(5)), listOf(op(3), op(4)))
        assertEquals(listOf(3L, 4L, 5L), orders(shown))
        assertEquals(1, shown.folded)
    }

    @Test
    fun threeBubblesFitAboveTheButtonsPlace() {
        val clips = (1L..6L).map { clip(it) }
        val shown = StackLayout.of(clips, emptyList())
        assertEquals(listOf(4L, 5L, 6L), orders(shown))
        assertEquals(3, shown.folded)
    }

    @Test
    fun nothingFoldedWhileAllFit() {
        val shown = StackLayout.of(listOf(clip(1)), listOf(op(2)))
        assertEquals(listOf(1L, 2L), orders(shown))
        assertEquals(0, shown.folded)
        assertEquals(StackLayout.Shown(emptyList(), emptyList()), StackLayout.of(emptyList(), emptyList()))
    }

    @Test
    fun aPastedPickTakesItsPlaceInTheQueueAndFinishedOnesGo() {
        // Pick 1 was pasted as operation 7: it sits below pick 3, by its place in the queue.
        val shown = StackLayout.of(listOf(clip(3)), listOf(op(7), op(8, outcome = FileWork.Outcome.Done(null))))
        assertEquals(listOf(3L, 7L), orders(shown))
        assertEquals(Bubble.Paste::class, shown.bubbles[0]::class)
        assertEquals(Bubble.Work::class, shown.bubbles[1]::class)
    }

    @Test
    fun theOperationUnderWayAlwaysShowsAndTheRestFold() {
        val running = op(1).copy(started = 0)
        val shown = StackLayout.of(listOf(clip(2), clip(3), clip(4)), listOf(running, op(5)))
        assertEquals(listOf(1L, 4L, 5L), orders(shown))
        assertEquals(listOf(2L, 3L), shown.hidden.map { it.order })
        assertEquals(2, shown.folded)
    }

    @Test
    fun aPasteAndAnOperationOfOneIdAreTwoBubbles() {
        val shown = StackLayout.of(listOf(clip(4)), listOf(op(4)))
        assertEquals(2, shown.bubbles.map { it.key }.toSet().size)
    }
}
