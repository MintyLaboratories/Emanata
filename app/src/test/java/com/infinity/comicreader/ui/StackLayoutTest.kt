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
        assertEquals(listOf(2L, 3L, 4L), orders(StackLayout.of(listOf(clip(2)), listOf(op(4), op(3)), fab = true)))
        val shown = StackLayout.of(listOf(clip(2), clip(5)), listOf(op(3), op(4)), fab = true)
        assertEquals(listOf(3L, 4L, 5L), orders(shown))
        assertEquals(1, shown.folded)
    }

    @Test
    fun withTheButtonThreeBubblesFitAndWithoutItFour() {
        val clips = (1L..6L).map { clip(it) }
        val under = StackLayout.of(clips, emptyList(), fab = true)
        assertEquals(listOf(4L, 5L, 6L), orders(under))
        assertEquals(3, under.folded)
        val alone = StackLayout.of(clips, emptyList(), fab = false)
        assertEquals(listOf(3L, 4L, 5L, 6L), orders(alone))
        assertEquals(2, alone.folded)
    }

    @Test
    fun nothingFoldedWhileAllFit() {
        val shown = StackLayout.of(listOf(clip(1)), listOf(op(2)), fab = true)
        assertEquals(listOf(1L, 2L), orders(shown))
        assertEquals(0, shown.folded)
        assertEquals(StackLayout.Shown(emptyList(), 0), StackLayout.of(emptyList(), emptyList(), fab = true))
    }

    @Test
    fun aPastedPickTakesItsPlaceInTheQueueAndFinishedOnesGo() {
        // Pick 1 was pasted as operation 7: it sits below pick 3, by its place in the queue.
        val shown = StackLayout.of(listOf(clip(3)), listOf(op(7), op(8, outcome = FileWork.Outcome.Done(null))), fab = false)
        assertEquals(listOf(3L, 7L), orders(shown))
        assertEquals(Bubble.Paste::class, shown.bubbles[0]::class)
        assertEquals(Bubble.Work::class, shown.bubbles[1]::class)
    }

    @Test
    fun aPasteAndAnOperationOfOneIdAreTwoBubbles() {
        val shown = StackLayout.of(listOf(clip(4)), listOf(op(4)), fab = true)
        assertEquals(2, shown.bubbles.map { it.key }.toSet().size)
    }
}
