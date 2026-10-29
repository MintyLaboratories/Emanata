package com.infinity.comicreader.util

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A store is trimmed least recently used first, sparing what it is told to keep. */
class CacheFilesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun trimDropsTheOldestUntilUnderTheCapAndSparesKeptFiles() {
        val dir = tmp.newFolder("store")
        (1..5).forEach { i -> File(dir, "f$i").apply { writeBytes(ByteArray(10)); setLastModified(i * 1_000_000L) } }
        CacheFiles.trim(dir, maxBytes = 3 * CacheFiles.BLOCK) { it.name == "f1" }
        assertEquals(listOf("f1", "f4", "f5"), dir.list()!!.sorted())
    }

    @Test
    fun aStoreNotThereIsLeftBe() {
        CacheFiles.trim(File(tmp.root, "absent"), maxBytes = 0)
    }
}
