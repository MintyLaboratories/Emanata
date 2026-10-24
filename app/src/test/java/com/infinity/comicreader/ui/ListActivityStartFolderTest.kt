package com.infinity.comicreader.ui

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
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.ResumeMode
import com.infinity.comicreader.net.AesGcmBox
import com.infinity.comicreader.net.FakeRemoteFs
import com.infinity.comicreader.net.RemoteException
import com.infinity.comicreader.net.Remotes
import com.infinity.comicreader.net.ShareStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.time.Duration
import java.util.concurrent.CountDownLatch
import javax.crypto.spec.SecretKeySpec

/** Each location opens at its own start folder; a startup folder on a share slow to answer gives way to the device's. */
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

    private fun launch(): ListActivity = Robolectric.buildActivity(ListActivity::class.java).setup().get().also { idle() }

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
    fun aStartupShareSlowToAnswerGivesWayToTheDevice() {
        prefs.setLocationStart(device.absolutePath, comics.absolutePath)
        prefs.lastPath = "/net/s1/Series"
        fake.slow = CountDownLatch(1)
        val activity = launch()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ListActivity.SHARE_WAIT_MS + 100))
        idle()
        assertEquals(listOf("Device", "Comics"), activity.crumbs())
        assertEquals(activity.getString(R.string.share_slow_start, "Share A"), ShadowToast.getTextOfLatestToast())
        // Still asked in the background: once it answers, browsing it needs no new wait.
        fake.slow!!.countDown()
        idle()
        assertEquals(1, fake.lists)
    }

    @Test
    fun anUnreachableStartupShareGivesWayToTheDevice() {
        prefs.lastPath = "/net/s1/Series"
        fake.offline = true
        val activity = launch()
        assertEquals(listOf("Device"), activity.crumbs())
        assertEquals(activity.getString(R.string.share_slow_start, "Share A"), ShadowToast.getTextOfLatestToast())
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
