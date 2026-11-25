package minty.emanata.ui

import android.Manifest
import android.app.Application
import android.content.DialogInterface
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import minty.emanata.data.Library
import minty.emanata.data.Prefs
import minty.emanata.files.FileClipboard
import minty.emanata.files.FileWork
import minty.emanata.testing.idleUntil
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
import java.io.File

/** File operations ask once, as the first starts, to show notifications; they run whatever the answer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileActionsNotificationsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Application
    private lateinit var controller: ActivityController<AboutActivity>
    private lateinit var actions: FileActions

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("ui", 0).edit().clear().commit()
        FileWork.reset()
        controller = Robolectric.buildActivity(AboutActivity::class.java).create()
        actions = FileActions(controller.get(), Library(context), Prefs(context), { "Device" }, {}, {})
        controller.start().resume()
    }

    @After
    fun tearDown() {
        settle()
        controller.pause().stop().destroy()
    }

    private fun settle() = idleUntil("every operation done") { FileWork.operations.value.isEmpty() }

    /** Deletes a new file through the confirmation, and waits for it to go. */
    private fun deleteOne(name: String) {
        val file = File(tmp.root, name).apply { writeBytes(ByteArray(10)) }
        actions.delete(listOf(FileClipboard.Item(file.absolutePath, isDir = false)))
        shadowOf(Looper.getMainLooper()).idle()
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        settle()
        assertFalse(file.exists())
    }

    private fun asked() = shadowOf(controller.get()).lastRequestedPermission

    @Test
    fun asksOnceAsTheFirstOperationStarts() {
        assertNull(asked())
        deleteOne("a.cbz")
        val first = asked()
        assertNotNull(first)
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in first!!.requestedPermissions)
        assertTrue(Prefs(context).askedNotifications)

        // Refused or not, never again; operations still run.
        deleteOne("b.cbz")
        assertSame(first, asked())
    }

    @Test
    fun asksForAnOperationStartedElsewhere() {
        // As the reader queues Repack and leaves for the library.
        val gate = java.util.concurrent.CountDownLatch(1)
        FileWork.start(FileWork.Kind.REPACK, "a.cb7") { gate.await(); FileWork.Outcome.Done(null) }
        shadowOf(Looper.getMainLooper()).idle()
        val first = asked()
        gate.countDown()
        assertNotNull(first)
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in first!!.requestedPermissions)
        assertTrue(Prefs(context).askedNotifications)
    }

    @Test
    fun grantedAsksNothing() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        deleteOne("a.cbz")
        assertNull(asked())
    }

    @Test
    @Config(sdk = [32])
    fun beforeAndroid13AsksNothing() {
        deleteOne("a.cbz")
        assertNull(asked())
    }
}
