package minty.emanata.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ShareFormTest {

    private fun ok(result: ShareForm.Result) = (result as ShareForm.Result.Ok).share

    @Test
    fun aBlankNameTakesTheLastFolderOrTheShare() {
        assertEquals("series", ok(ShareForm.build(" ", "nas.local/books/comics/series", "", "", null)).name)
        assertEquals("books", ok(ShareForm.build("", "nas.local/books", "", "", null)).name)
        assertEquals("Share A", ok(ShareForm.build(" Share A ", "nas.local/books", "", "", null)).name)
    }

    @Test
    fun userAndDomain() {
        val share = ok(ShareForm.build("A", "nas.local/books", " WORKGROUP\\user ", "pw", null))
        assertEquals("WORKGROUP\\user", share.user)
        assertEquals("WORKGROUP", share.domain)
        assertEquals("user", share.account)
        val plain = ok(ShareForm.build("A", "nas.local/books", "user", "pw", null))
        assertEquals("", plain.domain)
        assertEquals("user", plain.account)
    }

    @Test
    fun editingKeepsTheIdAndABlankPasswordKeepsTheOldOne() {
        val first = ok(ShareForm.build("A", "nas.local/books", "user", "pw", null))
        val edited = ok(ShareForm.build("B", "nas.local/books/comics", "user", "", first))
        assertEquals(first.id, edited.id)
        assertEquals("pw", edited.password)
        assertEquals("comics", edited.path.dir)
        assertEquals("new", ok(ShareForm.build("B", "nas.local/books", "user", "new", first)).password)
        assertNotEquals(first.id, ok(ShareForm.build("A", "nas.local/books", "user", "pw", null)).id)
    }

    @Test
    fun aBadPathSaysWhy() {
        assertEquals(
            ShareForm.Result.Invalid(SharePath.Problem.NO_SHARE),
            ShareForm.build("A", "nas.local", "user", "pw", null)
        )
    }

    @Test
    fun aWebDavShareTakesAnAddress() {
        val share = ok(ShareForm.build("", "https://nas.local/dav/comics", "user", "pw", null, ShareKind.WEBDAV))
        assertEquals(DavUrl(true, "nas.local", 443, "dav/comics"), share.path)
        assertEquals("comics", share.name)
        assertEquals(ShareKind.WEBDAV, share.kind)
        assertEquals("nas.local", ok(ShareForm.build("", "nas.local", "", "", null, ShareKind.WEBDAV)).name)
        assertEquals(
            ShareForm.Result.Invalid(SharePath.Problem.INSECURE),
            ShareForm.build("A", "http://nas.example/dav", "user", "pw", null, ShareKind.WEBDAV)
        )
        assertEquals(DavUrl(false, "nas.local", 80, "dav"), ok(ShareForm.build("A", "http://nas.local/dav", "user", "pw", null, ShareKind.WEBDAV)).path)
        assertEquals(
            ShareForm.Result.Invalid(SharePath.Problem.WEB_ADDRESS),
            ShareForm.build("A", "https://nas.local/dav", "user", "pw", null, ShareKind.SMB)
        )
    }

    @Test
    fun editingCanSwitchHowTheShareIsReached() {
        val first = ok(ShareForm.build("A", "nas.local/books", "user", "pw", null))
        val edited = ok(ShareForm.build("A", "https://nas.local/books", "user", "new", first, ShareKind.WEBDAV))
        assertEquals(first.id, edited.id)
        assertEquals("new", edited.password)
        assertEquals(ShareKind.WEBDAV, edited.kind)
    }

    @Test
    fun aBlankPasswordStaysWithTheServerItWasSavedFor() {
        val smb = ok(ShareForm.build("A", "nas.local/books", "user", "pw", null))
        val again = ShareForm.Result.Invalid(SharePath.Problem.PASSWORD_AGAIN)
        // Another protocol, host or port: asked for again.
        assertEquals(again, ShareForm.build("A", "https://nas.local/books", "user", "", smb, ShareKind.WEBDAV))
        assertEquals(again, ShareForm.build("A", "other.local/books", "user", "", smb))
        assertEquals(again, ShareForm.build("A", "nas.local:4450/books", "user", "", smb))
        // The same server, another share or folder, any case of its name: kept.
        assertEquals("pw", ok(ShareForm.build("A", "NAS.local/other/folder", "user", "", smb)).password)
        val dav = ok(ShareForm.build("A", "https://nas.local/dav", "user", "pw", null, ShareKind.WEBDAV))
        assertEquals(again, ShareForm.build("A", "https://nas.local:8443/dav", "user", "", dav, ShareKind.WEBDAV))
        assertEquals(again, ShareForm.build("A", "https://cloud.example.com/dav", "user", "", dav, ShareKind.WEBDAV))
        assertEquals("pw", ok(ShareForm.build("A", "https://nas.local/elsewhere", "user", "", dav, ShareKind.WEBDAV)).password)
        // A lost password is asked for again too; no password at all needs none.
        assertEquals(again, ShareForm.build("A", "other.local/books", "user", "", smb.copy(password = "", passwordLost = true)))
        val open = ok(ShareForm.build("A", "nas.local/books", "", "", null))
        assertEquals("", ok(ShareForm.build("A", "other.local/books", "", "", open)).password)
    }

    @Test
    fun thePasswordStaysOutOfText() {
        val share = ok(ShareForm.build("A", "nas.local/books", "user", "hunter2", null))
        assertFalse(share.toString().contains("hunter2"))
    }

    @Test
    fun clearingThePasswordStoresNoneEvenWhenItWasLost() {
        val first = ok(ShareForm.build("A", "nas.local/books", "user", "pw", null))
        val cleared = ok(ShareForm.build("A", "nas.local/books", "user", "", first, clearPassword = true))
        assertEquals("", cleared.password)
        val lost = first.copy(password = "", passwordLost = true)
        val fresh = ok(ShareForm.build("A", "nas.local/books", "user", "", lost, clearPassword = true))
        assertEquals(false, fresh.passwordLost)
        // Typed after clearing, the new one counts; another host needs no password again.
        assertEquals("new", ok(ShareForm.build("A", "nas.local/books", "user", "new", first, clearPassword = true)).password)
        assertEquals("", ok(ShareForm.build("A", "other.local/books", "user", "", first, clearPassword = true)).password)
    }

    @Test
    fun thumbnailsFollowTheSwitchAndStayOnAnEdit() {
        val off = ok(ShareForm.build("A", "nas.local/books", "user", "pw", null, thumbnails = false))
        assertEquals(false, off.thumbnails)
        assertEquals(false, ok(ShareForm.build("B", "nas.local/books", "user", "", off)).thumbnails)
        assertEquals(true, ok(ShareForm.build("A", "nas.local/books", "user", "pw", null)).thumbnails)
    }
}
