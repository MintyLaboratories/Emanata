package com.infinity.comicreader.net

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
    fun thePasswordStaysOutOfText() {
        val share = ok(ShareForm.build("A", "nas.local/books", "user", "hunter2", null))
        assertFalse(share.toString().contains("hunter2"))
    }
}
