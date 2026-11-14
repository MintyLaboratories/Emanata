package com.infinity.comicreader.ui

import com.infinity.comicreader.data.BookRef
import com.infinity.comicreader.data.BookId
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
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.textfield.TextInputLayout
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.SortKey
import com.infinity.comicreader.data.SortLevel
import com.infinity.comicreader.data.TabSort
import com.infinity.comicreader.net.AesGcmBox
import com.infinity.comicreader.net.DavUrl
import com.infinity.comicreader.net.FakeRemoteFs
import com.infinity.comicreader.net.Metered
import com.infinity.comicreader.net.Remotes
import com.infinity.comicreader.net.SecretBox
import com.infinity.comicreader.net.ShareSpeed
import com.infinity.comicreader.net.ShareSpeeds
import com.infinity.comicreader.net.ShareStore
import com.infinity.comicreader.net.SpeedMeter
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
import org.robolectric.shadows.ShadowToast
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
        Metered.probe = { Metered.Link(connected = true, notMetered = true, activeMetered = false) }
        Metered.forget()
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
    fun aShareWithoutThumbnailsIsNotReadForBookIds() {
        ShareStore(context).put(FakeRemoteFs.share().copy(thumbnails = false))
        Remotes.init(context)
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        idle()
        openSheet(activity)
        row("Share A").performClick()
        idle()
        assertEquals(listOf("Loose.cbz", "Series"), rows(activity.findViewById(R.id.recycler)).sorted())
        idle()
        assertEquals(0L, fake.bytesRead)
    }

    @Test
    fun onAMeteredConnectionShareBooksAreNotReadForPageCountsAndANoteSaysSo() {
        ShareStore(context).put(FakeRemoteFs.share().copy(thumbnails = false))
        Remotes.init(context)
        Prefs(context).setSort(Prefs.TAB_BROWSE, TabSort(SortLevel(SortKey.LENGTH)))
        Metered.probe = { Metered.Link(connected = true, notMetered = false, activeMetered = true) }
        Metered.forget()
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        idle()
        openSheet(activity)
        row("Share A").performClick()
        idle()
        assertEquals(listOf("Loose.cbz", "Series"), rows(activity.findViewById(R.id.recycler)).sorted())
        idle()
        assertEquals(0L, fake.bytesRead)
        val note = activity.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
        assertEquals(activity.getString(R.string.facts_metered), note?.text?.toString())

        // Unmetered, the next listing reads them.
        Metered.probe = { Metered.Link(connected = true, notMetered = true, activeMetered = false) }
        Metered.forget()
        openSheet(activity)
        row("Device").performClick()
        idle()
        openSheet(activity)
        row("Share A").performClick()
        idle()
        assertTrue(fake.bytesRead > 0)
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
    fun aShareTakesNewFoldersButIsNotSearchedForDuplicates() {
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        idle()
        openSheet(activity)
        row("Share A").performClick()
        idle()
        activity.invalidateOptionsMenu()
        idle()
        val menu = activity.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).menu
        assertTrue(menu.findItem(R.id.menu_new_folder).isVisible)
        assertFalse(menu.findItem(R.id.menu_dupes).isVisible)
        // Once the settings include shares in the similar search, duplicates are; the largest files never are.
        Prefs(context).similarShares = true
        activity.invalidateOptionsMenu()
        idle()
        assertTrue(menu.findItem(R.id.menu_dupes).isVisible)
        assertFalse(menu.findItem(R.id.menu_large).isVisible)
    }

    @Test
    fun theFormAddsAShareAndSaysWhatIsWrongWithAPath() {
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        idle()
        openSheet(activity)
        sheetList().findViewById<View>(R.id.location_add).performClick()
        idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        // A share not added yet has no speed measured to show.
        assertEquals(View.GONE, dialog.findViewById<TextView>(R.id.share_speed_measured)!!.visibility)
        assertEquals(activity.getString(R.string.share_speed_hint_auto), dialog.findViewById<TextView>(R.id.share_speed_hint)!!.text.toString())
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
    fun theFormAddsAWebDavShareAndEditsItAsOne() {
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        idle()
        openSheet(activity)
        sheetList().findViewById<View>(R.id.location_add).performClick()
        idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val pathLayout = dialog.findViewById<TextInputLayout>(R.id.share_path_layout)!!
        assertEquals(activity.getString(R.string.share_path), pathLayout.hint.toString())
        dialog.findViewById<TextView>(R.id.share_path)!!.text = "https://nas.local/dav"
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals(activity.getString(R.string.share_problem_web), pathLayout.error.toString())
        dialog.findViewById<View>(R.id.share_kind_webdav)!!.performClick()
        idle()
        assertEquals(activity.getString(R.string.share_address), pathLayout.hint.toString())
        assertEquals(null, pathLayout.error)
        dialog.findViewById<TextView>(R.id.share_path)!!.text = "http://nas.example/dav"
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals(activity.getString(R.string.share_problem_insecure), pathLayout.error.toString())
        // Plain http within the local network is taken, with a warning.
        dialog.findViewById<TextView>(R.id.share_path)!!.text = "http://192.168.200.7/dav"
        idle()
        assertEquals(activity.getString(R.string.share_http_warning), pathLayout.helperText.toString())
        dialog.findViewById<TextView>(R.id.share_path)!!.text = "https://nas.local/dav/comics"
        dialog.findViewById<TextView>(R.id.share_user)!!.text = "user"
        dialog.findViewById<TextView>(R.id.share_password)!!.text = "pw"
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertFalse(dialog.isShowing)
        val added = ShareStore(context, box).all().last()
        assertEquals("comics", added.name)
        assertEquals(DavUrl(true, "nas.local", 443, "dav/comics"), added.path)
        assertEquals("pw", added.password)
        assertEquals(listOf("Device", "Share A", "comics"), activity.locations?.shownRoots?.map { it.name })

        // Editing it opens on WebDAV with its address.
        openSheet(activity)
        row("comics").performLongClick()
        idle()
        (ShadowDialog.getLatestDialog() as AlertDialog).listView.performItemClick(null, 0, 0)
        idle()
        val edit = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals(R.id.share_kind_webdav, edit.findViewById<MaterialButtonToggleGroup>(R.id.share_kind)!!.checkedButtonId)
        assertEquals("https://nas.local/dav/comics", edit.findViewById<TextView>(R.id.share_path)!!.text.toString())
        assertEquals(activity.getString(R.string.share_address), edit.findViewById<TextInputLayout>(R.id.share_path_layout)!!.hint.toString())
    }

    @Test
    fun aShareFolderFoundGoneGoesToItsNearestParent() {
        Prefs(context).lastPath = "/net/s1/Series/Gone/Deeper"
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        idle()
        assertEquals(listOf("Share A", "Series"), activity.findViewById<CrumbBar>(R.id.pathbar).crumbs.map { it.label })
        assertEquals(listOf("001.cbz"), rows(activity.findViewById(R.id.recycler)))
        assertEquals(activity.getString(R.string.folder_gone), ShadowToast.getTextOfLatestToast())
        assertEquals(1, ShadowToast.shownToastCount())
        assertEquals(View.GONE, activity.findViewById<View>(R.id.empty_retry).visibility)
    }

    @Test
    fun pathsOnARemovedShareCountAsGone() {
        Prefs(context).lastPath = "/net/gone1/Series"
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        idle()
        assertEquals(listOf("Device"), activity.findViewById<CrumbBar>(R.id.pathbar).crumbs.map { it.label })
        val orphan = Remotes.file("/net/gone1/Series/001.cbz", askNetwork = false)
        assertFalse(orphan.exists())
        assertFalse(orphan.isFile)
        assertFalse(Remotes.file("/net/gone1/Series", askNetwork = false).isDirectory)
    }

    @Test
    fun aKeystoreFailureKeepsTheFormOpenSayingSo() {
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        idle()
        openSheet(activity)
        ShareStore.defaultBox = object : SecretBox {
            override fun seal(plain: ByteArray): ByteArray = throw java.security.GeneralSecurityException("keystore")
            override fun open(sealed: ByteArray): ByteArray = box.open(sealed)
        }
        sheetList().findViewById<View>(R.id.location_add).performClick()
        idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.findViewById<TextView>(R.id.share_path)!!.text = "nas.local/books"
        dialog.findViewById<TextView>(R.id.share_password)!!.text = "pw"
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertTrue(dialog.isShowing)
        assertEquals(
            activity.getString(R.string.share_save_failed),
            dialog.findViewById<TextInputLayout>(R.id.share_password_layout)!!.error.toString()
        )
        assertEquals(listOf("Share A"), ShareStore(context, box).all().map { it.name })
    }

    /** Removes "Share A" from the location picker, ticking "forget its library" when [forget]. */
    private fun removeShare(activity: ListActivity, forget: Boolean) {
        openSheet(activity)
        row("Share A").performLongClick()
        idle()
        val menu = ShadowDialog.getLatestDialog() as AlertDialog
        val labels = (0 until menu.listView.adapter.count).map { menu.listView.adapter.getItem(it).toString() }
        val remove = labels.indexOf(activity.getString(R.string.share_remove))
        menu.listView.performItemClick(null, remove, remove.toLong())
        idle()
        val confirm = ShadowDialog.getLatestDialog() as AlertDialog
        val check = confirm.findViewById<android.widget.CheckBox>(R.id.dialog_check)!!
        assertFalse("not ticked at first", check.isChecked)
        assertEquals(activity.getString(R.string.share_remove_library), check.text.toString())
        check.isChecked = forget
        confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
    }

    /** A book on the share, as the library keeps it. */
    private val loose = BookRef(BookId(0x100), "/net/s1/Loose.cbz")

    private fun fillLibrary(): Library = Library(context).apply {
        toggleFavoriteFolder("/net/s1/Series")
        addHistory(com.infinity.comicreader.data.HistoryItem(loose.path, "Loose.cbz", 2, 10, 1000, loose.id))
        addBookmark(com.infinity.comicreader.data.Bookmark(loose.path, "Loose.cbz", 3, "", 1000, loose.id))
        toggleFavoriteFolder("/books/attic")
    }

    @Test
    fun aLongPressEditsOrRemovesAShare() {
        val prefs = Prefs(context)
        prefs.setLocationStart("/net/s1", "/net/s1/Series")
        val library = fillLibrary()
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        idle()
        removeShare(activity, forget = false)
        assertEquals(emptyList<String>(), ShareStore(context, box).all().map { it.name })
        assertEquals(listOf("Device"), activity.locations?.shownRoots?.map { it.name })
        assertEquals("its start folder goes with it", "/net/s1", prefs.locationStart("/net/s1"))
        assertEquals("its library stays unless asked", 2, library.favorites().size)
        assertEquals(1, library.history().size)
        assertEquals(1, library.bookmarks().size)
    }

    @Test
    fun removingAShareCanForgetItsFavoritesHistoryAndBookmarks() {
        val library = fillLibrary()
        library.setRead(loose, true)
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        idle()
        removeShare(activity, forget = true)
        assertEquals(listOf("/books/attic"), library.favorites())
        assertEquals(0, library.history().size)
        assertEquals(0, library.bookmarks().size)
        assertTrue("read marks are not named, so they stay", library.isRead(loose.id))
    }

    @Test
    fun freeSpaceShowsAsKeptAndIsAskedAgainOnlyWhenOld() {
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        idle()
        openSheet(activity)
        val asked = fake.attempts
        val free = activity.getString(R.string.location_free, "3.0 GB", "8.0 GB")
        assertEquals(free, row("Share A").findViewById<TextView>(R.id.location_detail).text.toString())
        ShadowDialog.getLatestDialog().dismiss()
        idle()
        fake.offline = true
        openSheet(activity)
        assertEquals("shown as kept, without asking", free, row("Share A").findViewById<TextView>(R.id.location_detail).text.toString())
        assertEquals(asked, fake.attempts)
    }

    @Test
    fun theFormClearsAPasswordAndTurnsThumbnailsOff() {
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        idle()
        openSheet(activity)
        row("Share A").performLongClick()
        idle()
        val menu = ShadowDialog.getLatestDialog() as AlertDialog
        menu.listView.performItemClick(null, 0, 0)
        idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val clear = dialog.findViewById<View>(R.id.share_password_clear)!!
        assertEquals(View.VISIBLE, clear.visibility)
        clear.performClick()
        assertEquals(View.GONE, clear.visibility)
        assertEquals(
            activity.getString(R.string.share_password_cleared),
            dialog.findViewById<TextInputLayout>(R.id.share_password_layout)!!.helperText.toString()
        )
        dialog.findViewById<android.widget.CompoundButton>(R.id.share_thumbnails)!!.isChecked = false
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        val saved = ShareStore(context, box).all().single()
        assertEquals("", saved.password)
        assertFalse(saved.passwordLost)
        assertFalse(saved.thumbnails)
        assertFalse(Remotes.thumbnails("/net/s1/Loose.cbz"))
    }

    @Test
    fun theFormShowsTheMeasuredSpeedAndStoresAChoice() {
        var now = 0L
        ShareSpeeds.clock = { now }
        try {
            // Reads at 1.5 MB/s: Auto counts the share slow.
            repeat(SpeedMeter.MIN_SAMPLES) {
                val meter = ShareSpeeds.meter("s1")
                meter.bulkStarted()
                now += 1_000
                meter.bulkEnded(1_500_000)
            }
            val estimate = ShareSpeeds.estimate("s1")!!
            assertTrue(ShareSpeeds.slow("s1"))
            val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
            idle()
            openSheet(activity)
            row("Share A").performLongClick()
            idle()
            (ShadowDialog.getLatestDialog() as AlertDialog).listView.performItemClick(null, 0, 0)
            idle()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val measured = dialog.findViewById<TextView>(R.id.share_speed_measured)!!
            val shown = measured.text.toString()
            assertEquals(View.VISIBLE, measured.visibility)
            assertEquals(ShareDialog.measuredText(activity, estimate), shown)
            assertEquals(activity.getString(R.string.share_speed_measured_slow, activity.getString(R.string.share_speed_rate, "1.5 MB")), shown)
            assertEquals(R.id.share_speed_auto, dialog.findViewById<MaterialButtonToggleGroup>(R.id.share_speed)!!.checkedButtonId)
            val hint = dialog.findViewById<TextView>(R.id.share_speed_hint)!!
            assertEquals(activity.getString(R.string.share_speed_hint_auto), hint.text.toString())
            dialog.findViewById<MaterialButtonToggleGroup>(R.id.share_speed)!!.check(R.id.share_speed_slow)
            assertEquals(activity.getString(R.string.share_speed_hint_slow), hint.text.toString())
            dialog.findViewById<MaterialButtonToggleGroup>(R.id.share_speed)!!.check(R.id.share_speed_fast)
            assertEquals(activity.getString(R.string.share_speed_hint_fast), hint.text.toString())
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            idle()
            assertEquals(ShareSpeed.FAST, ShareStore(context, box).all().single().speed)
            assertFalse(ShareSpeeds.slow("s1"))
            assertEquals("what it measured stays", estimate, ShareSpeeds.estimate("s1"))
            assertEquals(activity.getString(R.string.share_speed_unmeasured), ShareDialog.measuredText(activity, null))
        } finally {
            ShareSpeeds.clock = { System.nanoTime() / 1_000_000 }
        }
    }

    @Test
    fun favoritesAndHistoryOnAShareShowWithoutTheNetwork() {
        val library = Library(context)
        library.toggleFavoriteFolder("/net/s1/Series")
        library.addHistory(com.infinity.comicreader.data.HistoryItem(loose.path, "Loose.cbz", 2, 10, 1000, loose.id))
        fake.offline = true
        Prefs(context).startupTab = Prefs.TAB_HISTORY
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        idle()
        assertEquals(listOf("Loose.cbz"), rows(activity.findViewById(R.id.recycler)))
        assertEquals(0, fake.stats + fake.lists)
        assertNotNull(activity)
    }

    @Test
    fun aLostPasswordIsAskedForWithoutReplacingAFormBeingFilled() {
        ShareStore(context, box).put(FakeRemoteFs.share().copy(password = "", passwordLost = true))
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        idle()
        openSheet(activity)
        row("Share A").performClick()
        idle()
        val asked = ShareDialog.find(activity)
        assertNotNull("asked for", asked)
        asked!!.dismiss()
        idle()

        ShareDialog.show(activity, null)
        idle()
        val typing = ShareDialog.find(activity)!!
        (typing.dialog as AlertDialog).findViewById<TextView>(R.id.share_name)!!.text = "Half typed"
        openSheet(activity)
        row("Share A").performClick()
        idle()
        assertTrue("the form being filled stays", typing === ShareDialog.find(activity))
        assertEquals("Half typed", (typing.dialog as AlertDialog).findViewById<TextView>(R.id.share_name)!!.text.toString())
    }

    /** The subtitle of the row titled [name]. */
    private fun subtitleOf(recycler: RecyclerView, name: String): String {
        rows(recycler)
        return (0 until recycler.childCount).map { recycler.getChildAt(it) }
            .first { (it.findViewById<View>(R.id.title) as? TitleView)?.text?.toString() == name }
            .findViewById<TextView>(R.id.subtitle).text.toString()
    }

    @Test
    fun aShareFolderShowsItsSubfoldersCountedThenAsKeptWhileUnreachable() {
        // Covers would list folders too.
        Prefs(context).thumbnails = false
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        idle()
        openSheet(activity)
        row("Share A").performClick()
        idle()
        val recycler = activity.findViewById<RecyclerView>(R.id.recycler)
        assertEquals(listOf("Loose.cbz", "Series"), rows(recycler).sorted())
        // Counted from a listing of its own, made after the folder shows; one is singular.
        assertEquals("1 item", subtitleOf(recycler, "Series"))
        val lists = fake.lists

        // Back on the device, then the share again: shown as kept, the unchanged subfolder not listed again.
        openSheet(activity)
        row("Device").performClick()
        idle()
        openSheet(activity)
        row("Share A").performClick()
        idle()
        assertEquals(listOf("Loose.cbz", "Series"), rows(recycler).sorted())
        assertEquals(lists + 1, fake.lists)

        // Unreachable: what was kept stays shown.
        fake.offline = true
        openSheet(activity)
        row("Device").performClick()
        idle()
        openSheet(activity)
        row("Share A").performClick()
        idle()
        assertEquals(listOf("Loose.cbz", "Series"), rows(recycler).sorted())
        assertEquals(activity.resources.getQuantityString(R.plurals.books_count, 1, 1), subtitleOf(recycler, "Series"))
        // Saying so, with Retry.
        val note = activity.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
        assertEquals(activity.getString(R.string.share_unreachable_named, "Share A"), note?.text?.toString())
        fake.offline = false
        File(tmp.root, "share/Added.cbz").writeBytes(ByteArray(10))
        activity.findViewById<View>(com.google.android.material.R.id.snackbar_action).performClick()
        idle()
        assertEquals(listOf("Added.cbz", "Loose.cbz", "Series"), rows(recycler).sorted())
    }
}
