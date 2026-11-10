package com.infinity.comicreader.ai

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.text.Text
import com.infinity.comicreader.data.SmartCrop
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MlKitTextDetectorTest {
    /** A recognizer client answering each image with [answer], noting what it was given and when it closed. */
    private class Finder(private val answer: (Bitmap) -> Task<List<FoundLine>>) : LineFinder {
        val seen = ArrayList<Bitmap>()
        val closed = CountDownLatch(1)

        override fun find(src: Bitmap): Task<List<FoundLine>> {
            seen += src
            return answer(src)
        }

        override fun close() = closed.countDown()
    }

    private val image = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)

    private val worker = Executors.newSingleThreadExecutor()

    @After
    fun stopWorker() {
        worker.shutdownNow()
    }

    /** [d]'s lines on [image], asked on a worker thread as covers are. */
    private fun linesOff(d: MlKitTextDetector): List<SmartCrop.TextBox>? = worker.submit<List<SmartCrop.TextBox>?> { d.lines(image) }.get(5, TimeUnit.SECONDS)

    private fun upright(left: Double, top: Double, right: Double, bottom: Double) =
        FoundLine(listOf(Corner(left, top), Corner(right, top), Corner(right, bottom), Corner(left, bottom)), left, top, right, bottom, 0.8f)

    @Test
    fun itsIdIsMlKitsLatinV2() {
        assertEquals("mlkit-latin-v2", MlKitTextDetector({ Finder { Tasks.forResult(emptyList()) } }).id)
    }

    @Test
    fun refusesTheMainThread() {
        var opened = 0
        val d = MlKitTextDetector({ opened++; Finder { Tasks.forResult(emptyList()) } })
        try {
            d.lines(image)
            fail("ran on the main thread")
        } catch (e: IllegalStateException) {
            // expected
        }
        assertEquals(0, opened)
        assertFalse(d.isOpen)
    }

    @Test
    fun givesMlKitsLinesAsBoxesOnTheImage() {
        val finder = Finder { Tasks.forResult(listOf(upright(20.0, 30.0, 380.0, 110.0), upright(500.0, 0.0, 600.0, 40.0))) }
        val d = MlKitTextDetector({ finder })
        val lines = linesOff(d)!!
        assertEquals(listOf(SmartCrop.TextBox(20.0, 30.0, 380.0, 110.0, 0.8f, 80.0)), lines)
        // ML Kit reads a copy of its own.
        assertNotSame(image, finder.seen.single())
        assertEquals(400, finder.seen.single().width)
        assertTrue(finder.seen.single().isRecycled)
        assertFalse(image.isRecycled)
        d.close()
    }

    @Test
    fun noTextIsNoLines() {
        val d = MlKitTextDetector({ Finder { Tasks.forResult(emptyList()) } })
        assertEquals(emptyList<SmartCrop.TextBox>(), linesOff(d))
        d.close()
    }

    @Test
    fun opensOnceForManyCoversAndClosesWhenIdle() {
        val finders = ArrayList<Finder>()
        val d = MlKitTextDetector({ Finder { Tasks.forResult(emptyList()) }.also { finders += it } }, idleMs = 2_000)
        assertFalse(d.isOpen)
        linesOff(d)
        linesOff(d)
        assertEquals(1, finders.size)
        assertEquals(2, finders[0].seen.size)
        assertTrue(finders[0].closed.await(5, TimeUnit.SECONDS))
        assertFalse(d.isOpen)
        linesOff(d)
        assertEquals(2, finders.size)
        d.close()
    }

    @Test
    fun aFailedRunIsNullAndKeepsTheClient() {
        val finder = Finder { Tasks.forException(IllegalStateException("module missing")) }
        val d = MlKitTextDetector({ finder })
        assertNull(linesOff(d))
        assertTrue(d.isOpen)
        assertEquals(1L, finder.closed.count)
        assertTrue(finder.seen.single().isRecycled)
        d.close()
    }

    @Test
    fun noAnswerInTimeIsNullAndTheClientIsDropped() {
        val finders = ArrayList<Finder>()
        val d = MlKitTextDetector({ Finder { TaskCompletionSource<List<FoundLine>>().task }.also { finders += it } }, timeoutMs = 100)
        assertNull(linesOff(d))
        assertFalse(d.isOpen)
        assertEquals(0L, finders.single().closed.count)
        // ML Kit may still read its copy.
        assertFalse(finders.single().seen.single().isRecycled)
        assertNull(linesOff(d))
        assertEquals(2, finders.size)
    }

    @Test
    fun closingWhileACoverWaitsReturnsAtOnceAndClosesAfterIt() {
        val asked = CountDownLatch(1)
        val answer = TaskCompletionSource<List<FoundLine>>()
        val finder = Finder { asked.countDown(); answer.task }
        val d = MlKitTextDetector({ finder }, timeoutMs = 5_000)
        val run = worker.submit<List<SmartCrop.TextBox>?> { d.lines(image) }
        assertTrue(asked.await(5, TimeUnit.SECONDS))
        val start = System.nanoTime()
        d.close()
        assertFalse(d.isOpen)
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1))
        // The cover in progress keeps its client until it is done.
        assertEquals(1L, finder.closed.count)
        answer.setResult(emptyList())
        assertEquals(emptyList<SmartCrop.TextBox>(), run.get(5, TimeUnit.SECONDS))
        assertTrue(finder.closed.await(5, TimeUnit.SECONDS))
        assertFalse(d.isOpen)
    }

    @Test
    fun anInterruptedWaitLeavesTheThreadInterruptedAndKeepsTheClient() {
        val finder = Finder { TaskCompletionSource<List<FoundLine>>().task }
        val d = MlKitTextDetector({ finder }, timeoutMs = 5_000)
        val run = worker.submit<String> {
            Thread.currentThread().interrupt()
            try {
                d.lines(image)
                "returned"
            } catch (e: InterruptedException) {
                "interrupted: ${Thread.currentThread().isInterrupted}"
            }
        }
        assertEquals("interrupted: true", run.get(5, TimeUnit.SECONDS))
        assertTrue(d.isOpen)
        assertFalse(finder.seen.single().isRecycled)
        d.close()
    }

    @Test
    fun aClientThatDoesNotOpenFailsTheRun() {
        val d = MlKitTextDetector({ throw IllegalStateException("no Play services") })
        try {
            linesOff(d)
            fail("opened")
        } catch (e: java.util.concurrent.ExecutionException) {
            assertTrue(e.cause is IllegalStateException)
        }
        assertFalse(d.isOpen)
    }

    @Test
    fun classesThatDoNotLinkFailTheRunAsAnException() {
        val d = MlKitTextDetector({ throw NoClassDefFoundError("com/example/Missing") })
        try {
            linesOff(d)
            fail("opened")
        } catch (e: java.util.concurrent.ExecutionException) {
            assertTrue(e.cause is IllegalStateException)
            assertTrue(e.cause!!.cause is NoClassDefFoundError)
        }
        assertFalse(d.isOpen)
    }

    @Test
    fun creatingItStartsNothing() {
        val d = MlKitTextDetector.create(ApplicationProvider.getApplicationContext())
        assertFalse(d.isOpen)
    }

    @Test
    fun readsLinesWithoutTheirText() {
        val corners = listOf(Point(10, 20), Point(110, 30), Point(108, 50), Point(8, 40))
        val line = Text.Line("SAMPLE", Rect(8, 20, 110, 50), corners, "en", null, emptyList<Text.Element>(), 0.7f, 12f)
        val bare = Text.Line("B", Rect(1, 2, 3, 4), emptyList<Point>(), "en", null, emptyList<Text.Element>(), 0.5f, 0f)
        val text = Text("SAMPLE\nB", listOf(Text.TextBlock("SAMPLE\nB", Rect(1, 2, 110, 50), corners, "en", null, listOf(line, bare))))
        val found = MlKitTextDetector.found(text)
        assertEquals(2, found.size)
        assertEquals(corners.map { Corner(it.x.toDouble(), it.y.toDouble()) }, found[0].corners)
        assertEquals(8.0, found[0].left, 0.0)
        assertEquals(50.0, found[0].bottom, 0.0)
        assertEquals(0.7f, found[0].confidence)
        assertTrue(found[1].corners.isNullOrEmpty())
        assertEquals(FoundLine(found[1].corners, 1.0, 2.0, 3.0, 4.0, 0.5f), found[1])
    }
}
