package minty.emanata.ui

import minty.emanata.testing.idleUntil
import minty.emanata.testing.toggleFavorite
import android.Manifest
import android.app.Application
import android.app.Dialog
import android.content.DialogInterface
import android.os.Environment
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import minty.emanata.data.Library
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class FolderPickerTest {

    private lateinit var context: Application
    private lateinit var root: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        for (name in listOf("ui", "library", "favorite_kinds")) context.getSharedPreferences(name, 0).edit().clear().commit()
        root = Environment.getExternalStorageDirectory()
        root.mkdirs()
    }

    /** Waits until a dialog other than [shown] opens: each list of folders does once read on the IO pool. Returns it. */
    private fun dialogAfter(what: String, shown: Dialog?): AlertDialog {
        idleUntil(what) { ShadowDialog.getLatestDialog()?.let { it !== shown && it.isShowing } == true }
        return ShadowDialog.getLatestDialog() as AlertDialog
    }

    private fun AlertDialog.items(): List<String> =
        (0 until listView.adapter.count).map { listView.adapter.getItem(it).toString() }

    @Test
    fun listsFavoriteFoldersFirstThenRootsThenBrowsing() {
        val library = Library(context)
        val alpha = File(root, "Alpha").apply { mkdirs() }
        val beta = File(root, "Beta").apply { mkdirs() }
        val book = File(root, "a.cbz").apply { createNewFile() }
        listOf(beta, book, alpha).forEach { library.toggleFavorite(it) }
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        var picked: String? = null
        FolderPicker(activity, activity.lifecycleScope) { picked = it }.show()
        val first = dialogAfter("the picker", null)
        assertEquals(listOf("Alpha", "Beta", "Device", "Other folder…"), first.items())

        first.listView.performItemClick(null, 3, 3)
        val top = dialogAfter("the device's folders", first)
        assertEquals(listOf("Alpha", "Beta"), top.items().filter { it == "Alpha" || it == "Beta" })
        val alphaAt = top.items().indexOf("Alpha")
        top.listView.performItemClick(null, alphaAt, alphaAt.toLong())
        dialogAfter("the folders in Alpha", top).getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(alpha.absolutePath, picked)
    }

    @Test
    fun describeUsesTheFavoriteLabelElseTheCrumbs() {
        val library = Library(context)
        val stacks = File(root, "stacks/Comics").apply { mkdirs() }
        File(root, "books/Comics").apply { mkdirs() }.let { library.toggleFavorite(it) }
        library.toggleFavorite(stacks)
        assertEquals("stacks/Comics", FolderPicker.describe(context, stacks.absolutePath).toString())
        assertEquals("Device / Other", FolderPicker.describe(context, File(root, "Other").absolutePath).toString())
    }
}
