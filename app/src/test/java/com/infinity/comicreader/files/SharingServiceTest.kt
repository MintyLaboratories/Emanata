package com.infinity.comicreader.files

import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class SharingServiceTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun pass(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test
    fun showsSharingWhileFilesAreReadAndStopsOnceNoneWereForAWhile() {
        val controller = Robolectric.buildService(SharingService::class.java, Intent(context, SharingService::class.java)).create().startCommand(0, 1)
        val service = shadowOf(controller.get())
        val shown = service.lastForegroundNotification!!
        assertEquals(context.getString(R.string.sharing_title), shown.extras.getCharSequence("android.title").toString())
        assertEquals(context.getString(R.string.sharing_stop), shown.actions.single().title.toString())

        // Reads keep it going.
        repeat(4) {
            pass(SharingService.IDLE_MS / 2)
            BookProvider.touch()
        }
        assertFalse(service.isStoppedBySelf)
        // Then nothing for a while.
        pass(SharingService.IDLE_MS + SharingService.CHECK_MS)
        assertTrue(service.isStoppedBySelf)
        assertTrue(service.isForegroundStopped)
    }

    @Test
    fun stopsWhenAsked() {
        val controller = Robolectric.buildService(SharingService::class.java, Intent(context, SharingService::class.java)).create().startCommand(0, 1)
        controller.withIntent(Intent(context, SharingService::class.java).setAction(SharingService.ACTION_STOP)).startCommand(0, 2)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test
    fun idleCountsFromTheLastUse() {
        assertFalse(SharingService.idle(1_000, 1_000 + SharingService.IDLE_MS - 1))
        assertTrue(SharingService.idle(1_000, 1_000 + SharingService.IDLE_MS))
    }
}
