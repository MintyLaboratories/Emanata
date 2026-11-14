package com.infinity.comicreader.ai

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Page times learned on this device: kept by model and its files, written only on a real change, read back by a new instance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PageTimesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val upscale = ModelCatalog.byId(ModelCatalog.DEFAULT_UPSCALE)!!
    private val descreen = ModelCatalog.forStep(AiStep.DESCREEN).first()

    @Test
    fun aTimeIsKeptPerModelAndReadBack() {
        val file = File(tmp.root, "times")
        val times = PageTimes(file)
        assertNull(times.pageMs(upscale))
        times.keep(upscale, 4_200)
        times.keep(descreen, 9_000)
        assertEquals(4_200L, times.pageMs(upscale))
        val again = PageTimes(file)
        assertEquals(4_200L, again.pageMs(upscale))
        assertEquals(9_000L, again.pageMs(descreen))
    }

    @Test
    fun aSmallChangeIsNotWrittenALargerOneIs() {
        val file = File(tmp.root, "times")
        val times = PageTimes(file)
        times.keep(upscale, 4_000)
        val written = file.lastModified()
        file.setLastModified(written - 10_000)
        times.keep(upscale, 4_150)
        assertEquals(4_000L, times.pageMs(upscale))
        assertEquals("not written", written - 10_000, file.lastModified())
        times.keep(upscale, 4_200)
        assertEquals(4_200L, times.pageMs(upscale))
        assertEquals(4_200L, PageTimes(file).pageMs(upscale))
        times.keep(upscale, 0)
        assertEquals("nothing to learn from", 4_200L, times.pageMs(upscale))
    }

    @Test
    fun newFilesForgetTheOldTime() {
        val times = PageTimes(File(tmp.root, "times"))
        times.keep(upscale, 4_000)
        val replaced = upscale.copy(bin = upscale.bin.copy(sha256 = "0".repeat(64)))
        assertNull(times.pageMs(replaced))
        times.keep(replaced, 4_100)
        assertEquals("the replaced model's own, though close", 4_100L, times.pageMs(replaced))
        assertNull(times.pageMs(upscale))
    }

    @Test
    fun anUnreadableFileReadsAsNoneLearned() {
        val file = File(tmp.root, "times")
        file.writeText("garbage\n${upscale.id}\t${upscale.fingerprint}\tnot-a-number\n${descreen.id}\t${descreen.fingerprint}\t5000\n")
        val times = PageTimes(file)
        assertNull(times.pageMs(upscale))
        assertEquals(5_000L, times.pageMs(descreen))
    }
}
