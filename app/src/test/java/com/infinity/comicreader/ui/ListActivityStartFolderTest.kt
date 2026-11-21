package com.infinity.comicreader.ui

import com.infinity.comicreader.testing.bookId
import android.Manifest
import android.app.Application
import android.app.Dialog
import android.os.Environment
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.ViewModelProvider
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
import com.infinity.comicreader.net.RemoteFile
import com.infinity.comicreader.net.RemoteFs
import com.infinity.comicreader.net.Remotes
import com.infinity.comicreader.net.ShareStore
import com.infinity.comicreader.testing.awaitJob
import com.infinity.comicreader.testing.idleUntil
import kotlinx.coroutines.Deferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
import java.util.concurrent.atomic.AtomicInteger
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
    private lateinit var retried: Retried
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
        // A held share answers only when a test (or tearDown) opens it, however long a busy machine takes.
        fake = FakeRemoteFs(dir).apply { slowCapMs = 120_000 }
        ShareStore.defaultBox = AesGcmBox { SecretKeySpec(ByteArray(32) { it.toByte() }, "AES") }
        ShareStore(context).put(FakeRemoteFs.share())
        Remotes.init(context)
        retried = Retried(fake)
        Remotes.connector = { retried }
    }

    /** [fs] as the screen holds it, counting the times it is told to try again ([Remotes.retry]): on the main thread, just before each new try. */
    private class Retried(private val fs: RemoteFs) : RemoteFs by fs {
        val retries = AtomicInteger()

        override fun retry() {
            retries.incrementAndGet()
            fs.retry()
        }
    }

    @After
    fun tearDown() {
        fake.slow?.countDown()
        Remotes.serve(emptyList()) { throw AssertionError("no shares") }
    }

    /** Runs what is due on the main thread: what a click or a dialog posts there. */
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Waits until the folder shown is listed: the screen's listing job ended. */
    private fun ListActivity.awaitListed() = idleUntil("the folder listed") { listJob?.isCompleted == true }

    /** Starts the screen and waits until its startup folder is listed. */
    private fun launch(): ListActivity = start().get().also { it.awaitListed() }

    private fun start(): ActivityController<ListActivity> = Robolectric.buildActivity(ListActivity::class.java).setup()

    /** Starts the screen and waits until it waits for its startup share, whose listing [fake] holds. */
    private fun startWaiting(): ActivityController<ListActivity> =
        start().also { c -> idleUntil("the startup share asked") { startup(c.get()).attempt != null } }

    /** What [activity] keeps of its startup share across a recreate. */
    private fun startup(activity: ListActivity) = ViewModelProvider(activity)[StartupWait::class.java]

    /** The startup share's listing under way; null once its wait is over. */
    private fun ListActivity.attempt(): Deferred<Result<List<RemoteFile>>> = startup(this).attempt!!

    /** Waits for the startup share's [attempt] to answer. */
    private fun answered(attempt: Deferred<*>) = awaitJob("the startup share's answer", attempt)

    /** The note shown at the bottom, if any. */
    private fun ListActivity.note(): String? =
        findViewById<TextView>(com.google.android.material.R.id.snackbar_text)?.text?.toString()

    /**
     * Waits for the startup share's [attempt] to answer, then for the note the screen shows
     * on it: the screen hears the answer on the main thread, after [attempt] counts as done.
     */
    private fun ListActivity.noteOn(attempt: Deferred<*>): String {
        answered(attempt)
        idleUntil("a note on the startup share's answer") { note() != null }
        return note()!!
    }

    /**
     * Moves the clock on until the startup share given up on is asked again, within
     * [ListActivity.SHARE_RETRY_MAX_MS], then waits until its answer is taken.
     */
    private fun ListActivity.nextTry() {
        val behind = startup(this).behind!!
        val taken = behind.answer
        val tries = retried.retries.get()
        val from = SystemClock.uptimeMillis()
        while (retried.retries.get() == tries) {
            assertTrue("asked again in time", SystemClock.uptimeMillis() - from < ListActivity.SHARE_RETRY_MAX_MS)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        }
        idleUntil("try ${tries + 1} answered") { behind.answer != taken }
        idle()
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

    /** Waits until a dialog other than [shown] opens, as a folder listed in the background opens one; returns it. */
    private fun dialogAfter(what: String, shown: Dialog): AlertDialog {
        idleUntil(what) { ShadowDialog.getLatestDialog().let { it !== shown && it.isShowing } }
        return ShadowDialog.getLatestDialog() as AlertDialog
    }

    @Test
    fun theDeviceOpensAtItsStartFolder() {
        prefs.setLocationStart(device.absolutePath, comics.absolutePath)
        val activity = launch()
        location(activity, "Device").performClick()
        activity.awaitListed()
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
        activity.awaitListed()
        assertEquals(activity.getString(R.string.folder_gone), ShadowToast.getTextOfLatestToast())
        assertEquals(listOf("Device"), activity.crumbs())
    }

    @Test
    fun aShareOpensAtItsStartFolder() {
        prefs.setLocationStart("/net/s1", "/net/s1/Series")
        val activity = launch()
        location(activity, "Share A").performClick()
        activity.awaitListed()
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
        // The folders of the device's top; into Comics, then Choose.
        val top = dialogAfter("the device's folders", menu)
        val names = (0 until top.listView.adapter.count).map { top.listView.adapter.getItem(it).toString() }
        shadowOf(top.listView).performItemClick(names.indexOf("Comics"))
        dialogAfter("the folders in Comics", top).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
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
        ShadowToast.reset()
        shadowOf(menu.listView).performItemClick(1)
        // Said once its listing failed, which also ends the picking.
        idleUntil("the share's failure said") { ShadowToast.getTextOfLatestToast() != null }
        assertEquals(activity.getString(R.string.share_unreachable_named, "Share A"), ShadowToast.getTextOfLatestToast())
        assertEquals("no folders offered", menu, ShadowDialog.getLatestDialog())
        assertEquals("/net/s1/Series", prefs.locationStart("/net/s1"))
    }

    /** The dialog asking to wait for the startup share, if it shows. */
    private fun waitDialog(activity: ListActivity): AlertDialog? =
        (ShadowDialog.getLatestDialog() as? AlertDialog)?.takeIf {
            it.isShowing && it.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.text == activity.getString(R.string.network_waiting_named, "Share A")
        }

    /** Moves the main thread's clock on by [ms], running what falls due. */
    private fun idleFor(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test
    fun aStartupShareSlowToAnswerAsksToWaitThenWaitsTheWholeTimeout() {
        prefs.setLocationStart(device.absolutePath, comics.absolutePath)
        prefs.lastPath = "/net/s1/Series"
        fake.slow = CountDownLatch(1)
        val activity = startWaiting().get()
        val attempt = activity.attempt()
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
        assertEquals(activity.getString(R.string.share_answering, "Share A"), activity.noteOn(attempt))
        activity.findViewById<View>(com.google.android.material.R.id.snackbar_action).performClick()
        activity.awaitListed()
        assertEquals(listOf("Share A", "Series"), activity.crumbs())
        assertEquals(listOf("001.cbz"), activity.titles())
        assertEquals(1, fake.lists)
    }

    @Test
    fun aStartupShareSpinningUpOpensOnceItAnswers() {
        prefs.lastPath = "/net/s1/Series"
        fake.slow = CountDownLatch(1)
        val activity = startWaiting().get()
        val attempt = activity.attempt()
        idleFor(ListActivity.SHARE_DIALOG_MS + 100)
        val dialog = waitDialog(activity)!!
        // A disk spinning up for 20 s, within the default timeout.
        idleFor(Remotes.SPIN_UP_MS - ListActivity.SHARE_DIALOG_MS)
        fake.slow!!.countDown()
        answered(attempt)
        activity.awaitListed()
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
        val activity = startWaiting().get()
        val attempt = activity.attempt()
        idleFor(ListActivity.SHARE_DIALOG_MS + 100)
        val dialog = waitDialog(activity)!!
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        activity.awaitListed()
        assertFalse(dialog.isShowing)
        assertEquals(listOf("Device", "Comics"), activity.crumbs())
        assertNull("asked for, so not said", ShadowToast.getTextOfLatestToast())
        fake.slow!!.countDown()
        assertEquals(activity.getString(R.string.share_answering, "Share A"), activity.noteOn(attempt))
    }

    @Test
    fun theWaitDialogClosesByItselfOnTimeout() {
        prefs.lastPath = "/net/s1/Series"
        fake.slow = CountDownLatch(1)
        val activity = startWaiting().get()
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
        val controller = startWaiting()
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
        val activity = startWaiting().get()
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
        val controller = startWaiting()
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
        val controller = startWaiting()
        val attempt = controller.get().attempt()
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
        assertEquals(activity.getString(R.string.share_answering, "Share A"), activity.noteOn(attempt))
        assertEquals(1, fake.lists)
    }

    @Test
    fun aRotationAfterGivingUpStillOffersTheShareOnceItAnswers() {
        prefs.setLocationStart(device.absolutePath, comics.absolutePath)
        prefs.lastPath = "/net/s1/Series"
        fake.slow = CountDownLatch(1)
        val controller = startWaiting()
        val attempt = controller.get().attempt()
        idleFor(Remotes.answerMs + 100)
        assertEquals(listOf("Device", "Comics"), controller.get().crumbs())
        controller.recreate()
        val activity = controller.get()
        activity.awaitListed()
        assertEquals(listOf("Device", "Comics"), activity.crumbs())
        fake.slow!!.countDown()
        assertEquals(activity.getString(R.string.share_answering, "Share A"), activity.noteOn(attempt))
        assertEquals(1, fake.lists)
    }

    @Test
    fun aRotationAsTheStartupShareFailsAsksItOnlyOnceAtATime() {
        prefs.lastPath = "/net/s1/Series"
        fake.offline = true
        fake.slow = CountDownLatch(1)
        val controller = startWaiting()
        val attempt = controller.get().attempt()
        // Its answer heard only by the screen made anew, which then gives up on it as it starts up.
        fake.slow!!.countDown()
        answered(attempt)
        controller.recreate()
        val activity = controller.get()
        activity.awaitListed()
        assertEquals(listOf("Device"), activity.crumbs())
        val tries = retried.retries.get()
        idleFor(ListActivity.SHARE_RETRY_MS)
        assertEquals("asked once", tries + 1, retried.retries.get())
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
        val activity = startWaiting().get()
        val attempt = activity.attempt()
        idleFor(Remotes.answerMs + 100)
        fake.slow!!.countDown()
        assertEquals(activity.getString(R.string.share_denied_named, "Share A"), activity.noteOn(attempt))
        // Done asking: however long it shows, never tried again.
        assertNull(startup(activity).behind)
        idleFor(ListActivity.SHARE_RETRY_MAX_MS * 2)
        assertNull(activity.note())
        assertEquals("not tried again", 0, retried.retries.get())
        assertEquals("signed in once", 1, fake.attempts)
    }

    @Test
    fun anUnreachableStartupShareIsAskedWhileShownUntilItAnswers() {
        prefs.lastPath = "/net/s1/Series"
        fake.offline = true
        val controller = start()
        val activity = controller.get()
        activity.awaitListed()
        // Past the first few tries, still asking.
        repeat(4) {
            activity.nextTry()
            assertNull(activity.note())
        }
        controller.pause().stop()
        fake.offline = false
        val tries = retried.retries.get()
        idleFor(ListActivity.SHARE_RETRY_MAX_MS * 2)
        assertEquals("not asked while hidden", tries, retried.retries.get())
        assertEquals(0, fake.lists)
        controller.start().resume()
        activity.nextTry()
        assertEquals(activity.getString(R.string.share_answering, "Share A"), activity.note())
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
    fun aStartupShareFailingBeforeTheScreenShowsSaysSoOnceItDoes() {
        prefs.lastPath = "/net/s1/Series"
        fake.offline = true
        fake.slow = CountDownLatch(1)
        val controller = Robolectric.buildActivity(ListActivity::class.java).create().start()
        val activity = controller.get()
        idleUntil("the startup share asked") { startup(activity).attempt != null }
        val attempt = activity.attempt()
        fake.slow!!.countDown()
        answered(attempt)
        activity.awaitListed()
        assertEquals(listOf("Device"), activity.crumbs())
        assertNull("not shown yet", ShadowToast.getTextOfLatestToast())
        controller.resume()
        idle()
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
