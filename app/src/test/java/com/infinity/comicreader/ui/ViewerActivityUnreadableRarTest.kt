package com.infinity.comicreader.ui

import android.app.Application
import android.content.Intent
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.ResumeMode
import com.infinity.comicreader.reader.Fixtures
import com.infinity.comicreader.testing.idleUntil
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.File

/** A RAR the reader turns down closes with the usual message. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewerActivityUnreadableRarTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val pages = (0 until 3).map { Fixtures.entry("p$it.png", byteArrayOf(0)) }.toTypedArray()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        Prefs(context).resumeMode = ResumeMode.NO
    }

    private fun openClosesWithTheUsualMessage(bytes: ByteArray) {
        val book = File(tmp.newFolder(), "book.cbr").apply { writeBytes(bytes) }
        val intent = Intent(ApplicationProvider.getApplicationContext(), ViewerActivity::class.java)
            .putExtra(ViewerActivity.EXTRA_PATH, book.absolutePath)
        val viewer = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup().get()
        idleUntil("the viewer closing") { viewer.isFinishing }
        assertEquals(viewer.getString(R.string.error_open), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun anEncryptedRarClosesWithTheUsualMessage() {
        openClosesWithTheUsualMessage(Fixtures.rar5Bytes(*pages, encrypted = true))
    }

    @Test
    fun aSolidRar4ClosesWithTheUsualMessage() {
        openClosesWithTheUsualMessage(Fixtures.rar4Bytes(*pages, solid = true))
    }
}
