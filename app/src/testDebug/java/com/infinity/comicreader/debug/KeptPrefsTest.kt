package com.infinity.comicreader.debug

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.ReaderLayout
import com.infinity.comicreader.data.TwoPage
import com.infinity.comicreader.reader.TapZones
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class KeptPrefsTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = Prefs(app)
    private val file get() = File(app.filesDir, "bench/kept.json")

    private val twoPagesByKey = BenchPlan.of(
        Intent().putExtra("do", "read").putExtra("path", "/books/a.cbz").putExtra("layout", "two")
            .putExtra("input", "key").putExtra("crop", true).putExtra("doubletap", "off")
    )!!

    private fun mine() = prefs.apply {
        pageMode = ReaderLayout.MODE_CONTINUOUS
        readingDir = ReaderLayout.DIR_RTL
        fit = "width"
        twoPage = TwoPage.LANDSCAPE
        autoCrop = false
        perSeries = true
        volumeKeys = false
        doubleTapZoom = true
        tapZones = TapZones.entries.first { it != TapZones.LEFT_RIGHT }.value
    }

    @Test
    fun aSessionsSettingsArePutBack() {
        val before = KeptPrefs.snapshot(mine()).toString()
        val kept = KeptPrefs(prefs, file)
        kept.apply(twoPagesByKey)
        assertEquals(TwoPage.ON, prefs.twoPage)
        assertTrue(prefs.autoCrop)
        assertTrue(prefs.volumeKeys)
        assertFalse(prefs.doubleTapZoom)
        assertTrue(file.exists())
        kept.restore()
        assertEquals(before, KeptPrefs.snapshot(prefs).toString())
        assertFalse(file.exists())
    }

    @Test
    fun settingsARunThatDiedLeftChangedArePutBackAtTheNextStart() {
        val before = KeptPrefs.snapshot(mine()).toString()
        // The process dies mid-run: nothing restores.
        KeptPrefs(prefs, file).apply(twoPagesByKey)
        assertTrue(KeptPrefs.restoreLeft(prefs, file))
        assertEquals(before, KeptPrefs.snapshot(prefs).toString())
        assertFalse(KeptPrefs.restoreLeft(prefs, file))
    }

    @Test
    fun aRunAfterOneThatDiedKeepsTheSettingsFromBeforeBoth() {
        val before = KeptPrefs.snapshot(mine()).toString()
        KeptPrefs(prefs, file).apply(twoPagesByKey)
        val next = KeptPrefs(prefs, file)
        next.apply(twoPagesByKey)
        next.restore()
        assertEquals(before, KeptPrefs.snapshot(prefs).toString())
    }
}
