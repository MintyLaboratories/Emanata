package com.infinity.comicreader.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ShareIntentsTest {
    private val cover = Uri.parse("content://app.files/shared/cover/Title.png")
    private val file = Uri.parse("content://app.books/key/Title.cbz")
    private val cbz = "application/vnd.comicbook+zip"

    private fun Intent.clipUris() = (0 until clipData!!.itemCount).map { clipData!!.getItemAt(it).uri }

    private fun Intent.granted() = flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0

    @Test
    fun nothingSendsNothing() {
        assertNull(ShareIntents.of(null, null, null, cbz))
    }

    @Test
    fun theTitleAloneIsText() {
        val intent = ShareIntents.of("Title", null, null, cbz)!!
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals("Title", intent.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals("Title", intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertNull(intent.clipData)
    }

    @Test
    fun oneStreamWithTheTitle() {
        val intent = ShareIntents.of("Title", null, file, cbz)!!
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals(cbz, intent.type)
        @Suppress("DEPRECATION")
        assertEquals(file, intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals("Title", intent.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(listOf(file), intent.clipUris())
        assertTrue(intent.granted())

        val coverOnly = ShareIntents.of(null, cover, null, cbz)!!
        assertEquals("image/png", coverOnly.type)
        assertNull(coverOnly.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun coverAndFileGoTogether() {
        val intent = ShareIntents.of("Title", cover, file, cbz)!!
        assertEquals(Intent.ACTION_SEND_MULTIPLE, intent.action)
        assertEquals("*/*", intent.type)
        @Suppress("DEPRECATION")
        assertEquals(listOf(cover, file), intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals(listOf(cover, file), intent.clipUris())
        assertEquals("Title", intent.getStringExtra(Intent.EXTRA_TEXT))
        assertTrue(intent.granted())
    }

    @Test
    fun typesNarrowAsFarAsTheyAgree() {
        assertEquals("image/png", ShareIntents.commonType(listOf("image/png", "image/png")))
        assertEquals("image/*", ShareIntents.commonType(listOf("image/png", "image/jpeg")))
        assertEquals("*/*", ShareIntents.commonType(listOf("image/png", "application/pdf")))
    }
}
