package com.infinity.comicreader.ai

import android.app.Application
import com.infinity.comicreader.net.Metered
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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

    /** Requests received; the nth waits for holds[n], when there is one. */
    private val requests = AtomicInteger()
    private val holds = ArrayList<CountDownLatch>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                holds.getOrNull(requests.getAndIncrement())?.await(10, TimeUnit.SECONDS)
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
        Metered.probe = { Metered.Link(connected = true, notMetered = true, activeMetered = false) }
        Metered.forget()
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

    private fun waitForRequests(n: Int) {
        val end = System.currentTimeMillis() + 10_000
        while (requests.get() < n) {
            assertTrue("timed out waiting for request $n", System.currentTimeMillis() < end)
            Thread.sleep(10)
        }
    }

    private fun waitFor(what: String, condition: (Downloads) -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!condition(ModelDownloads.state.value)) {
            assertTrue("timed out waiting for $what: ${ModelDownloads.state.value}", System.currentTimeMillis() < end)
            Thread.sleep(10)
        }
    }

    @Test
    fun aDownloadGivenNoClientUsesTheStandIn() {
        val asked = AtomicInteger()
        ModelDownloads.client = { OkHttpClient.Builder().addInterceptor { asked.incrementAndGet(); throw IOException("refused") }.build() }
        try {
            val m = model("dl-stand-in")
            ModelDownloads.start(store, m)
            val end = System.currentTimeMillis() + 5_000
            while (ModelDownloads.state.value.states[m.id] !is DownloadState.Failed && System.currentTimeMillis() < end) Thread.sleep(10)
            assertTrue(ModelDownloads.state.value.states[m.id] is DownloadState.Failed)
            assertTrue(asked.get() > 0)
            assertEquals(0, server.requestCount)
            ModelDownloads.cancel(m.id)
        } finally {
            ModelDownloads.client = { ModelDownloader.defaultClient }
        }
    }

    @Test
    fun aDownloadRunsThenInstallsAndLeavesTheState() {
        val m = model("dl-ok")
        val generation = ModelDownloads.state.value.generation
        gate = CountDownLatch(1)
        val job = ModelDownloads.start(store, m)!!
        assertTrue(ModelDownloads.state.value.states[m.id] is DownloadState.Running)
        // Asking again while it runs starts nothing new: it hands back the job on its way.
        assertSame(job, ModelDownloads.start(store, m))
        gate.countDown()
        waitFor("install") { m.id !in it.states && it.generation > generation }
        assertTrue(store.installed(m))
        assertNull("nothing runs for a model in place", ModelDownloads.start(store, m))
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
    fun anOptionalDownloadIsSkippedOnAMeteredConnectionAndAnAskedOneIsNot() {
        Metered.probe = { Metered.Link(connected = true, notMetered = false, activeMetered = true, cellular = true) }
        Metered.forget()
        val m = model("dl-metered")
        ModelDownloads.start(store, m, optional = true)
        assertEquals(DownloadState.Failed(DownloadFailure.METERED), ModelDownloads.state.value.states[m.id])
        assertEquals(0, requests.get())
        // Asked again for each page: the state stays the same object, so nobody hears a change.
        val skipped = ModelDownloads.state.value
        ModelDownloads.start(store, m, optional = true)
        assertTrue(skipped === ModelDownloads.state.value)
        // The app may try again by itself, as it may not after another failure.
        assertTrue(ModelDownloads.mayStartBySelf(ModelDownloads.state.value, m.id))
        assertFalse(ModelDownloads.mayStartBySelf(Downloads(mapOf(m.id to DownloadState.Failed(DownloadFailure.SERVER))), m.id))
        ModelDownloads.start(store, m)
        waitFor("install") { m.id !in it.states && store.installed(m) }
    }

    @Test
    fun theDetectorsDownloadTellsAHoldApartAndEndsOnceInPlace() {
        val m = model("dl-detector")
        val ends = java.util.concurrent.LinkedBlockingQueue<Boolean>()
        val client = ModelDownloads.client()
        Metered.probe = { Metered.Link(connected = true, notMetered = false, activeMetered = true, cellular = true) }
        Metered.forget()
        assertFalse("held back", DetectorModel.download(store, m, optional = true, done = { ends += it }, client = client))
        assertEquals(0, requests.get())
        assertTrue("no end told for a hold", ends.isEmpty())
        Metered.probe = { Metered.Link(connected = true, notMetered = true, activeMetered = false) }
        Metered.forget()
        gate = CountDownLatch(1)
        assertTrue(DetectorModel.download(store, m, optional = true, done = { ends += it }, client = client))
        assertTrue("told only once it ends", ends.isEmpty())
        gate.countDown()
        assertEquals(true, ends.poll(10, TimeUnit.SECONDS))
        assertTrue(store.installed(m))
        // In place: told at once, nothing downloaded.
        val before = requests.get()
        assertTrue(DetectorModel.download(store, m, optional = true, done = { ends += it }, client = client))
        assertEquals(true, ends.poll())
        assertEquals(before, requests.get())
    }

    @Test
    fun theDetectorsFailedDownloadEndsWithoutItsModel() {
        val m = model("dl-detector-missing", missing = true)
        val ends = java.util.concurrent.LinkedBlockingQueue<Boolean>()
        assertTrue(DetectorModel.download(store, m, optional = false, done = { ends += it }, client = ModelDownloads.client()))
        assertEquals(false, ends.poll(10, TimeUnit.SECONDS))
    }

    @Test
    fun anOptionalDownloadRunsUnmetered() {
        val m = model("dl-unmetered")
        ModelDownloads.start(store, m, optional = true)
        waitFor("install") { m.id !in it.states && store.installed(m) }
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

    @Test
    fun aCanceledDownloadEndingLateLeavesItsSuccessorCancelable() {
        val m = model("dl-recancel")
        val first = CountDownLatch(1)
        val second = CountDownLatch(1)
        holds += listOf(first, second)
        ModelDownloads.start(store, m)
        waitForRequests(1)
        ModelDownloads.cancel(m.id)
        ModelDownloads.start(store, m)
        // The first download ends on its canceled answer, then the second asks.
        first.countDown()
        waitForRequests(2)
        assertTrue(ModelDownloads.state.value.states[m.id] is DownloadState.Running)
        ModelDownloads.cancel(m.id)
        second.countDown()
        Thread.sleep(300)
        assertFalse(store.installed(m))
        assertFalse(m.id in ModelDownloads.state.value.states)
    }
}
