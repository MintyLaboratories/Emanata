package com.infinity.comicreader.debug

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.data.Prefs
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The shell's debug flags: "no Play services" set, kept and cleared. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DebugFlagsReceiverTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun tearDown() {
        Prefs(app).debugNoPlayServices = false
    }

    private fun send(extra: Boolean?) {
        val intent = Intent()
        if (extra != null) intent.putExtra(DebugFlagsReceiver.NO_PLAY_SERVICES, extra)
        DebugFlagsReceiver().onReceive(app, intent)
    }

    @Test
    fun setsAndClearsNoPlayServices() {
        send(true)
        assertTrue(Prefs(app).debugNoPlayServices)
        send(null)
        assertTrue(Prefs(app).debugNoPlayServices)
        send(false)
        assertFalse(Prefs(app).debugNoPlayServices)
    }
}
