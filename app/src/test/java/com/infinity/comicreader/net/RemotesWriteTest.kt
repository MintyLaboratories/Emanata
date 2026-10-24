package com.infinity.comicreader.net

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

/** Changes to shares through [Remotes]: what was remembered of the places changed is forgotten. */
class RemotesWriteTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var fake: FakeRemoteFs

    @Before
    fun setUp() {
        dir = tmp.newFolder("share")
        File(dir, "Series").mkdirs()
        File(dir, "Series/001.cbz").writeBytes(ByteArray(10))
        fake = FakeRemoteFs(dir)
        Remotes.serve(listOf(FakeRemoteFs.share()), tmp.newFolder("listings")) { fake }
    }

    @After
    fun tearDown() {
        Remotes.serve(emptyList()) { throw AssertionError("no shares") }
    }

    @Test
    fun renamingForgetsTheOldPlaceAndBothFoldersListings() {
        Remotes.list("/net/s1")
        Remotes.list("/net/s1/Series")
        assertNotNull(Remotes.cachedStat("/net/s1/Series/001.cbz"))
        Remotes.mkdir("/net/s1/Other")
        Remotes.rename("/net/s1/Series/001.cbz", "/net/s1/Other/001.cbz")
        assertNull(Remotes.cachedStat("/net/s1/Series/001.cbz"))
        assertNull(Remotes.cachedList("/net/s1/Series"))
        assertNull(Remotes.cachedList("/net/s1"))
        assertEquals(listOf("001.cbz"), Remotes.list("/net/s1/Other").map { it.name })
    }

    @Test
    fun writesAndDeletes() {
        Remotes.create("/net/s1/new.cbz", ByteArrayInputStream(ByteArray(7)), 7)
        assertEquals(7L, Remotes.stat("/net/s1/new.cbz")?.size)
        Remotes.list("/net/s1/Series")
        Remotes.delete("/net/s1/Series")
        assertNull(Remotes.cachedList("/net/s1/Series"))
        assertNull(Remotes.stat("/net/s1/Series"))
        assertTrue(File(dir, "new.cbz").isFile)
    }

    @Test
    fun neverDeletesOrRenamesAShareOrAcrossShares() {
        for (change in listOf<() -> Unit>(
            { Remotes.delete("/net/s1") },
            { Remotes.rename("/net/s1", "/net/s1/x") },
            { Remotes.rename("/net/s1/Series", "/net/s2/Series") }
        )) {
            try {
                change()
                fail("no failure")
            } catch (e: IllegalArgumentException) {
                // Expected.
            }
        }
        assertTrue(File(dir, "Series").isDirectory)
    }

    @Test
    fun aReadOnlyShareStaysBrowsable() {
        fake.readOnly = true
        try {
            Remotes.mkdir("/net/s1/x")
            fail("no failure")
        } catch (e: RemoteException) {
            assertEquals(RemoteException.Reason.DENIED, e.reason)
        }
        assertEquals(listOf("Series"), Remotes.list("/net/s1").map { it.name })
    }
}
