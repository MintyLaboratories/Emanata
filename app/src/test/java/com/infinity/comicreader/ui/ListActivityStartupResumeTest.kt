package com.infinity.comicreader.ui

import com.infinity.comicreader.testing.ref
import com.infinity.comicreader.testing.bookId
import android.Manifest
import android.app.Application
import android.content.DialogInterface
import android.os.Environment
import android.os.Looper
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.infinity.comicreader.testing.idleUntil
import com.infinity.comicreader.data.HistoryItem
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.ResumeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File

/** As the app starts, it offers to reopen the book read last. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ListActivityStartupResumeTest {

    private lateinit var context: Application
    private lateinit var prefs: Prefs
    private lateinit var library: Library
    private lateinit var book: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        for (name in listOf("ui", "library")) context.getSharedPreferences(name, 0).edit().clear().commit()
        prefs = Prefs(context)
        library = Library(context)
        val dir = File(Environment.getExternalStorageDirectory(), "shelf").apply { mkdirs() }
        book = File(dir, "two.cbz").apply { createNewFile() }
        library.addHistory(HistoryItem(book.absolutePath, book.name, 4, 10, 1L, bookId(book)))
        ShadowDialog.reset()
    }

    /** True once the screen waits on nothing but the test: listed, favorites classified, facts read, presence asked, its menu looked into. */
    private fun ListActivity.quiet() =
        listJob?.isCompleted == true && listOf(classifyJob, factsJob, presenceJob, moreLook).none { it?.isActive == true }

    /** Runs the main looper until the screen is quiet. */
    private fun ListActivity.settle() = idleUntil("the library screen quiet") { quiet() }

    /** Runs what is due on the main thread: what a click or a dialog posts there. */
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun start(): ActivityController<ListActivity> =
        Robolectric.buildActivity(ListActivity::class.java).setup().also { it.get().settle() }

    private fun offered(): AlertDialog? = (ShadowDialog.getLatestDialog() as? AlertDialog)?.takeIf { it.isShowing }

    private fun answer(dialog: AlertDialog, yes: Boolean, dontAsk: Boolean = false) {
        dialog.findViewById<CheckBox>(R.id.resume_dont_ask)!!.isChecked = dontAsk
        dialog.getButton(if (yes) DialogInterface.BUTTON_POSITIVE else DialogInterface.BUTTON_NEGATIVE).performClick()
        idle()
    }

    private fun ActivityController<ListActivity>.opened() = shadowOf(get()).nextStartedActivity

    /** No work of this test's screens runs on into the next. */
    @After
    fun awaitLeftovers() = awaitLeftoverWork()

    @Test
    fun yesOpensTheLastBookWhereItWasLeft() {
        val controller = start()
        val dialog = offered()!!
        val message = dialog.findViewById<TextView>(android.R.id.message)!!.text.toString()
        assertTrue(message, message.startsWith(book.name) && message.contains("5"))
        answer(dialog, yes = true)
        val started = controller.opened()
        assertEquals(book.absolutePath, started.getStringExtra(ViewerActivity.EXTRA_PATH))
        assertEquals(4, started.getIntExtra(ViewerActivity.EXTRA_PAGE, -1))
        assertEquals(ResumeMode.ASK, prefs.startupResume)
    }

    @Test
    fun noWithDontAskAgainNeverAsksAgain() {
        val controller = start()
        answer(offered()!!, yes = false, dontAsk = true)
        assertNull(controller.opened())
        assertEquals(ResumeMode.NO, prefs.startupResume)
        ShadowDialog.reset()
        start()
        assertNull(offered())
    }

    @Test
    fun yesWithDontAskAgainReopensWithoutAsking() {
        start()
        answer(offered()!!, yes = true, dontAsk = true)
        assertEquals(ResumeMode.YES, prefs.startupResume)
        ShadowDialog.reset()
        val controller = start()
        assertNull(offered())
        assertEquals(book.absolutePath, controller.opened().getStringExtra(ViewerActivity.EXTRA_PATH))
    }

    @Test
    fun aFinishedBookIsNotOffered() {
        library.setRead(ref(book), true)
        start()
        assertNull(offered())
    }

    @Test
    fun aRecreatedScreenDoesNotAskAgain() {
        val controller = start()
        offered()!!.dismiss()
        ShadowDialog.reset()
        controller.recreate()
        controller.get().settle()
        assertNull(offered())
    }

    @Test
    fun anOfferNotAnsweredYetIsAskedAgainAfterARecreate() {
        val controller = start()
        offered()!!
        ShadowDialog.reset()
        controller.recreate()
        controller.get().settle()
        answer(offered()!!, yes = true)
        assertEquals(book.absolutePath, controller.opened().getStringExtra(ViewerActivity.EXTRA_PATH))
    }
}
