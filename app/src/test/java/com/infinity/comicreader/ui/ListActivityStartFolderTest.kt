package com.infinity.comicreader.ui

import com.infinity.comicreader.testing.bookId
import android.Manifest
import android.app.Application
import android.os.Environment
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.infinity.comicreader.data.HistoryItem
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.ResumeMode
import com.infinity.comicreader.net.AesGcmBox
import com.infinity.comicreader.net.FakeRemoteFs
import com.infinity.comicreader.net.RemoteException
import com.infinity.comicreader.net.Remotes
import com.infinity.comicreader.net.ShareStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.time.Duration
import java.util.concurrent.CountDownLatch
import javax.crypto.spec.SecretKeySpec

/** Each location opens at its own start folder; a startup folder on a share slow to answer is waited for, or gives way to the device's. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ListActivityStartFolderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Application
    private lateinit var prefs: Prefs
    private lateinit var fake: FakeRemoteFs
    private lateinit var device: File
    private lateinit var comics: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        for (name in listOf("ui", "library", "favorite_kinds", "shares")) context.getSharedPreferences(name, 0).edit().clear().commit()
        prefs = Prefs(context)
        prefs.startupResume = ResumeMode.NO
        device = Environment.getExternalStorageDirectory()
        comics = File(device, "Comics").apply { mkdirs() }
        File(device, "attic").mkdirs()
        prefs.lastPath = File(device, "attic").absolutePath
        val dir = tmp.newFolder("share")
        File(dir, "Series").mkdirs()
        File(dir, "Series/001.cbz").writeBytes(ByteArray(10))
        File(dir, "Loose.cbz").writeBytes(ByteArray(10))
        fake = FakeRemoteFs(dir)
        ShareStore.defaultBox = AesGcmBox { SecretKeySpec(ByteArray(32) { it.toByte() }, "AES") }
        ShareStore(context).put(FakeRemoteFs.share())
        Remotes.init(context)
        Remotes.connector = { fake }
    }

    @After
    fun tearDown() {
        fake.slow?.countDown()
        Remotes.serve(emptyList()) { throw AssertionError("no shares") }
    }

    private fun idle() = repeat(20) {
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(20)
    }

    private fun launch(): ListActivity = start().get()

    private fun start(): ActivityController<ListActivity> = Robolectric.buildActivity(ListActivity::class.java).setup().also { idle() }

    /** The note shown at the bottom, if any. */
    private fun ListActivity.note(): String? =
        findViewById<TextView>(com.google.android.material.R.id.snackbar_text)?.text?.toString()

    /** Moves the clock on by up to [seconds], a few at a time, until a note shows; returns it. */
    private fun ListActivity.noteWithin(seconds: Long): String? {
        repeat((seconds / 5).toInt()) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
            repeat(3) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(5)
            }
            note()?.let { return it }
        }
        return null
    }

    private fun ListActivity.crumbs() = findViewById<CrumbBar>(R.id.pathbar).crumbs.map { it.label }

    private fun ListActivity.titles(): List<String> {
        val recycler = findViewById<RecyclerView>(R.id.recycler)
        recycler.measure(0, 0)
        recycler.layout(0, 0, 1000, 20000)
        return (0 until recycler.childCount).mapNotNull { (recycler.getChildAt(it).findViewById<View>(R.id.title) as? TitleView)?.text?.toString() }
    }

    private fun location(activity: ListActivity, name: String): View {
        activity.findViewById<CrumbBar>(R.id.pathbar).locationsButton.performClick()
        idle()
        val list = ShadowDialog.getLatestDialog().findViewById<LinearLayout>(R.id.locations_list)
        return (0 until list.childCount).map { list.getChildAt(it) }.first { it.findViewById<TextView>(R.id.location_name).text == name }
    }

    @Test
    fun theDeviceOpensAtItsStartFolder() {
        prefs.setLocationStart(device.absolutePath, comics.absolutePath)
        val activity = launch()
        location(activity, "Device").performClick()
        idle()
        assertEquals(listOf("Device", "Comics"), activity.crumbs())
    }

    @Test
    fun aDeviceStartFolderNowGoneSaysSoRatherThanEmpty() {
        prefs.setLocationStart(device.absolutePath, comics.absolutePath)
        prefs.lastPath = null
        comics.delete()
        val activity = launch()
        assertEquals(activity.getString(R.string.folder_gone), ShadowToast.getTextOfLatestToast())
        assertEquals(listOf("Device"), activity.crumbs())
        assertEquals(listOf("attic"), activity.titles())
        // Picked again from the locations: said again.
        ShadowToast.reset()
        location(activity, "Device").performClick()
        idle()
        assertEquals(activity.getString(R.string.folder_gone), ShadowToast.getTextOfLatestToast())
        assertEquals(listOf("Device"), activity.crumbs())
    }

    @Test
    fun aShareOpensAtItsStartFolder() {
        prefs.setLocationStart("/net/s1", "/net/s1/Series")
        val activity = launch()
        location(activity, "Share A").performClick()
        idle()
        assertEquals(listOf("Share A", "Series"), activity.crumbs())
        assertEquals(listOf("001.cbz"), activity.titles())
    }

    @Test
    fun theStartFolderIsPickedFromTheLocationsLongPress() {
        val activity = launch()
        location(activity, "Device").performLongClick()
        idle()
        val menu = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals(activity.getString(R.string.location_start_folder), menu.listView.adapter.getItem(0).toString())
        shadowOf(menu.listView).performItemClick(0)
        idle()
        // The folders of the device's top; into Comics, then Choose.
        val top = ShadowDialog.getLatestDialog() as AlertDialog
        val names = (0 until top.listView.adapter.count).map { top.listView.adapter.getItem(it).toString() }
        shadowOf(top.listView).performItemClick(names.indexOf("Comics"))
        idle()
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals(comics.absolutePath, prefs.locationStart(device.absolutePath))
    }

    @Test
    fun anUnreachableShareKeepsItsStartFolderAndSaysWhy() {
        prefs.setLocationStart("/net/s1", "/net/s1/Series")
        val activity = launch()
        fake.offline = true
        location(activity, "Share A").performLongClick()
        idle()
        val menu = ShadowDialog.getLatestDialog() as AlertDialog
        shadowOf(menu.listView).performItemClick(1)
        idle()
        assertEquals(activity.getString(R.string.share_unreachable_named, "Share A"), ShadowToast.getTextOfLatestToast())
        assertEquals("no folders offered", menu, ShadowDialog.getLatestDialog())
        assertEquals("/net/s1/Series", prefs.locationStart("/net/s1"))
    }

    /** The dialog asking to wait for the startup share, if it shows. */
    private fun waitDialog(activity: ListActivity): AlertDialog? =
        (ShadowDialog.getLatestDialog() as? AlertDialog)?.takeIf {
            it.isShowing && it.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.text == activity.getString(R.string.network_waiting_named, "Share A")
        }

    private fun idleFor(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        idle()
    }

    @Test
    fun aStartupShareSlowToAnswerAsksToWaitThenWaitsTheWholeTimeout() {
        prefs.setLocationStart(device.absolutePath, comics.absolutePath)
        prefs.lastPath = "/net/s1/Series"
        fake.slow = CountDownLatch(1)
        val activity = launch()
        idleFor(ListActivity.SHARE_DIALOG_MS - 500)
        assertNull(waitDialog(activity))
        idleFor(600)
        val dialog = waitDialog(activity)!!
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertFalse(dialog.isShowing)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.progress).visibility)
        // No giving up before the timeout, however slow.
        idleFor(Remotes.answerMs - ListActivity.SHARE_DIALOG_MS - 1_000)
        assertNotEquals(listOf("Device", "Comics"), activity.crumbs())
        idleFor(1_200)
        assertEquals(listOf("Device", "Comics"), activity.crumbs())
        assertEquals(activity.getString(R.string.network_wait_gave_up, "Share A", "Device"), ShadowToast.getTextOfLatestToast())
        // Still asked in the background: once it answers, it is offered, and opens without listing it again.
        fake.slow!!.countDown()
        idle()
        assertEquals(activity.getString(R.string.share_answering, "Share A"), activity.note())
        activity.findViewById<View>(com.google.android.material.R.id.snackbar_action).performClick()
        idle()
        assertEquals(listOf("Share A", "Series"), activity.crumbs())
        assertEquals(listOf("001.cbz"), activity.titles())
        assertEquals(1, fake.lists)
    }

    @Test
    fun aStartupShareSpinningUpOpensOnceItAnswers() {
        prefs.lastPath = "/net/s1/Series"
        fake.slow = CountDownLatch(1)
        val activity = launch()
        idleFor(ListActivity.SHARE_DIALOG_MS + 100)
        val dialog = waitDialog(activity)!!
        // A disk spinning up for 20 s, within the default timeout.
        idleFor(Remotes.SPIN_UP_MS - ListActivity.SHARE_DIALOG_MS)
        fake.slow!!.countDown()
        idle()
        assertFalse("closed by itself", dialog.isShowing)
        assertEquals(listOf("Share A", "Series"), activity.crumbs())
        assertEquals(listOf("001.cbz"), activity.titles())
        assertNull(ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun openDeviceGivesUpWaitingAtOnceAndOffersTheShareLater() {
        prefs.setLocationStart(device.absolutePath, comics.absolutePath)
        prefs.lastPath = "/net/s1/Series"
        fake.slow = CountDownLatch(1)
        val activity = launch()
        idleFor(ListActivity.SHARE_DIALOG_MS + 100)
        val dialog = waitDialog(activity)!!
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertFalse(dialog.isShowing)
        assertEquals(listOf("Device", "Comics"), activity.crumbs())
        assertNull("asked for, so not said", ShadowToast.getTextOfLatestToast())
        fake.slow!!.countDown()
        idle()
        assertEquals(activity.getString(R.string.share_answering, "Share A"), activity.note())
    }

    @Test
    fun theWaitDialogClosesByItselfOnTimeout() {
        prefs.lastPath = "/net/s1/Series"
        fake.slow = CountDownLatch(1)
        val activity = launch()
        idleFor(ListActivity.SHARE_DIALOG_MS + 100)
        val dialog = waitDialog(activity)!!
        idleFor(Remotes.answerMs)
        assertFalse(dialog.isShowing)
        assertEquals(listOf("Device"), activity.crumbs())
        assertEquals(activity.getString(R.string.network_wait_gave_up, "Share A", "Device"), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun theWaitDialogWaitsForTheListToBeOnTop() {
        prefs.lastPath = "/net/s1/Series"
        fake.slow = CountDownLatch(1)
        val controller = start()
        val activity = controller.get()
        // The reader, say, opened over the list.
        controller.pause()
        idleFor(ListActivity.SHARE_DIALOG_MS + 1_000)
        assertNull(waitDialog(activity))
        controller.resume()
        idleFor(500)
        waitDialog(activity)!!
    }

    @Test
    fun theWaitDialogWaitsForTheResumeOfferToBeAnswered() {
        prefs.lastPath = "/net/s1/Series"
        prefs.startupResume = ResumeMode.ASK
        val book = File(comics, "Tale 001.cbz").apply { writeBytes(ByteArray(10)) }
        Library(context).addHistory(HistoryItem(book.absolutePath, book.name, 3, 20, 1L, bookId(book)))
        fake.slow = CountDownLatch(1)
        val activity = launch()
        idleFor(ListActivity.SHARE_DIALOG_MS + 1_000)
        val offer = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals(activity.getString(R.string.startup_resume_title), offer.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.text)
        assertNull(waitDialog(activity))
        offer.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idleFor(500)
        waitDialog(activity)!!
    }

    @Test
    fun theGaveUpNoteIsNotShownOverTheReader() {
        prefs.lastPath = "/net/s1/Series"
        fake.slow = CountDownLatch(1)
        val controller = start()
        val activity = controller.get()
        // The reader, say, opened over the list.
        controller.pause()
        idleFor(Remotes.answerMs + 100)
        assertEquals(listOf("Device"), activity.crumbs())
        assertNull(ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun aRotationKeepsWaitingWhereItWas() {
        prefs.setLocationStart(device.absolutePath, comics.absolutePath)
        prefs.lastPath = "/net/s1/Series"
        fake.slow = CountDownLatch(1)
        val controller = start()
        idleFor(ListActivity.SHARE_DIALOG_MS + 100)
        waitDialog(controller.get())!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        controller.recreate()
        idle()
        val activity = controller.get()
        // "Wait" was chosen: not asked again.
        idleFor(ListActivity.SHARE_DIALOG_MS + 100)
        assertNull(waitDialog(activity))
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.progress).visibility)
        // The timeout runs from the first start, not the recreate.
        idleFor(Remotes.answerMs - 2 * ListActivity.SHARE_DIALOG_MS - 1_000)
        assertNotEquals(listOf("Device", "Comics"), activity.crumbs())
        idleFor(1_200)
        assertEquals(listOf("Device", "Comics"), activity.crumbs())
        // One listing all along, offered once it answers.
        fake.slow!!.countDown()
        idle()
        assertEquals(activity.getString(R.string.share_answering, "Share A"), activity.note())
        assertEquals(1, fake.lists)
    }

    @Test
    fun aStartupShareAnsweringAtOnceAsksNothing() {
        prefs.lastPath = "/net/s1/Series"
        val activity = launch()
        idleFor(ListActivity.SHARE_DIALOG_MS + 100)
        assertNull(waitDialog(activity))
        assertEquals(listOf("Share A", "Series"), activity.crumbs())
    }

    @Test
    fun aStartupShareRefusingLateSaysWhyWithoutSigningInAgain() {
        prefs.lastPath = "/net/s1/Series"
        fake.slow = CountDownLatch(1)
        fake.refuse = RemoteException.Reason.DENIED
        val activity = launch()
        idleFor(Remotes.answerMs + 100)
        fake.slow!!.countDown()
        idle()
        assertEquals(activity.getString(R.string.share_denied_named, "Share A"), activity.note())
        assertNull(activity.noteWithin(ListActivity.SHARE_RETRY_MAX_MS / 1000 * 2))
        assertEquals("signed in once", 1, fake.attempts)
    }

    @Test
    fun anUnreachableStartupShareIsAskedWhileShownUntilItAnswers() {
        prefs.lastPath = "/net/s1/Series"
        fake.offline = true
        val controller = start()
        val activity = controller.get()
        // Past the first few tries, still asking.
        assertNull(activity.noteWithin(600))
        controller.pause().stop()
        fake.offline = false
        assertNull(activity.noteWithin(600))
        assertEquals("not asked while hidden", 0, fake.lists)
        controller.start().resume()
        assertEquals(activity.getString(R.string.share_answering, "Share A"), activity.noteWithin(ListActivity.SHARE_RETRY_MAX_MS / 1000 + 10))
        assertEquals(1, fake.lists)
    }

    @Test
    fun anUnreachableStartupShareGivesWayToTheDevice() {
        prefs.lastPath = "/net/s1/Series"
        fake.offline = true
        val activity = launch()
        assertEquals(listOf("Device"), activity.crumbs())
        assertEquals(activity.getString(R.string.network_wait_gave_up, "Share A", "Device"), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun aStartupShareThatRefusesSaysWhy() {
        prefs.lastPath = "/net/s1/Series"
        fake.refuse = RemoteException.Reason.DENIED
        val activity = launch()
        assertEquals(listOf("Share A", "Series"), activity.crumbs())
        assertEquals(activity.getString(R.string.share_denied_named, "Share A"), activity.findViewById<TextView>(R.id.empty_text).text)
    }
}
