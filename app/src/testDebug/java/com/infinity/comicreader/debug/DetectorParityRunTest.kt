package com.infinity.comicreader.debug

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.ai.DownloadException
import com.infinity.comicreader.ai.DownloadFailure
import com.infinity.comicreader.ai.ModelCatalog
import com.infinity.comicreader.ai.ModelStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.RandomAccessFile

/** The parity check's run: its result line on every path. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DetectorParityRunTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun run(download: () -> Unit): Pair<List<JSONObject>, List<String>> {
        ModelStore.of(context).delete(ModelCatalog.TEXT_DETECTOR)
        val logged = ArrayList<JSONObject>()
        val said = ArrayList<String>()
        DetectorParity(context, { logged += it }, { said += it }) { _, _ -> download() }.run()
        return logged to said
    }

    @Test
    fun aDownloadThatFailsEndsInFail() {
        val (logged, said) = run { throw DownloadException(DownloadFailure.NETWORK, "no route") }
        assertEquals(listOf("PARITY: FAIL"), said)
        assertEquals("download: no route", logged.single { it.has("failed") }.getString("failed"))
    }

    @Test
    fun anyOtherFailureEndsInFailToo() {
        val (logged, said) = run { throw IllegalStateException("broken") }
        assertEquals(listOf("PARITY: FAIL"), said)
        assertTrue(logged.single { it.has("failed") }.getString("failed").contains("broken"))
        // A download that ends without the model in place fails as well.
        assertEquals(listOf("PARITY: FAIL"), run {}.second)
    }

    @Test
    fun aModelThatDidNotPassIsDeletedAgain() {
        val store = ModelStore.of(context)
        val model = ModelCatalog.TEXT_DETECTOR
        // Files of the catalog's sizes and stamps, which the host's runtime does not run: no map, so FAIL.
        val (logged, said) = run {
            for (f in model.files) {
                store.target(model, f).apply { parentFile!!.mkdirs() }.let { RandomAccessFile(it, "rw").use { r -> r.setLength(f.bytes) } }
                store.stamped(model, f)
            }
            assertTrue(store.installed(model))
        }
        assertEquals(listOf("PARITY: FAIL"), said)
        assertFalse(store.installed(model))
        assertFalse(store.dir(model).exists())
        assertTrue(logged.last().getBoolean("removed"))
    }
}
