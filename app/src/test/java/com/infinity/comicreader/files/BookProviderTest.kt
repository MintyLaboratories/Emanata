package com.infinity.comicreader.files

import android.app.Application
import android.content.pm.ProviderInfo
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.net.FakeRemoteFs
import com.infinity.comicreader.net.Remotes
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BookProviderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Application
    private lateinit var provider: BookProvider

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val info = ProviderInfo().apply {
            authority = BookUris.authority(context.packageName)
            grantUriPermissions = true
        }
        provider = Robolectric.buildContentProvider(BookProvider::class.java).create(info).get()
    }

    @After
    fun tearDown() {
        Remotes.serve(emptyList()) { throw AssertionError("no shares") }
    }

    private fun uri(path: String) = BookUris.of(context, path)

    @Test
    fun urisStandForThePathIssuedAndEndInTheName() {
        for (path in listOf("/storage/books/Ünïcode set/Vol 1 #2 (draft).cbz", "/net/s1/a/b.cbr", "/x/a%20b?.pdf", "/x/tab\tand\nline.cbz")) {
            val uri = uri(path)
            assertEquals("content", uri.scheme)
            assertEquals(BookUris.authority(context.packageName), uri.authority)
            assertEquals(listOf(path.substringAfterLast('/')), uri.pathSegments.drop(1))
            assertEquals(path, BookUris.pathOf(context, Uri.parse(uri.toString())))
            // The same book keeps its URI.
            assertEquals(uri, uri(path))
        }
        val authority = BookUris.authority(context.packageName)
        assertNull(BookUris.pathOf(context, Uri.parse("content://$authority/not*a*key/x.cbz")))
        assertNull(BookUris.pathOf(context, Uri.parse("content://$authority/AAAAAAAAAAAAAAAAAAAAAA/x.cbz")))
        assertNull(BookUris.pathOf(context, Uri.parse("content://$authority")))
        assertNull(BookUris.pathOf(context, Uri.parse("content://other.books/${uri("/x/a.cbz").pathSegments[0]}/a.cbz")))
    }

    @Test
    fun aUriTellsNothingOfTheFolders() {
        val path = "/storage/books/Private folder/Sub/a.cbz"
        val text = uri(path).path!!
        for (part in listOf("storage", "books", "Private", "Sub")) assertFalse(part, text.contains(part))
        val key = uri(path).pathSegments[0]
        assertFalse(text.contains(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(path.toByteArray()).take(8)))
        assertEquals(22, key.length)
        assertFalse(key == uri("/storage/books/Private folder/Sub/b.cbz").pathSegments[0])
    }

    @Test
    fun theLatestKeysAreKeptAndStillOpenFromANewProcess() {
        val first = uri("/b/0.cbz")
        repeat(SharedKeys.MAX) { uri("/b/${it + 1}.cbz") }
        assertNull(BookUris.pathOf(context, first))
        val latest = uri("/b/${SharedKeys.MAX}.cbz")
        // Read back from disk, as another process or a restarted app would.
        assertEquals("/b/${SharedKeys.MAX}.cbz", SharedKeys(File(context.filesDir, SharedKeys.FILE)).pathOf(latest.pathSegments[0]))
        // Sharing an old book again keeps it as the latest.
        val again = uri("/b/1.cbz")
        repeat(SharedKeys.MAX - 1) { uri("/c/$it.cbz") }
        assertEquals("/b/1.cbz", BookUris.pathOf(context, again))
    }

    @Test
    fun typesFollowTheExtension() {
        assertEquals("application/vnd.comicbook+zip", BookUris.mimeOf("a.CBZ"))
        assertEquals("application/vnd.comicbook-rar", BookUris.mimeOf("a.cbr"))
        assertEquals("application/pdf", BookUris.mimeOf("a.pdf"))
        assertEquals("image/jpeg", BookUris.mimeOf("a.jpeg"))
        assertEquals("application/octet-stream", BookUris.mimeOf("a"))
        assertEquals("application/x-cb7", provider.getType(uri("/b/a.cb7")))
    }

    @Test
    fun aDeviceFileIsNamedSizedAndReadAsItIs() {
        val bytes = ByteArray(70_000) { (it * 13).toByte() }
        val book = File(tmp.root, "One shot.cbz").apply { writeBytes(bytes) }
        val cursor = provider.query(uri(book.absolutePath), null, null, null, null)!!
        cursor.use {
            it.moveToFirst()
            assertEquals("One shot.cbz", it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)))
            assertEquals(70_000L, it.getLong(it.getColumnIndexOrThrow(OpenableColumns.SIZE)))
        }
        val read = provider.openFile(uri(book.absolutePath), "r").use { FileInputStream(it.fileDescriptor).readBytes() }
        assertArrayEquals(bytes, read)
    }

    @Test
    fun onlyTheColumnsAskedFor() {
        val book = File(tmp.root, "a.cbz").apply { writeBytes(ByteArray(3)) }
        provider.query(uri(book.absolutePath), arrayOf(OpenableColumns.SIZE, "_data"), null, null, null)!!.use {
            assertEquals(listOf(OpenableColumns.SIZE), it.columnNames.toList())
        }
    }

    @Test(expected = SecurityException::class)
    fun neverWritten() {
        val book = File(tmp.root, "a.cbz").apply { writeBytes(ByteArray(3)) }
        provider.openFile(uri(book.absolutePath), "rw")
    }

    @Test(expected = java.io.FileNotFoundException::class)
    fun aMissingFileIsNotFound() {
        provider.openFile(uri(File(tmp.root, "gone.cbz").absolutePath), "r")
    }

    @Test
    fun aShareFileIsSizedAsItsListingFoundIt() {
        val dir = tmp.newFolder("share")
        File(dir, "far.cbz").writeBytes(ByteArray(1234))
        val fake = FakeRemoteFs(dir)
        Remotes.serve(listOf(FakeRemoteFs.share())) { fake }
        Remotes.list("/net/s1")
        val asked = fake.attempts
        provider.query(uri("/net/s1/far.cbz"), null, null, null, null)!!.use {
            it.moveToFirst()
            assertEquals("far.cbz", it.getString(0))
            assertEquals(1234L, it.getLong(1))
        }
        // Known from the listing: the share is not asked again.
        assertEquals(asked, fake.attempts)
    }

    @Test
    fun aForgottenStatIsAskedOfTheShareAgain() {
        val dir = tmp.newFolder("share")
        val file = File(dir, "far.cbz").apply { writeBytes(ByteArray(1234)) }
        val fake = FakeRemoteFs(dir)
        Remotes.serve(listOf(FakeRemoteFs.share())) { fake }
        Remotes.list("/net/s1")
        file.writeBytes(ByteArray(2000))
        Remotes.forgetStat("/net/s1/far.cbz")
        provider.query(uri("/net/s1/far.cbz"), arrayOf(OpenableColumns.SIZE), null, null, null)!!.use {
            it.moveToFirst()
            assertEquals(2000L, it.getLong(0))
        }
    }
}
