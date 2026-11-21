package com.infinity.comicreader.files

import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.testing.awaitUntil
import com.infinity.comicreader.testing.idleUntil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager
import java.util.concurrent.Semaphore

/** The service that keeps file operations running in the background: notification, wake lock, Cancel, stopping. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileWorkServiceTest {

    private lateinit var context: Application

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        FileWork.reset()
    }

    @After
    fun tearDown() {
        FileWork.reset()
    }

    @Test
    fun startingAnOperationStartsTheServiceWhichHoldsTheDeviceAwakeUntilItEnds() {
        val started = ArrayList<Unit>()
        val before = FileWork.onStart
        FileWork.onStart = { started += Unit }
        val go = Semaphore(0)
        try {
            FileWork.start(FileWork.Kind.COPY, "big.cbz") { p ->
                p.report(1, 4)
                go.acquire()
                FileWork.Outcome.Done(null)
            }
        } finally {
            FileWork.onStart = before
        }
        assertEquals(1, started.size)

        val controller = Robolectric.buildService(FileWorkService::class.java, Intent(context, FileWorkService::class.java)).create().startCommand(0, 1)
        val service = controller.get()
        // The operation holds until go: starting the service put it in the foreground and took the wake lock.
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(shadowOf(service).lastForegroundNotification)
        val lock = ShadowPowerManager.getLatestWakeLock()
        assertTrue(lock.isHeld)
        assertFalse(shadowOf(service).isStoppedBySelf)

        go.release()
        idleUntil("the service to stop itself") { shadowOf(service).isStoppedBySelf }
        assertFalse(lock.isHeld)
        assertTrue(shadowOf(service).isForegroundStopped)
    }

    @Test
    fun cancelFromTheNotificationCancelsTheOperation() {
        FileWork.start(FileWork.Kind.COPY, "big.cbz") { p ->
            awaitUntil("the cancel") { !p.alive }
            throw java.util.concurrent.CancellationException()
        }
        val controller = Robolectric.buildService(FileWorkService::class.java, Intent(context, FileWorkService::class.java)).create().startCommand(0, 1)
        controller.withIntent(Intent(context, FileWorkService::class.java).setAction("cancel")).startCommand(0, 2)
        idleUntil("the operation to end") { FileWork.operations.value.firstOrNull()?.outcome != null }
        assertEquals(FileWork.Outcome.Canceled(), FileWork.operations.value.firstOrNull()?.outcome)
    }

    @Test
    fun aServiceStartedAfterTheOperationEndedStopsAtOnce() {
        val service = Robolectric.buildService(FileWorkService::class.java, Intent(context, FileWorkService::class.java)).create().startCommand(0, 1).get()
        assertTrue(shadowOf(service).isStoppedBySelf)
    }
}
