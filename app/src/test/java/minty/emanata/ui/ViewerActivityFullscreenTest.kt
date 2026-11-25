package minty.emanata.ui

import android.app.Application
import android.app.Dialog
import android.content.Intent
import android.os.Looper
import android.view.View
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import minty.emanata.R
import minty.emanata.data.Prefs
import minty.emanata.data.ResumeMode
import minty.emanata.reader.Fixtures
import minty.emanata.testing.idleUntil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** Fullscreen stays fullscreen: the reader's sheets and dialogs keep the system bars hidden. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewerActivityFullscreenTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        prefs = Prefs(context)
        prefs.resumeMode = ResumeMode.NO
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun launch(): ViewerActivity {
        val book = Fixtures.zip(File(tmp.newFolder("shelf"), "Book 01.cbz"), *(0 until 3).map { Fixtures.entry("p%02d.png".format(it), byteArrayOf(0)) }.toTypedArray())
        val intent = Intent(ApplicationProvider.getApplicationContext(), ViewerActivity::class.java)
            .putExtra(ViewerActivity.EXTRA_PATH, book.absolutePath)
        val viewer = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup().get()
        val progress = viewer.findViewById<View>(R.id.viewer_progress)
        idleUntil("the book open") { progress.visibility != View.VISIBLE }
        return viewer
    }

    /** True when [dialog]'s window hid the system bars as the reader's does. */
    private fun barsHidden(dialog: Dialog): Boolean = Immersive.hidden(dialog.window)

    private fun open(id: Int): Dialog {
        shadowOf(launched).clickMenuItem(id)
        idle()
        return latestDialogOf(launched)
    }

    private lateinit var launched: ViewerActivity

    @Test
    fun sheetsAndDialogsKeepTheBarsHidden() {
        prefs.fullscreen = true
        launched = launch()
        assertTrue(Immersive.hidden(launched.window))
        for (id in listOf(R.id.menu_tune, R.id.menu_jump)) {
            val dialog = open(id)
            assertTrue("menu item $id", barsHidden(dialog))
            dialog.dismiss()
            idle()
        }
    }

    @Test
    fun withoutFullscreenDialogsLeaveTheBarsAlone() {
        prefs.fullscreen = false
        launched = launch()
        assertFalse(Immersive.hidden(launched.window))
        val sheet = open(R.id.menu_tune)
        assertFalse(barsHidden(sheet))
        // Turned on from the sheet, the reader's own bars hide; the next dialog follows.
        sheet.findViewById<View>(R.id.sheet_fullscreen).performClick()
        idle()
        assertEquals(true, prefs.fullscreen)
        assertTrue(Immersive.hidden(launched.window))
        sheet.dismiss()
        idle()
        assertTrue(barsHidden(open(R.id.menu_jump)))
    }
}
