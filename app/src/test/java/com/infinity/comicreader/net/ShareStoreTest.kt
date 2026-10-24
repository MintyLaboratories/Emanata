package com.infinity.comicreader.net

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.spec.SecretKeySpec

/** Shares and their sealed passwords; the JVM has no Android Keystore, so a fixed AES key stands in for its key. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ShareStoreTest {

    private lateinit var context: Context
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private val box = AesGcmBox { key }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("shares", 0).edit().clear().commit()
    }

    @After
    fun tearDown() {
        Remotes.serve(emptyList()) { throw AssertionError("no shares") }
    }

    private fun share(id: String, name: String, password: String) =
        Share(id, name, SharePath("nas.local", 445, "books", "comics"), "WORKGROUP\\user", password)

    @Test
    fun sharesRoundTripWithTheirPasswords() {
        val store = ShareStore(context, box)
        store.put(share("a1", "Share A", "pw-a"))
        store.put(share("b2", "Share B", ""))
        val again = ShareStore(context, box)
        assertEquals(listOf(share("a1", "Share A", "pw-a"), share("b2", "Share B", "")), again.all())
        assertEquals("pw-a", again.get("a1")?.password)
    }

    @Test
    fun webDavSharesRoundTripBesideSmbOnes() {
        val store = ShareStore(context, box)
        val dav = Share("d3", "Share D", DavUrl(true, "nas.local", 8443, "dav/comics"), "user", "pw-d")
        val local = Share("d4", "Share E", DavUrl(false, "localhost", 8080, ""), "", "")
        store.put(share("a1", "Share A", "pw-a"))
        store.put(dav)
        store.put(local)
        assertEquals(listOf(share("a1", "Share A", "pw-a"), dav, local), ShareStore(context, box).all())
        assertEquals(ShareKind.WEBDAV, store.get("d3")!!.kind)
        assertEquals(ShareKind.SMB, store.get("a1")!!.kind)
    }

    @Test
    fun passwordsAreNeverStoredInTheClear() {
        ShareStore(context, box).put(share("a1", "Share A", "plain-secret"))
        val stored = context.getSharedPreferences("shares", 0).all.values.joinToString()
        assertFalse(stored.contains("plain-secret"))
        assertTrue(stored.contains("Share A"))
    }

    @Test
    fun anotherKeyCannotOpenThemAndTheyReadEmptyAndLost() {
        ShareStore(context, box).put(share("a1", "Share A", "pw-a"))
        ShareStore(context, box).put(share("b2", "Share B", ""))
        val other = ShareStore(context, AesGcmBox { SecretKeySpec(ByteArray(32) { 7 }, "AES") })
        assertEquals("", other.get("a1")?.password)
        assertTrue(other.get("a1")!!.passwordLost)
        assertEquals("Share A", other.get("a1")?.name)
        // No password is not a lost one.
        assertFalse(other.get("b2")!!.passwordLost)
        // Editing it without a new password keeps it lost; a new one ends that.
        val kept = (ShareForm.build("Share A", "nas.local/books", "user", "", other.get("a1")) as ShareForm.Result.Ok).share
        other.put(kept)
        assertTrue(ShareStore(context, box).get("a1")!!.passwordLost)
        val entered = (ShareForm.build("Share A", "nas.local/books", "user", "pw-new", other.get("a1")) as ShareForm.Result.Ok).share
        other.put(entered)
        assertFalse(other.get("a1")!!.passwordLost)
        assertEquals("pw-new", other.get("a1")?.password)
    }

    @Test
    fun editingReplacesInPlaceAndRemovingForgets() {
        val store = ShareStore(context, box)
        store.put(share("a1", "Share A", "pw"))
        store.put(share("b2", "Share B", "pw"))
        store.put(share("a1", "Share C", "pw2"))
        assertEquals(listOf("Share C", "Share B"), store.all().map { it.name })
        store.remove("a1")
        assertEquals(listOf("b2"), store.all().map { it.id })
        assertNull(store.get("a1"))
    }

    @Test
    fun aDamagedListReadsEmpty() {
        context.getSharedPreferences("shares", 0).edit().putString("list", "{not json").commit()
        assertEquals(emptyList<Share>(), ShareStore(context, box).all())
    }

    @Test
    fun changesReachTheSharesServed() {
        val store = ShareStore(context, box)
        Remotes.init(context)
        assertEquals(emptyList<Share>(), Remotes.shares())
        store.put(share("a1", "Share A", "pw"))
        assertEquals(listOf("Share A"), Remotes.shares().map { it.name })
        store.remove("a1")
        assertEquals(emptyList<Share>(), Remotes.shares())
    }

    @Test
    fun sealingUsesAFreshNonceAndDetectsTampering() {
        val plain = "pw".toByteArray()
        val a = box.seal(plain)
        val b = box.seal(plain)
        assertNotEquals(a.toList(), b.toList())
        assertArrayEquals(plain, box.open(a))
        a[a.size - 1] = (a[a.size - 1].toInt() xor 1).toByte()
        try {
            box.open(a)
            fail("tampered secret opened")
        } catch (e: Exception) {
            // Expected: the tag no longer matches.
        }
    }

    @Test
    fun loadingThumbnailsIsKeptPerShare() {
        val store = ShareStore(context, box)
        store.put(share("a1", "Share A", "pw").copy(thumbnails = false))
        store.put(share("b2", "Share B", "pw"))
        assertEquals(listOf(false, true), ShareStore(context, box).all().map { it.thumbnails })
    }
}
