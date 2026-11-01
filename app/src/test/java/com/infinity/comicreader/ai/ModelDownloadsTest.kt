package com.infinity.comicreader.ai

import android.app.Application
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** App-wide downloads: state while running, installed when done, a failure kept to show, deletes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ModelDownloadsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var store: ModelStore
    private val files = HashMap<String, ByteArray>()

    /** Holds every answer until counted down. */
    private var gate = CountDownLatch(0)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                gate.await(10, TimeUnit.SECONDS)
                val data = files[request.path] ?: return MockResponse().setResponseCode(404)
                return MockResponse().setBody(Buffer().write(data))
            }
        }
        server.start()
        store = ModelStore(tmp.newFolder("models"))
    }

    @After
    fun tearDown() {
        gate.countDown()
        server.shutdown()
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun model(id: String, missing: Boolean = false): CatalogModel {
        fun serve(name: String, n: Int): ModelFile {
            val data = Random(name.hashCode()).nextBytes(n)
            if (!missing) files["/$name"] = data
            return ModelFile(name, server.url("/$name").toString(), n.toLong(), sha256(data))
        }
        return CatalogModel(id, id, AiStep.UPSCALE, 2, ModelSource.OPENCOMIC, serve("$id.param", 100), serve("$id.bin", 50_000))
    }

    private fun waitFor(what: String, condition: (Downloads) -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!condition(ModelDownloads.state.value)) {
            assertTrue("timed out waiting for $what: ${ModelDownloads.state.value}", System.currentTimeMillis() < end)
            Thread.sleep(10)
        }
    }

    @Test
    fun aDownloadRunsThenInstallsAndLeavesTheState() {
        val m = model("dl-ok")
        val generation = ModelDownloads.state.value.generation
        gate = CountDownLatch(1)
        ModelDownloads.start(store, m)
        assertTrue(ModelDownloads.state.value.states[m.id] is DownloadState.Running)
        // Asking again while it runs starts nothing new.
        ModelDownloads.start(store, m)
        gate.countDown()
        waitFor("install") { m.id !in it.states && it.generation > generation }
        assertTrue(store.installed(m))
    }

    @Test
    fun aFailedDownloadStaysShownUntilRetried() {
        val m = model("dl-missing", missing = true)
        ModelDownloads.start(store, m)
        waitFor("failure") { it.states[m.id] is DownloadState.Failed }
        assertEquals(DownloadFailure.SERVER, (ModelDownloads.state.value.states[m.id] as DownloadState.Failed).failure)
        assertFalse(store.installed(m))
    }

    @Test
    fun deleteStopsAndRemovesAModel() {
        val m = model("dl-delete")
        ModelDownloads.start(store, m)
        waitFor("install") { m.id !in it.states && store.installed(m) }
        val generation = ModelDownloads.state.value.generation
        ModelDownloads.delete(store, m)
        waitFor("delete") { it.generation > generation }
        assertFalse(store.installed(m))
        assertFalse(store.dir(m).exists())
    }

    @Test
    fun cancelKeepsWhatArrivedAndClearsTheState() {
        val m = model("dl-cancel")
        gate = CountDownLatch(1)
        ModelDownloads.start(store, m)
        ModelDownloads.cancel(m.id)
        assertFalse(m.id in ModelDownloads.state.value.states)
        gate.countDown()
        Thread.sleep(200)
        assertFalse(m.id in ModelDownloads.state.value.states)
    }
}
