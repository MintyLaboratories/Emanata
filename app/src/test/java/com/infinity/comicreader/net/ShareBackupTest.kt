package com.infinity.comicreader.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Shares in backups: passwords sealed for this device, lost anywhere else. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ShareBackupTest {

    private val smb = Share("a1b2c3", "Share A", SharePath("nas.local", 445, "books", "comics"), "WORKGROUP\\user", "pw-a", thumbnails = false)
    private val dav = Share("d4e5f6", "Share D", DavUrl(false, "192.168.200.7", 8080, "dav"), "user", "pw-d")
    private val open = Share("0f0f0f", "Share G", SharePath("nas.local", 445, "public", ""), "", "")

    @Test
    fun onTheSameDeviceSharesComeBackWhole() {
        val json = ShareBackup.export(listOf(smb, dav, open), "device-1", withPasswords = true)
        assertFalse("no password in the clear", json.toString().contains("pw-a") || json.toString().contains("pw-d"))
        assertEquals(listOf(smb, dav, open), ShareBackup.import(json, "device-1"))
    }

    @Test
    fun onAnotherDevicePasswordsAreLostAndAskedForAgain() {
        val json = ShareBackup.export(listOf(smb, open), "device-1", withPasswords = true)
        val restored = ShareBackup.import(json, "device-2")
        assertEquals(listOf("", ""), restored.map { it.password })
        assertEquals(listOf(true, false), restored.map { it.passwordLost })
        assertEquals(smb.path, restored[0].path)
        assertEquals(false, restored[0].thumbnails)
    }

    @Test
    fun withoutPasswordsNoneIsWrittenAndTheyAreAskedForAgain() {
        val json = ShareBackup.export(listOf(smb), "device-1", withPasswords = false)
        assertFalse(json.toString().contains("\"password\""))
        val restored = ShareBackup.import(json, "device-1").single()
        assertTrue(restored.passwordLost)
        assertEquals("", restored.password)
    }

    @Test
    fun withoutADeviceIdPasswordsAreLeftOut() {
        val restored = ShareBackup.import(ShareBackup.export(listOf(smb), null, withPasswords = true), "device-1").single()
        assertTrue(restored.passwordLost)
    }

    @Test
    fun aPasswordOpensOnlyForTheServerAndUserItWasSealedFor() {
        val edits = listOf<(org.json.JSONObject) -> Unit>(
            { it.put("host", "nas2.local") }, { it.put("port", 4445) }, { it.put("share", "other") },
            { it.put("user", "someone") }, { it.put("id", "a1b2c4") }
        )
        for ((i, edit) in edits.withIndex()) {
            val json = ShareBackup.export(listOf(smb), "device-1", withPasswords = true)
            edit(json.getJSONArray("list").getJSONObject(0))
            val restored = ShareBackup.import(json, "device-1").single()
            assertEquals("edit $i", "", restored.password)
            assertTrue("edit $i", restored.passwordLost)
        }
        val json = ShareBackup.export(listOf(dav.copy(path = DavUrl(true, "nas.local", 443, "dav"))), "device-1", withPasswords = true)
        json.getJSONArray("list").getJSONObject(0).put("secure", false).put("host", "192.168.200.7")
        assertTrue("to plain http", ShareBackup.import(json, "device-1").single().passwordLost)
        val kind = ShareBackup.export(listOf(smb), "device-1", withPasswords = true)
        kind.getJSONArray("list").getJSONObject(0).put("kind", "webdav").put("secure", true)
        assertTrue("to WebDAV", ShareBackup.import(kind, "device-1").single().passwordLost)
    }

    @Test
    fun aBackupAskingForEndlessIterationsIsCapped() {
        val json = ShareBackup.export(listOf(smb), "device-1", withPasswords = true).put("iterations", Int.MAX_VALUE)
        val started = System.nanoTime()
        val restored = ShareBackup.import(json, "device-1").single()
        assertTrue("capped", System.nanoTime() - started < 60_000_000_000L)
        assertTrue("another key", restored.passwordLost)
    }

    @Test
    fun malformedOrUnsafeEntriesAreSkipped() {
        val json = ShareBackup.export(listOf(smb), "device-1", withPasswords = true)
        val list = json.getJSONArray("list")
        list.put(org.json.JSONObject().put("id", "../x").put("kind", "smb").put("host", "nas.local").put("share", "b"))
        list.put(org.json.JSONObject().put("id", "abc").put("kind", "webdav").put("host", "192.0.2.10").put("secure", false))
        list.put(org.json.JSONObject().put("id", "abd").put("kind", "smb").put("host", "nas.local"))
        list.put(org.json.JSONObject().put("id", "abe").put("kind", "ftp").put("host", "nas.local"))
        assertEquals(listOf(smb), ShareBackup.import(json, "device-1"))
    }
}
