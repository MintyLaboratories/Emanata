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
import com.infinity.comicreader.testing.idleUntil
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
import java.util.concurrent.Executor

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

    /** The share of [share], if any. */
    private var shared: FakeRemoteFs? = null

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
        shared?.slow?.countDown()
        settle()
        controller.pause().stop().destroy()
        FileClipboard.clear()
        Remotes.serve(emptyList()) { throw AssertionError("no shares") }
    }

    /** A share served from a folder, as /net/s1, whose held calls ([FakeRemoteFs.slow]) wait until the test lets them go. */
    private fun share(): Pair<FakeRemoteFs, File> {
        val dir = tmp.newFolder("share")
        val fake = FakeRemoteFs(dir).apply { slowCapMs = Long.MAX_VALUE }
        shared = fake
        Remotes.serve(listOf(FakeRemoteFs.share())) { fake }
        return fake to dir
    }

    /** Runs [block] with what is chosen looked up at once, on the main thread, as it is chosen. */
    private fun lookingUpAtOnce(block: () -> Unit) {
        val io = FileJobs.Looks.executor
        FileJobs.Looks.executor = Executor(Runnable::run)
        try {
            block()
        } finally {
            FileJobs.Looks.executor = io
        }
    }

    private fun item(file: File) = FileClipboard.Item(file.absolutePath, file.isDirectory)

    /** Confirms the latest question with [button]. */
    private fun answer(button: Int) {
        shadowOf(Looper.getMainLooper()).idle()
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(button).performClick()
    }

    private fun deleted(done: Int, count: Int) = context.resources.getQuantityString(R.plurals.deleted_part, count, done, count)

    private fun settle() = idleUntil("every operation done") { FileWork.operations.value.isEmpty() }

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
    fun anItemAlreadyGoneIsNotCountedAndSaid() {
        share()
        val a = File(tmp.root, "a.cbz").apply { writeBytes(ByteArray(10)) }
        actions.delete(listOf(FileClipboard.Item("/net/s1/gone.cbz", false), item(a)))
        answer(DialogInterface.BUTTON_POSITIVE)
        settle()
        assertFalse(a.exists())
        val failed = finished.single() as FileWork.Outcome.Failed
        assertEquals(FileWork.Outcome.Failed(failed.error, 1, 2, listOf(a.absolutePath)), failed)
        assertEquals(
            context.getString(R.string.file_failed_part, deleted(1, 2), context.getString(R.string.file_gone, "gone.cbz")),
            ShadowToast.getTextOfLatestToast()
        )
    }

    /** Holds the queue with an operation until the returned semaphore is released. */
    private fun holdQueue(): java.util.concurrent.Semaphore {
        val go = java.util.concurrent.Semaphore(0)
        val started = java.util.concurrent.Semaphore(0)
        FileWork.start(FileWork.Kind.COPY, "held.cbz") {
            started.release()
            go.acquire()
            FileWork.Outcome.Done(null)
        }
        started.acquire()
        return go
    }

    @Test
    fun aQueuedDeleteLeavesAloneWhatChangedOrCameInItsPlace() = lookingUpAtOnce {
        val go = holdQueue()
        val a = File(tmp.root, "a.cbz").apply { writeBytes(ByteArray(10)) }
        val b = File(tmp.root, "b.cbz").apply { writeBytes(ByteArray(10)) }
        val c = File(tmp.root, "c.cbz").apply { writeBytes(ByteArray(10)) }
        actions.delete(listOf(item(a), item(b), item(c)))
        answer(DialogInterface.BUTTON_POSITIVE)
        idleUntil("the delete queued") { FileWork.operations.value.size == 2 }
        // Once looked up as chosen, before its turn: another file takes a's name, and b is moved away.
        a.delete()
        a.writeBytes(ByteArray(30))
        assertTrue(b.renameTo(File(tmp.root, "elsewhere.cbz")))
        go.release()
        settle()
        assertTrue(a.exists())
        assertEquals(30L, a.length())
        assertTrue(File(tmp.root, "elsewhere.cbz").exists())
        assertFalse(c.exists())
        val failed = finished.last() as FileWork.Outcome.Failed
        assertEquals(FileWork.Outcome.Failed(failed.error, 1, 3, listOf(c.absolutePath)), failed)
        assertEquals(context.getString(R.string.file_altered, "a.cbz"), ShadowToast.getTextOfLatestToast().substringAfter("failed: "))
    }

    @Test
    fun twoQueuedPastesOfOneNameKeepBoth() {
        val go = holdQueue()
        val one = File(tmp.newFolder("One"), "x.cbz").apply { writeBytes(ByteArray(10)) }
        val two = File(tmp.newFolder("Two"), "x.cbz").apply { writeBytes(ByteArray(20)) }
        val dest = tmp.newFolder("Dest")
        actions.pickUp(listOf(item(one)), move = false)
        actions.paste(dest)
        idleUntil("the first paste queued") { FileWork.operations.value.size == 2 }
        actions.pickUp(listOf(item(two)), move = true)
        actions.paste(dest)
        idleUntil("the second paste queued") { FileWork.operations.value.size == 3 }
        go.release()
        settle()
        assertEquals(listOf("x (2).cbz", "x.cbz"), dest.list()!!.sorted())
        assertEquals(10L, File(dest, "x.cbz").length())
        assertEquals(20L, File(dest, "x (2).cbz").length())
        assertFalse(two.exists())
        assertTrue(finished.all { it is FileWork.Outcome.Done })
    }

    @Test
    fun aQueuedReplaceKeepsBothWhenAnotherItemCameInItsPlace() {
        val go = holdQueue()
        val a = File(tmp.root, "x.cbz").apply { writeBytes(ByteArray(10)) }
        val dest = tmp.newFolder("Dest")
        val old = File(dest, "x.cbz").apply { writeBytes(ByteArray(3)) }
        actions.pickUp(listOf(item(a)), move = true)
        actions.paste(dest)
        idleUntil("asked about the name taken") { ShadowDialog.getLatestDialog()?.isShowing == true }
        answer(DialogInterface.BUTTON_POSITIVE)
        idleUntil("the paste queued") { FileWork.operations.value.size == 2 }
        old.delete()
        old.writeBytes(ByteArray(30))
        go.release()
        settle()
        assertEquals(30L, File(dest, "x.cbz").length())
        assertEquals(10L, File(dest, "x (2).cbz").length())
        assertFalse(a.exists())
    }

    @Test
    fun aQueuedRenameLeavesAloneWhatCameInItsPlace() = lookingUpAtOnce {
        val go = holdQueue()
        val a = File(tmp.root, "a.cbz").apply { writeBytes(ByteArray(10)) }
        val method = FileActions::class.java.getDeclaredMethod("renameTo", String::class.java, String::class.java).apply { isAccessible = true }
        method.invoke(actions, a.absolutePath, "b.cbz")
        idleUntil("the rename queued") { FileWork.operations.value.size == 2 }
        a.delete()
        a.writeBytes(ByteArray(30))
        go.release()
        settle()
        assertTrue(a.exists())
        assertFalse(File(tmp.root, "b.cbz").exists())
        assertEquals(context.getString(R.string.file_failed, context.getString(R.string.file_altered, "a.cbz")), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun cancelBetweenDeletesSaysHowManyWereDone() {
        val (fake, dir) = share()
        File(dir, "s.cbz").writeBytes(ByteArray(10))
        val a = File(tmp.root, "a.cbz").apply { writeBytes(ByteArray(10)) }
        // Held twice: as the items are looked up when chosen, then as the first is deleted.
        val go = holdQueue()
        val chosen = CountDownLatch(1)
        fake.slow = chosen
        actions.delete(listOf(FileClipboard.Item("/net/s1/s.cbz", false), item(a)))
        answer(DialogInterface.BUTTON_POSITIVE)
        idleUntil("the items' lookup held") { fake.held.get() > 0 }
        val hold = CountDownLatch(1)
        fake.slow = hold
        chosen.countDown()
        idleUntil("the items' lookup let go") { fake.held.get() == 0 }
        go.release()
        idleUntil("the first delete held") { fake.held.get() > 0 }
        FileWork.cancel()
        hold.countDown()
        settle()
        assertTrue(a.exists())
        assertEquals(FileWork.Outcome.Canceled(1, 2, listOf("/net/s1/s.cbz")), finished.last())
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
        idleUntil("the move finished") { finished.isNotEmpty() }
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
        idleUntil("asked about the name taken") { ShadowDialog.getLatestDialog()?.isShowing == true }
        fake.offline = true
        answer(DialogInterface.BUTTON_NEUTRAL)
        idleUntil("the paste finished") { finished.isNotEmpty() }
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
    fun aShareGoneUnreachableStopsTheItemsLeftOnIt() {
        val (fake, dir) = share()
        for (n in listOf("s1.cbz", "s2.cbz", "s3.cbz")) File(dir, n).writeBytes(ByteArray(20))
        val a = File(tmp.root, "a.cbz").apply { writeBytes(ByteArray(10)) }
        val dest = tmp.newFolder("Dest")
        // Each call tries the network again, as once the quiet time after a failure is over.
        var now = 0L
        Remotes.clock = { now += Remotes.QUIET_MS; now }
        val go = holdQueue()
        actions.pickUp(listOf("s1.cbz", "s2.cbz").map { FileClipboard.Item("/net/s1/$it", false) } + item(a) + FileClipboard.Item("/net/s1/s3.cbz", false), move = true)
        actions.paste(dest)
        idleUntil("the move queued") { FileWork.operations.value.size == 2 }
        fake.offline = true
        val before = fake.tries
        go.release()
        idleUntil("the move finished") { finished.size == 2 }
        settle()
        val tried = fake.tries - before
        // Only the first item on the share was tried; the device's went on.
        assertEquals(listOf("a.cbz"), dest.list()!!.toList())
        val failed = finished.last() as FileWork.Outcome.Failed
        assertEquals(1, failed.done)
        assertEquals(4, failed.count)
        assertEquals(
            context.getString(R.string.file_failed_part, context.resources.getQuantityString(R.plurals.moved_part, 4, 1, 4), context.getString(R.string.share_unreachable)),
            ShadowToast.getTextOfLatestToast()
        )
        assertEquals(listOf("s1.cbz", "s2.cbz", "s3.cbz"), FileClipboard.clips.value.single().items.map { it.name })
        // One try, at the first item.
        assertEquals(1, tried)
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
        idleUntil("asked about the names taken") { ShadowDialog.getLatestDialog()?.isShowing == true }
        answer(DialogInterface.BUTTON_POSITIVE)
        idleUntil("the paste finished") { finished.isNotEmpty() }
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
            idleUntil("asked about names taken") { ShadowDialog.getLatestDialog()?.isShowing == true }
            meanwhile()
            answer(button)
        }
        idleUntil("the paste finished") { finished.isNotEmpty() }
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
        idleUntil("asked about the name taken") { ShadowDialog.getLatestDialog()?.isShowing == true }
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
        idleUntil("asked about the name taken") { ShadowDialog.getLatestDialog()?.isShowing == true }
        fake.slow = hold
        answer(DialogInterface.BUTTON_NEUTRAL)
        idleUntil("the first move held") { fake.held.get() > 0 }
        FileWork.cancel()
        hold.countDown()
        idleUntil("the move finished") { finished.isNotEmpty() }
        settle()
        assertEquals(FileWork.Outcome.Canceled(1, 2, listOf("/net/s1/a.cbz"), givenBack = 1), finished.single())
        assertTrue(File(dest, "a.cbz").exists())
        assertEquals(listOf("/net/s1/b.cbz"), FileClipboard.clips.value.single().items.map { it.path })
        val res = context.resources
        assertEquals(
            context.getString(R.string.file_canceled_back_part, res.getQuantityString(R.plurals.moved_part, 2, 1, 2), res.getQuantityString(R.plurals.move_back, 1, 1)),
            ShadowToast.getTextOfLatestToast()
        )
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
        idleUntil("the move queued") { FileWork.operations.value.size == 2 }
        assertTrue(FileClipboard.clips.value.isEmpty())
        FileWork.cancel(FileWork.operations.value.last().id)
        idleUntil("the move canceled") { finished.any { it is FileWork.Outcome.Canceled } }
        assertEquals(1, (finished.single { it is FileWork.Outcome.Canceled } as FileWork.Outcome.Canceled).givenBack)
        assertEquals(
            context.getString(R.string.file_canceled_back, context.resources.getQuantityString(R.plurals.move_back, 1, 1)),
            ShadowToast.getTextOfLatestToast()
        )
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
        idleUntil("the delete and the move queued") { FileWork.operations.value.size == 3 }
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
