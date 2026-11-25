package minty.emanata.testing

import android.app.Application
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WaitsTest {

    @Test
    fun aConditionThatHoldsReturnsAtOnce() {
        var checks = 0
        idleUntil("a condition already true") { ++checks > 0 }
        awaitUntil("a condition already true") { ++checks > 0 }
        assertEquals(2, checks)
    }

    @Test
    fun idleUntilRunsWhatABackgroundThreadPostsToTheMainLooper() {
        var shown = false
        val posted = CountDownLatch(1)
        Thread {
            Handler(Looper.getMainLooper()).post { shown = true }
            posted.countDown()
        }.start()
        posted.await()
        idleUntil("the posted task") { shown }
    }

    @Test
    fun idleUntilRunsTheStepBetweenPolls() {
        var steps = 0
        idleUntil("three steps", step = { steps++ }) { steps == 3 }
        assertEquals(3, steps)
    }

    @Test
    fun awaitUntilSeesWorkOnAnotherThread() {
        val done = AtomicBoolean()
        val go = CountDownLatch(1)
        Thread {
            go.await()
            done.set(true)
        }.start()
        go.countDown()
        awaitUntil("the other thread") { done.get() }
    }

    @Test
    fun awaitJobReturnsOnceTheJobEnds() {
        val job = CompletableDeferred<Unit>()
        Thread { job.complete(Unit) }.start()
        awaitJob("the job", job)
        assertTrue(job.isCompleted)
    }

    @Test
    fun aJobThatNeverEndsFailsNamingTheWait() {
        val e = assertThrows(AssertionError::class.java) { Waits.join("the endless job", Job(), boundMs = 50) }
        assertTrue(e.message, e.message!!.contains("the endless job"))
    }

    @Test
    fun aConditionThatNeverHoldsFailsNamingTheWait() {
        val e = assertThrows(AssertionError::class.java) { Waits.poll("the moon to turn blue", boundMs = 50, step = {}) { false } }
        assertTrue(e.message, e.message!!.contains("the moon to turn blue"))
    }
}
