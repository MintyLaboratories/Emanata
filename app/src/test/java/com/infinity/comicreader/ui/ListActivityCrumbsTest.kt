package com.infinity.comicreader.ui

import android.Manifest
import android.app.Application
import android.os.Environment
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.infinity.comicreader.testing.idleUntil
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.StorageRoot
import com.infinity.comicreader.data.StorageRoots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ListActivityCrumbsTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("ui", 0).edit().clear().commit()
        root = Environment.getExternalStorageDirectory()
        File(root, "attic/Comics").mkdirs()
        Prefs(context).lastPath = File(root, "attic/Comics").absolutePath
    }

    /** True once the screen waits on nothing but the test: listed, favorites classified, facts read, presence asked, its menu looked into. */
    private fun ListActivity.quiet() =
        listJob?.isCompleted == true && listOf(classifyJob, factsJob, presenceJob, moreLook).none { it?.isCompleted == false }

    /** Runs the main looper until the screen is quiet: the startup location is worked out, and folders listed, on the IO pool. */
    private fun ListActivity.settle() = idleUntil("the library screen quiet") { quiet() }

    private fun CrumbBar.row(): LinearLayout = (getChildAt(1) as HorizontalScrollView).getChildAt(0) as LinearLayout

    /** Taps the app icon crumb, then the location named [name] in the picker it opens. */
    private fun pickLocation(activity: ListActivity, name: String) {
        val bar = activity.findViewById<CrumbBar>(R.id.pathbar)
        bar.locationsButton.performClick()
        activity.settle()
        assertTrue(activity.locations?.isShowing == true)
        val list = ShadowDialog.getLatestDialog().findViewById<LinearLayout>(R.id.locations_list)
        val row = (0 until list.childCount).map { list.getChildAt(it) }
            .first { it.findViewById<TextView>(R.id.location_name).text == name }
        row.performClick()
        activity.settle()
    }

    /** No work of this test's screens runs on into the next. */
    @After
    fun awaitLeftovers() = awaitLeftoverWork()

    @Test
    fun crumbsNameTheRootAndNavigate() {
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        activity.settle()
        val bar = activity.findViewById<CrumbBar>(R.id.pathbar)
        assertEquals(listOf("Device", "attic", "Comics"), bar.crumbs.map { it.label })

        // Separator, "Device", separator, "attic", separator, "Comics".
        val parent = bar.row().getChildAt(3) as TextView
        assertEquals("attic", parent.text)
        parent.performClick()
        activity.settle()
        // The folder left stays as a forward crumb.
        assertEquals(listOf("Device", "attic", "Comics"), bar.crumbs.map { it.label })
        assertEquals(1, bar.currentIndex)

        pickLocation(activity, "Device")
        assertEquals(root.absolutePath, bar.crumbs[bar.currentIndex].path)
        assertEquals(0, bar.currentIndex)
    }

    @Test
    fun rootCrumbNavigatesToTheRoot() {
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        activity.settle()
        val bar = activity.findViewById<CrumbBar>(R.id.pathbar)
        val rootCrumb = bar.row().getChildAt(1) as TextView
        assertEquals("Device", rootCrumb.text.toString())
        rootCrumb.performClick()
        activity.settle()
        assertEquals(0, bar.currentIndex)
        assertEquals(root.absolutePath, bar.crumbs[0].path)
        assertTrue(activity.locations?.isShowing != true)
    }

    @Test
    fun appIconCrumbOpensTheLocationPicker() {
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        activity.settle()
        val icon = activity.findViewById<CrumbBar>(R.id.pathbar).locationsButton
        assertNotNull(icon.drawable)
        assertEquals(activity.getString(R.string.choose_root), icon.contentDescription)
        icon.performClick()
        activity.settle()
        assertEquals(listOf("Device"), activity.locations?.shownRoots?.map { it.name })
        val list = ShadowDialog.getLatestDialog().findViewById<LinearLayout>(R.id.locations_list)
        // The current location is highlighted.
        assertTrue(list.getChildAt(0).isActivated)
    }

    @Test
    fun locationPickerSurvivesRotation() {
        val controller = Robolectric.buildActivity(ListActivity::class.java).setup()
        controller.get().settle()
        controller.get().findViewById<CrumbBar>(R.id.pathbar).locationsButton.performClick()
        controller.get().settle()
        controller.recreate()
        controller.get().settle()
        val sheet = controller.get().locations
        assertTrue(sheet?.isShowing == true)
        assertEquals(listOf("Device"), sheet?.shownRoots?.map { it.name })
        val list = ShadowDialog.getLatestDialog().findViewById<LinearLayout>(R.id.locations_list)
        // Still highlighted, and picking from the rebuilt sheet reaches the new activity.
        assertTrue(list.getChildAt(0).isActivated)
        list.getChildAt(0).performClick()
        controller.get().settle()
        assertEquals(root.absolutePath, controller.get().findViewById<CrumbBar>(R.id.pathbar).let { it.crumbs[it.currentIndex].path })
    }

    @Test
    fun forwardCrumbsReopenAndDropOnAnotherFolder() {
        File(root, "attic/Other").mkdirs()
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        activity.settle()
        val bar = activity.findViewById<CrumbBar>(R.id.pathbar)
        pickLocation(activity, "Device")
        assertEquals(listOf("Device", "attic", "Comics"), bar.crumbs.map { it.label })

        // Separator, "Device", separator, "attic", separator, gray "Comics".
        (bar.row().getChildAt(5) as TextView).performClick()
        activity.settle()
        assertEquals(2, bar.currentIndex)

        (bar.row().getChildAt(3) as TextView).performClick()
        activity.settle()
        assertEquals(1, bar.currentIndex)
        // Opening a folder off the trail drops it.
        val crumbClick = bar.onCrumbClick!!
        crumbClick(com.infinity.comicreader.data.Crumb("Other", File(root, "attic/Other").absolutePath, false))
        activity.settle()
        assertEquals(listOf("Device", "attic", "Other"), bar.crumbs.map { it.label })
        assertEquals(2, bar.currentIndex)
    }

    @Test
    fun aForwardCrumbToAFolderNowGoneOpensItsNearestParentAndDropsThePastCrumbs() {
        File(root, "attic/Comics/Series/Volume").mkdirs()
        Prefs(ApplicationProvider.getApplicationContext()).lastPath = File(root, "attic/Comics/Series/Volume").absolutePath
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        activity.settle()
        val bar = activity.findViewById<CrumbBar>(R.id.pathbar)
        pickLocation(activity, "Device")
        assertEquals(listOf("Device", "attic", "Comics", "Series", "Volume"), bar.crumbs.map { it.label })

        File(root, "attic/Comics/Series").deleteRecursively()
        // Separator, "Device", separator, "attic", separator, "Comics", separator, gray "Series".
        (bar.row().getChildAt(7) as TextView).performClick()
        activity.settle()
        assertEquals(listOf("Device", "attic", "Comics"), bar.crumbs.map { it.label })
        assertEquals(2, bar.currentIndex)
        assertEquals(activity.getString(R.string.folder_gone), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun aStartupFolderNowGoneOpensItsNearestParent() {
        Prefs(ApplicationProvider.getApplicationContext()).lastPath = File(root, "attic/Comics/Gone/Deeper").absolutePath
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        activity.settle()
        val bar = activity.findViewById<CrumbBar>(R.id.pathbar)
        assertEquals(listOf("Device", "attic", "Comics"), bar.crumbs.map { it.label })
        assertEquals(2, bar.currentIndex)
        assertEquals(activity.getString(R.string.folder_gone), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun aDrivePluggedInOpensTheLocationPickerWithItHighlighted() {
        val controller = Robolectric.buildActivity(ListActivity::class.java).setup()
        controller.get().settle()
        val activity = controller.get()
        val roots = StorageRoots.list(activity)
        val usb = StorageRoot(File(root.parentFile, "1A2B-3C4D").absolutePath, "Drive A", StorageRoot.Kind.USB)
        activity.refreshRoots(roots + usb)
        activity.settle()
        assertEquals(listOf("Device", "Drive A"), activity.locations?.shownRoots?.map { it.name })
        val list = ShadowDialog.getLatestDialog().findViewById<LinearLayout>(R.id.locations_list)
        assertFalse(list.getChildAt(0).isActivated)
        assertTrue(list.getChildAt(1).isActivated)
    }

    @Test
    fun aDrivePluggedInWhileAwayOpensTheLocationPickerOnReturn() {
        val controller = Robolectric.buildActivity(ListActivity::class.java).setup()
        controller.get().settle()
        val activity = controller.get()
        val usb = StorageRoot(File(root.parentFile, "1A2B-3C4D").absolutePath, "Drive A", StorageRoot.Kind.USB)
        val mounted = StorageRoots.list(activity) + usb
        activity.listRoots = { mounted }
        controller.pause().stop()
        activity.refreshRoots()
        activity.settle()
        assertTrue(activity.locations?.isShowing != true)
        controller.start().resume()
        activity.settle()
        assertTrue(activity.locations?.isShowing == true)
        val list = ShadowDialog.getLatestDialog().findViewById<LinearLayout>(R.id.locations_list)
        assertTrue(list.getChildAt(1).isActivated)
    }

    @Test
    fun aDrivePulledOutBeforeTheReturnOpensNothing() {
        val controller = Robolectric.buildActivity(ListActivity::class.java).setup()
        controller.get().settle()
        val activity = controller.get()
        val usb = StorageRoot(File(root.parentFile, "1A2B-3C4D").absolutePath, "Drive A", StorageRoot.Kind.USB)
        controller.pause().stop()
        activity.refreshRoots(StorageRoots.list(activity) + usb)
        activity.refreshRoots(StorageRoots.list(activity))
        controller.start().resume()
        activity.settle()
        assertTrue(activity.locations?.isShowing != true)
    }

    @Test
    fun backStopsAtTheRoot() {
        Prefs(ApplicationProvider.getApplicationContext()).lastPath = root.absolutePath
        val controller = Robolectric.buildActivity(ListActivity::class.java).setup()
        controller.get().settle()
        @Suppress("DEPRECATION")
        controller.get().onBackPressed()
        controller.get().settle()
        assertTrue(controller.get().isFinishing)
        assertEquals(listOf("Device"), controller.get().findViewById<CrumbBar>(R.id.pathbar).crumbs.map { it.label })
    }
}
