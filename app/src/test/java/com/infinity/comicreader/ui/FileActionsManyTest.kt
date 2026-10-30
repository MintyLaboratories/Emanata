package com.infinity.comicreader.ui

import android.app.Application
import android.content.Context
import android.content.DialogInterface
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.files.FileClipboard
import com.infinity.comicreader.files.FileWork
import com.infinity.comicreader.files.FileWorkService
import com.infinity.comicreader.net.FakeRemoteFs
import com.infinity.comicreader.net.Remotes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.util.concurrent.CountDownLatch

/**
 * Several items at once: one question, one operation going on past an item that fails,
 * and a count of what was done; on the device and a share together.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileActionsManyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Application
    private lateinit var controller: ActivityController<AboutActivity>
    private lateinit var actions: FileActions
    private val finished = ArrayList<FileWork.Outcome>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("ui", 0).edit().clear().commit()
        FileWork.reset()
        controller = Robolectric.buildActivity(AboutActivity::class.java).create()
        actions = FileActions(controller.get(), Library(context), Prefs(context), { "Device" }, { finished += it }, {})
        controller.start().resume()
    }

    @After
    fun tearDown() {
        settle()
        controller.pause().stop().destroy()
        FileClipboard.clear()
        Remotes.serve(emptyList()) { throw AssertionError("no shares") }
    }

    /** A share served from a folder, as /net/s1. */
    private fun share(): Pair<FakeRemoteFs, File> {
        val dir = tmp.newFolder("share")
        val fake = FakeRemoteFs(dir)
        Remotes.serve(listOf(FakeRemoteFs.share())) { fake }
        return fake to dir
    }

    private fun waitFor(what: () -> Boolean) {
        repeat(500) {
            shadowOf(Looper.getMainLooper()).idle()
            if (what()) return
            Thread.sleep(10)
        }
        throw AssertionError("never came")
    }

    private fun item(file: File) = FileClipboard.Item(file.absolutePath, file.isDirectory)

    /** Confirms the latest question with [button]. */
    private fun answer(button: Int) {
        shadowOf(Looper.getMainLooper()).idle()
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(button).performClick()
    }

    private fun deleted(done: Int, count: Int) = context.resources.getQuantityString(R.plurals.deleted_part, count, done, count)

    private fun settle() {
        repeat(300) {
            shadowOf(Looper.getMainLooper()).idle()
            if (FileWork.operations.value.isEmpty()) return
            Thread.sleep(10)
        }
        throw AssertionError("still running: ${FileWork.operations.value}")
    }

    private fun AlertDialog.text(id: Int): String = findViewById<android.widget.TextView>(id)!!.text.toString()

    @Test
    fun severalAreDeletedAfterOneQuestion() {
        val files = listOf("a.cbz", "b.cbz").map { File(tmp.root, it).apply { writeBytes(ByteArray(10)) } }
        val folder = tmp.newFolder("Shelf").also { File(it, "c.cbz").writeBytes(ByteArray(5)) }
        val items = files.map { FileClipboard.Item(it.absolutePath, false) } + FileClipboard.Item(folder.absolutePath, true)
        var confirmed = 0
        actions.delete(items) { confirmed++ }
        shadowOf(Looper.getMainLooper()).idle()
        val ask = ShadowDialog.getLatestDialog() as AlertDialog
        val res = context.resources
        assertEquals(res.getQuantityString(R.plurals.delete_items_title, 3, 3), ask.text(androidx.appcompat.R.id.alertTitle))
        assertEquals(res.getQuantityString(R.plurals.delete_items_folders_msg, 3, 3, "Device"), ask.text(android.R.id.message))
        assertEquals(0, confirmed)
        ask.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        settle()
        assertEquals(1, confirmed)
        assertTrue(files.none { it.exists() })
        assertFalse(folder.exists())
        assertEquals(res.getQuantityString(R.plurals.deleted_format, 3, 3), ShadowToast.getTextOfLatestToast())
        val done = finished.single() as FileWork.Outcome.Done
        assertEquals(3, done.count)
        assertEquals(items.map { it.path }, done.gone)
    }

    @Test
    fun aFolderSelectedWithWhatIsInItIsDeletedOnce() {
        val folder = tmp.newFolder("Shelf")
        val inner = File(folder, "a.cbz").apply { writeBytes(ByteArray(10)) }
        actions.delete(listOf(FileClipboard.Item(inner.absolutePath, false), FileClipboard.Item(folder.absolutePath, true)))
        shadowOf(Looper.getMainLooper()).idle()
        val ask = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals(context.getString(R.string.delete_item_title, "Shelf"), ask.text(androidx.appcompat.R.id.alertTitle))
        ask.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        settle()
        assertFalse(folder.exists())
        val done = finished.single() as FileWork.Outcome.Done
        assertEquals(1, done.count)
        assertEquals(listOf(folder.absolutePath), done.gone)
    }

    @Test
    fun aFolderPickedUpWithWhatIsInItGoesAlone() {
        val folder = tmp.newFolder("Shelf")
        val inner = File(folder, "a.cbz").apply { writeBytes(ByteArray(10)) }
        actions.pickUp(listOf(FileClipboard.Item(inner.absolutePath, false), FileClipboard.Item(folder.absolutePath, true)), move = true)
        assertEquals(listOf(folder.absolutePath), FileClipboard.clips.value.single().items.map { it.path })
        FileClipboard.clear()
    }

    @Test
    fun oneFailingItemLeavesTheOthersDoneAndCounted() {
        val (fake, dir) = share()
        File(dir, "s.cbz").writeBytes(ByteArray(10))
        fake.readOnly = true
        val a = File(tmp.root, "a.cbz").apply { writeBytes(ByteArray(10)) }
        val b = File(tmp.root, "b.cbz").apply { writeBytes(ByteArray(10)) }
        actions.delete(listOf(item(a), FileClipboard.Item("/net/s1/s.cbz", false), item(b)))
        answer(DialogInterface.BUTTON_POSITIVE)
        settle()
        assertFalse(a.exists())
        assertFalse(b.exists())
        assertTrue(File(dir, "s.cbz").exists())
        assertEquals(FileWork.Outcome.Failed((finished.single() as FileWork.Outcome.Failed).error, 2, 3, listOf(a.absolutePath, b.absolutePath)), finished.single())
        assertEquals(
            context.getString(R.string.file_failed_part, deleted(2, 3), context.getString(R.string.file_denied)),
            ShadowToast.getTextOfLatestToast()
        )
    }

    @Test
    fun anItemAlreadyGoneCountsAsDeleted() {
        share()
        val a = File(tmp.root, "a.cbz").apply { writeBytes(ByteArray(10)) }
        actions.delete(listOf(FileClipboard.Item("/net/s1/gone.cbz", false), item(a)))
        answer(DialogInterface.BUTTON_POSITIVE)
        settle()
        assertFalse(a.exists())
        assertEquals(FileWork.Outcome.Done(null, gone = listOf("/net/s1/gone.cbz", a.absolutePath), count = 2), finished.single())
        assertEquals(context.resources.getQuantityString(R.plurals.deleted_format, 2, 2), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun cancelBetweenDeletesSaysHowManyWereDone() {
        val (fake, dir) = share()
        File(dir, "s.cbz").writeBytes(ByteArray(10))
        val a = File(tmp.root, "a.cbz").apply { writeBytes(ByteArray(10)) }
        val hold = CountDownLatch(1)
        fake.slow = hold
        actions.delete(listOf(FileClipboard.Item("/net/s1/s.cbz", false), item(a)))
        answer(DialogInterface.BUTTON_POSITIVE)
        waitFor { fake.held.get() > 0 }
        FileWork.cancel()
        hold.countDown()
        settle()
        assertTrue(a.exists())
        assertEquals(FileWork.Outcome.Canceled(1, 2, listOf("/net/s1/s.cbz")), finished.single())
        assertEquals(context.getString(R.string.file_canceled_part, deleted(1, 2)), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun itemsFromTheDeviceAndAShareMoveTogether() {
        val (_, dir) = share()
        File(dir, "s.cbz").writeBytes(ByteArray(20) { 2 })
        val a = File(tmp.root, "a.cbz").apply { writeBytes(ByteArray(10) { 1 }) }
        val dest = tmp.newFolder("Dest")
        actions.pickUp(listOf(item(a), FileClipboard.Item("/net/s1/s.cbz", false)), move = true)
        actions.paste(dest)
        waitFor { finished.isNotEmpty() }
        settle()
        assertEquals(listOf("a.cbz", "s.cbz"), dest.list()!!.sorted())
        assertEquals(20L, File(dest, "s.cbz").length())
        assertFalse(a.exists())
        assertFalse(File(dir, "s.cbz").exists())
        assertEquals(2, (finished.single() as FileWork.Outcome.Done).count)
    }

    @Test
    fun aFailingPasteGoesOnWithTheRest() {
        val (fake, dir) = share()
        File(dir, "s.cbz").writeBytes(ByteArray(20))
        val a = File(tmp.root, "a.cbz").apply { writeBytes(ByteArray(10)) }
        val b = File(tmp.root, "b.cbz").apply { writeBytes(ByteArray(10)) }
        val dest = tmp.newFolder("Dest")
        File(dest, "a.cbz").writeBytes(ByteArray(3))
        actions.pickUp(listOf(item(a), FileClipboard.Item("/net/s1/s.cbz", false), item(b)), move = false)
        actions.paste(dest)
        // Asked about the name taken; the share goes away meanwhile.
        waitFor { ShadowDialog.getLatestDialog()?.isShowing == true }
        fake.offline = true
        answer(DialogInterface.BUTTON_NEUTRAL)
        waitFor { finished.isNotEmpty() }
        settle()
        assertEquals(listOf("a (2).cbz", "a.cbz", "b.cbz"), dest.list()!!.sorted())
        val failed = finished.single() as FileWork.Outcome.Failed
        assertEquals(2, failed.done)
        assertEquals(3, failed.count)
        assertEquals(
            context.getString(R.string.file_failed_part, context.resources.getQuantityString(R.plurals.copied_part, 3, 2, 3), context.getString(R.string.share_unreachable)),
            ShadowToast.getTextOfLatestToast()
        )
    }

    @Test
    fun replaceTakesEveryNameTaken() {
        val a = File(tmp.root, "a.cbz").apply { writeBytes(ByteArray(10) { 1 }) }
        val b = File(tmp.root, "b.cbz").apply { writeBytes(ByteArray(20) { 2 }) }
        val dest = tmp.newFolder("Dest")
        File(dest, "a.cbz").writeBytes(ByteArray(3))
        File(dest, "b.cbz").writeBytes(ByteArray(3))
        actions.pickUp(listOf(item(a), item(b)), move = false)
        actions.paste(dest)
        waitFor { ShadowDialog.getLatestDialog()?.isShowing == true }
        answer(DialogInterface.BUTTON_POSITIVE)
        waitFor { finished.isNotEmpty() }
        settle()
        assertEquals(listOf("a.cbz", "b.cbz"), dest.list()!!.sorted())
        assertEquals(10L, File(dest, "a.cbz").length())
        assertEquals(20L, File(dest, "b.cbz").length())
        assertEquals(context.resources.getQuantityString(R.plurals.copied_count, 2, 2), ShadowToast.getTextOfLatestToast())
    }

    /** Pastes into [dest], answering [button] when asked about names taken (after [meanwhile]), and waits for the outcome. */
    private fun pasteInto(dest: File, button: Int? = null, meanwhile: () -> Unit = {}) {
        actions.paste(dest)
        if (button != null) {
            waitFor { ShadowDialog.getLatestDialog()?.isShowing == true }
            meanwhile()
            answer(button)
        }
        waitFor { finished.isNotEmpty() }
        settle()
    }

    @Test
    fun aMovePartlyHereLeavesNothingPickedUp() {
        val x = tmp.newFolder("X")
        val y = tmp.newFolder("Y")
        val a = File(x, "a.cbz").apply { writeBytes(ByteArray(10)) }
        val b = File(y, "b.cbz").apply { writeBytes(ByteArray(10)) }
        actions.pickUp(listOf(item(a), item(b)), move = true)
        pasteInto(x)
        assertEquals(listOf("a.cbz", "b.cbz"), x.list()!!.sorted())
        assertTrue(FileClipboard.clips.value.isEmpty())
    }

    @Test
    fun skippingInAMoveLeavesNothingPickedUp() {
        val a = File(tmp.root, "a.cbz").apply { writeBytes(ByteArray(10)) }
        val b = File(tmp.root, "b.cbz").apply { writeBytes(ByteArray(10)) }
        val dest = tmp.newFolder("Dest")
        File(dest, "b.cbz").writeBytes(ByteArray(3))
        actions.pickUp(listOf(item(a), item(b)), move = true)
        pasteInto(dest, DialogInterface.BUTTON_NEGATIVE)
        assertTrue(File(dest, "a.cbz").exists())
        assertTrue(b.exists())
        assertTrue(FileClipboard.clips.value.isEmpty())

        // Skipping the only one: nothing moves, and the pick is done with.
        actions.pickUp(listOf(item(b)), move = true)
        actions.paste(dest)
        waitFor { ShadowDialog.getLatestDialog()?.isShowing == true }
        answer(DialogInterface.BUTTON_NEGATIVE)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(b.exists())
        assertTrue(FileClipboard.clips.value.isEmpty())
    }

    @Test
    fun aCanceledMoveKeepsPickedUpWhatItNeverReached() {
        val (fake, dir) = share()
        File(dir, "a.cbz").writeBytes(ByteArray(10))
        File(dir, "b.cbz").writeBytes(ByteArray(10))
        val dest = File(dir, "Dest").apply { mkdirs() }
        File(dest, "b.cbz").writeBytes(ByteArray(3))
        actions.pickUp(listOf(FileClipboard.Item("/net/s1/a.cbz", false), FileClipboard.Item("/net/s1/b.cbz", false)), move = true)
        val hold = CountDownLatch(1)
        actions.paste(Remotes.file("/net/s1/Dest"))
        waitFor { ShadowDialog.getLatestDialog()?.isShowing == true }
        fake.slow = hold
        answer(DialogInterface.BUTTON_NEUTRAL)
        waitFor { fake.held.get() > 0 }
        FileWork.cancel()
        hold.countDown()
        waitFor { finished.isNotEmpty() }
        settle()
        assertEquals(FileWork.Outcome.Canceled(1, 2, listOf("/net/s1/a.cbz")), finished.single())
        assertTrue(File(dest, "a.cbz").exists())
        assertEquals(listOf("/net/s1/b.cbz"), FileClipboard.clips.value.single().items.map { it.path })
    }

    @Test
    fun aMoveCanceledWhileWaitingIsPickedUpAgain() {
        val go = java.util.concurrent.Semaphore(0)
        val started = java.util.concurrent.Semaphore(0)
        FileWork.start(FileWork.Kind.COPY, "held.cbz") {
            started.release()
            go.acquire()
            FileWork.Outcome.Done(null)
        }
        started.acquire()
        val a = File(tmp.root, "a.cbz").apply { writeBytes(ByteArray(10)) }
        val dest = tmp.newFolder("Dest")
        val clip = actions.pickUp(listOf(item(a)), move = true)!!
        actions.paste(dest)
        waitFor { FileWork.operations.value.size == 2 }
        assertTrue(FileClipboard.clips.value.isEmpty())
        FileWork.cancel(FileWork.operations.value.last().id)
        waitFor { finished.isNotEmpty() }
        val back = FileClipboard.clips.value.single()
        assertEquals(listOf(a.absolutePath), back.items.map { it.path })
        assertTrue(back.move)
        assertTrue(back.id > clip.id)
        assertTrue(a.exists())
        go.release()
        settle()
    }

    @Test
    fun aFailedMoveKeepsPickedUpWhatFailed() {
        val (fake, dir) = share()
        File(dir, "s.cbz").writeBytes(ByteArray(20))
        val a = File(tmp.root, "a.cbz").apply { writeBytes(ByteArray(10)) }
        val b = File(tmp.root, "b.cbz").apply { writeBytes(ByteArray(10)) }
        val dest = tmp.newFolder("Dest")
        File(dest, "a.cbz").writeBytes(ByteArray(3))
        actions.pickUp(listOf(item(a), FileClipboard.Item("/net/s1/s.cbz", false), item(b)), move = true)
        pasteInto(dest, DialogInterface.BUTTON_NEUTRAL) { fake.offline = true }
        assertEquals(listOf("a (2).cbz", "a.cbz", "b.cbz"), dest.list()!!.sorted())
        assertEquals(listOf("/net/s1/s.cbz"), FileClipboard.clips.value.single().items.map { it.path })
    }

    /** True when [root] holds an [android.app.Activity] within [depth] fields, outside the JDK and the platform. */
    private fun holdsActivity(root: Any?, depth: Int, seen: MutableSet<Any> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())): Boolean {
        if (root == null || !seen.add(root)) return false
        if (root is android.app.Activity) return true
        if (depth == 0) return false
        if (root is Iterable<*>) return root.any { holdsActivity(it, depth - 1, seen) }
        val name = root.javaClass.name
        if (name.startsWith("java.") || name.startsWith("android.") || name.startsWith("kotlin.")) return false
        var type: Class<*>? = root.javaClass
        while (type != null && type != Any::class.java) {
            for (field in type.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(field.modifiers) || field.type.isPrimitive) continue
                field.isAccessible = true
                if (holdsActivity(field.get(root), depth - 1, seen)) return true
            }
            type = type.superclass
        }
        return false
    }

    @Test
    fun queuedWorkHoldsNoScreen() {
        val go = java.util.concurrent.Semaphore(0)
        val started = java.util.concurrent.Semaphore(0)
        FileWork.start(FileWork.Kind.COPY, "held.cbz") {
            started.release()
            go.acquire()
            FileWork.Outcome.Done(null)
        }
        started.acquire()
        val a = File(tmp.root, "a.cbz").apply { writeBytes(ByteArray(10)) }
        val b = File(tmp.root, "b.cbz").apply { writeBytes(ByteArray(10)) }
        actions.delete(listOf(item(a)))
        answer(DialogInterface.BUTTON_POSITIVE)
        actions.pickUp(listOf(item(b)), move = true)
        actions.paste(tmp.newFolder("Dest"))
        waitFor { FileWork.operations.value.size == 3 }
        val waiting = FileWork::class.java.getDeclaredField("waiting").apply { isAccessible = true }.get(FileWork) as Iterable<*>
        assertEquals(2, waiting.count())
        for (op in waiting) assertFalse(holdsActivity(op, 6))
        go.release()
        settle()
    }

    @Test
    fun progressNamesTheItemAndWhichOfHowMany() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val status = FileWork.Status(FileWork.Kind.DELETE, "b.cbz", 0, index = 1, count = 3)
        assertEquals("b.cbz (2 of 3)", FileWorkService.nameOf(context, status))
        assertEquals("b.cbz", FileWorkService.nameOf(context, status.copy(index = 0, count = 1)))
    }
}
