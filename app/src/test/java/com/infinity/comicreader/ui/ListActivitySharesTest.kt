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
import com.google.android.material.textfield.TextInputLayout
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.net.AesGcmBox
import com.infinity.comicreader.net.FakeRemoteFs
import com.infinity.comicreader.net.Remotes
import com.infinity.comicreader.net.SecretBox
import com.infinity.comicreader.net.ShareStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import org.robolectric.shadows.ShadowDialog
import java.io.File
import javax.crypto.spec.SecretKeySpec

/** Network shares in the library: the location picker, browsing, failures and the share form. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ListActivitySharesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Application
    private lateinit var fake: FakeRemoteFs
    private lateinit var box: SecretBox

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        for (name in listOf("ui", "library", "favorite_kinds", "shares")) context.getSharedPreferences(name, 0).edit().clear().commit()
        val device = Environment.getExternalStorageDirectory()
        File(device, "attic").mkdirs()
        Prefs(context).lastPath = File(device, "attic").absolutePath
        val dir = tmp.newFolder("share")
        File(dir, "Series").mkdirs()
        File(dir, "Series/001.cbz").writeBytes(ByteArray(10))
        File(dir, "Loose.cbz").writeBytes(ByteArray(10))
        fake = FakeRemoteFs(dir)
        box = AesGcmBox { SecretKeySpec(ByteArray(32) { it.toByte() }, "AES") }
        ShareStore.defaultBox = box
        ShareStore(context).put(FakeRemoteFs.share())
        Remotes.init(context)
        Remotes.connector = { fake }
    }

    @After
    fun tearDown() {
        Remotes.serve(emptyList()) { throw AssertionError("no shares") }
    }

    private fun idle() {
        repeat(20) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    private fun rows(recycler: RecyclerView): List<String> {
        recycler.measure(0, 0)
        recycler.layout(0, 0, 1000, 20000)
        return (0 until recycler.childCount).mapNotNull { i ->
            when (val title = recycler.getChildAt(i).findViewById<View>(R.id.title)) {
                is TitleView -> title.text.toString()
                is TextView -> title.text.toString()
                else -> null
            }
        }
    }

    private fun sheetList(): LinearLayout = ShadowDialog.getLatestDialog().findViewById(R.id.locations_list)

    private fun openSheet(activity: ListActivity) {
        activity.findViewById<CrumbBar>(R.id.pathbar).locationsButton.performClick()
        idle()
    }

    private fun row(name: String): View {
        val list = sheetList()
        return (0 until list.childCount).map { list.getChildAt(it) }
            .first { it.findViewById<TextView>(R.id.location_name).text == name }
    }

    @Test
    fun aShareIsALocationBrowsedLikeADrive() {
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        idle()
        openSheet(activity)
        assertEquals(listOf("Device", "Share A"), activity.locations?.shownRoots?.map { it.name })
        // Its free space comes from the share.
        assertEquals(
            activity.getString(R.string.location_free, "3.0 GB", "8.0 GB"),
            row("Share A").findViewById<TextView>(R.id.location_detail).text.toString()
        )
        row("Share A").performClick()
        idle()
        val bar = activity.findViewById<CrumbBar>(R.id.pathbar)
        assertEquals(listOf("Share A"), bar.crumbs.map { it.label })
        assertEquals(listOf("Loose.cbz", "Series"), rows(activity.findViewById(R.id.recycler)).sorted())

        // Into a folder: the share's name stays the root crumb.
        activity.findViewById<RecyclerView>(R.id.recycler).let { r ->
            rows(r)
            (0 until r.childCount).map { r.getChildAt(it) }
                .first { (it.findViewById<View>(R.id.title) as? TitleView)?.text?.toString() == "Series" }
                .performClick()
        }
        idle()
        assertEquals(listOf("Share A", "Series"), bar.crumbs.map { it.label })
        assertEquals(listOf("001.cbz"), rows(activity.findViewById(R.id.recycler)))
    }

    @Test
    fun anUnreachableShareSaysSoAndRetries() {
        fake.offline = true
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        idle()
        openSheet(activity)
        assertEquals(
            activity.getString(R.string.share_unreachable),
            row("Share A").findViewById<TextView>(R.id.location_detail).text.toString()
        )
        row("Share A").performClick()
        idle()
        assertEquals(activity.getString(R.string.share_unreachable_named, "Share A"), activity.findViewById<TextView>(R.id.empty_text).text)
        val retry = activity.findViewById<View>(R.id.empty_retry)
        assertEquals(View.VISIBLE, retry.visibility)
        fake.offline = false
        retry.performClick()
        idle()
        assertEquals(View.GONE, retry.visibility)
        assertEquals(listOf("Loose.cbz", "Series"), rows(activity.findViewById(R.id.recycler)).sorted())
    }

    @Test
    fun aShareIsReadOnly() {
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        idle()
        openSheet(activity)
        row("Share A").performClick()
        idle()
        activity.invalidateOptionsMenu()
        idle()
        val menu = activity.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).menu
        assertFalse(menu.findItem(R.id.menu_new_folder).isVisible)
        assertFalse(menu.findItem(R.id.menu_dupes).isVisible)
    }

    @Test
    fun theFormAddsAShareAndSaysWhatIsWrongWithAPath() {
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        idle()
        openSheet(activity)
        sheetList().findViewById<View>(R.id.location_add).performClick()
        idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.findViewById<TextView>(R.id.share_name)!!.text = "Share B"
        dialog.findViewById<TextView>(R.id.share_path)!!.text = "nas.local"
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertTrue(dialog.isShowing)
        assertEquals(
            activity.getString(R.string.share_problem_no_share),
            dialog.findViewById<TextInputLayout>(R.id.share_path_layout)!!.error.toString()
        )
        dialog.findViewById<TextView>(R.id.share_path)!!.text = "nas.local/books/more"
        dialog.findViewById<TextView>(R.id.share_user)!!.text = "user"
        dialog.findViewById<TextView>(R.id.share_password)!!.text = "pw"
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertFalse(dialog.isShowing)
        val added = ShareStore(context, box).all().last()
        assertEquals("Share B", added.name)
        assertEquals("more", added.path.dir)
        assertEquals("pw", added.password)
        assertEquals(listOf("Device", "Share A", "Share B"), activity.locations?.shownRoots?.map { it.name })
    }

    @Test
    fun aLongPressEditsOrRemovesAShare() {
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        idle()
        openSheet(activity)
        row("Share A").performLongClick()
        idle()
        val menu = ShadowDialog.getLatestDialog() as AlertDialog
        menu.listView.performItemClick(null, 1, 1)
        idle()
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals(emptyList<String>(), ShareStore(context, box).all().map { it.name })
        assertEquals(listOf("Device"), activity.locations?.shownRoots?.map { it.name })
    }

    @Test
    fun favoritesAndHistoryOnAShareShowWithoutTheNetwork() {
        val library = Library(context)
        library.toggleFavorite("/net/s1/Series")
        library.addHistory(com.infinity.comicreader.data.HistoryItem("/net/s1/Loose.cbz", "Loose.cbz", 2, 10, 1000))
        fake.offline = true
        Prefs(context).startupTab = Prefs.TAB_HISTORY
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        idle()
        assertEquals(listOf("Loose.cbz"), rows(activity.findViewById(R.id.recycler)))
        assertEquals(0, fake.stats + fake.lists)
        assertNotNull(activity)
    }
}
