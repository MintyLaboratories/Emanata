package com.infinity.comicreader.ui

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.text.format.Formatter
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.net.FakeRemoteFs
import com.infinity.comicreader.net.Metered
import com.infinity.comicreader.net.Remotes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.io.RandomAccessFile

/** A large book on a share, opened on a metered connection, asks first: Open anyway or Cancel. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LargeBookWarningTest {
    /** Counts the books it is told to open, in every instance. */
    class HostActivity : AppCompatActivity(), LargeBookWarning.Host {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_ComicReader)
            super.onCreate(savedInstanceState)
        }

        override fun openConfirmed(path: String, page: Int) {
            opens += path to page
        }

        companion object {
            val opens = ArrayList<Pair<String, Int>>()
        }
    }

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var controller: ActivityController<HostActivity>
    private val activity: HostActivity get() = controller.get()
    private val opened: Int get() = HostActivity.opens.size

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        val dir = tmp.newFolder("share")
        RandomAccessFile(File(dir, "Large.cbz"), "rw").use { it.setLength(150_000_000) }
        RandomAccessFile(File(dir, "Small.cbz"), "rw").use { it.setLength(50_000_000) }
        val fake = FakeRemoteFs(dir)
        Remotes.serve(listOf(FakeRemoteFs.share())) { fake }
        HostActivity.opens.clear()
        controller = Robolectric.buildActivity(HostActivity::class.java).setup()
        metered(true)
    }

    @After
    fun tearDown() {
        metered(false)
        Remotes.serve(emptyList()) { throw AssertionError("no shares") }
    }

    private fun metered(on: Boolean) {
        Metered.probe = { Metered.Link(connected = true, notMetered = !on, activeMetered = on, cellular = on) }
        Metered.forget()
    }

    private fun idle() {
        repeat(50) {
            shadowOf(Looper.getMainLooper()).idle()
            if (opened > 0 || ShadowDialog.getLatestDialog()?.isShowing == true) return
            Thread.sleep(10)
        }
    }

    private fun check(path: String, askNetwork: Boolean = true) {
        ShadowDialog.reset()
        LargeBookWarning.check(activity, Remotes.file(path, askNetwork), 3)
        idle()
    }

    private fun shown(): AlertDialog? = (ShadowDialog.getLatestDialog() as? AlertDialog)?.takeIf { it.isShowing }

    @Test
    fun theRule() {
        assertTrue(LargeBookWarning.needed(remote = true, metered = true, enabled = true, thresholdMb = 100, bytes = 100_000_001))
        assertFalse(LargeBookWarning.needed(remote = true, metered = true, enabled = true, thresholdMb = 100, bytes = 100_000_000))
        assertFalse(LargeBookWarning.needed(remote = false, metered = true, enabled = true, thresholdMb = 100, bytes = 1L shl 40))
        assertFalse(LargeBookWarning.needed(remote = true, metered = false, enabled = true, thresholdMb = 100, bytes = 1L shl 40))
        assertFalse(LargeBookWarning.needed(remote = true, metered = true, enabled = false, thresholdMb = 100, bytes = 1L shl 40))
    }

    @Test
    fun aLargeBookAsksAndOpenAnywayOpensIt() {
        check("/net/s1/Large.cbz")
        assertEquals(0, opened)
        val dialog = shown()!!
        val size = Formatter.formatShortFileSize(context, 150_000_000)
        assertEquals(context.getString(R.string.big_book_msg, "Large.cbz", size), dialog.findViewById<TextView>(android.R.id.message)!!.text.toString())
        assertEquals(context.getString(R.string.big_book_open), dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals(listOf("/net/s1/Large.cbz" to 3), HostActivity.opens)
    }

    @Test
    fun aBookFromHistoryOrTheLibraryAsksItsShareForTheSize() {
        // Such files never ask the share for their stat; the warning asks anyway, off the main thread.
        check("/net/s1/Large.cbz", askNetwork = false)
        assertEquals(0, opened)
        assertTrue(shown() != null)
    }

    @Test
    fun aSecondTapWhileTheWarningIsOpenOrTheSizeIsAskedForAsksNothing() {
        check("/net/s1/Large.cbz", askNetwork = false)
        val first = shown()!!
        LargeBookWarning.check(activity, Remotes.file("/net/s1/Large.cbz"), 3)
        idle()
        assertTrue(ShadowDialog.getShownDialogs().count { it.isShowing } == 1)
        first.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        // Two taps before the size is known open the small book once.
        LargeBookWarning.check(activity, Remotes.file("/net/s1/Small.cbz", askNetwork = false), 0)
        LargeBookWarning.check(activity, Remotes.file("/net/s1/Small.cbz", askNetwork = false), 0)
        repeat(30) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        assertEquals(1, opened)
    }

    @Test
    fun theWarningStaysAcrossARotationAndOpensThroughTheNewActivity() {
        check("/net/s1/Large.cbz")
        controller.recreate()
        idle()
        val dialog = shown()!!
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals(listOf("/net/s1/Large.cbz" to 3), HostActivity.opens)
    }

    @Test
    fun cancelOpensNothing() {
        check("/net/s1/Large.cbz")
        shown()!!.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertEquals(0, opened)
    }

    @Test
    fun aSmallBookOrAnUnmeteredConnectionOpensAtOnce() {
        check("/net/s1/Small.cbz")
        assertEquals(1, opened)
        metered(false)
        check("/net/s1/Large.cbz")
        assertEquals(2, opened)
        assertEquals(null, shown())
    }

    @Test
    fun theSizeAndSwitchAreSettings() {
        Prefs(context).meteredWarnMb = 200
        check("/net/s1/Large.cbz")
        assertEquals(1, opened)
        Prefs(context).meteredWarnMb = 100
        Prefs(context).meteredWarn = false
        check("/net/s1/Large.cbz")
        assertEquals(2, opened)
    }

    @Test
    fun aBookOnTheDeviceNeverAsks() {
        val local = tmp.newFile("Local.cbz").apply { RandomAccessFile(this, "rw").use { it.setLength(500_000_000) } }
        LargeBookWarning.check(activity, local, 0)
        assertEquals(1, opened)
    }
}
