package com.infinity.comicreader.ai

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/** Model downloads against a server on this machine: whole, resumed, restarted, refused and wrong files. */
class ModelDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var store: ModelStore

    /** What the server holds, by path. */
    private val files = HashMap<String, ByteArray>()

    /** Ranges honored; off, the server sends whole files to every request. */
    private var ranges = true

    /** The connection drops halfway through each `.bin` answer's body. */
    private var cut = false

    private val requests = CopyOnWriteArrayList<RecordedRequest>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val data = files[request.path] ?: return MockResponse().setResponseCode(404)
                val range = request.getHeader("Range")?.removePrefix("bytes=")?.substringBefore('-')?.toLongOrNull()
                val from = if (ranges && range != null) range.toInt() else 0
                if (from > data.size) return MockResponse().setResponseCode(416)
                val response = MockResponse().setBody(Buffer().write(data.copyOfRange(from, data.size)))
                if (cut && request.path!!.endsWith(".bin")) response.socketPolicy = SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY
                return if (from > 0) {
                    response.setResponseCode(206).setHeader("Content-Range", "bytes $from-${data.size - 1}/${data.size}")
                } else {
                    response
                }
            }
        }
        server.start()
        store = ModelStore(tmp.newFolder("models"))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun serve(path: String, data: ByteArray): ModelFile {
        files[path] = data
        return ModelFile(path.substringAfterLast('/'), server.url(path).toString(), data.size.toLong(), sha256(data))
    }

    private fun model(paramBytes: Int = 300, binBytes: Int = 200_000, id: String = "test-model"): CatalogModel {
        val r = Random(paramBytes + binBytes)
        return CatalogModel(
            id, "Test model", AiStep.UPSCALE, 2, ModelSource.OPENCOMIC,
            serve("/m/$id.param", r.nextBytes(paramBytes)), serve("/m/$id.bin", r.nextBytes(binBytes))
        )
    }

    private fun installedBytes(m: CatalogModel, f: ModelFile) = File(store.dir(m), f.name).readBytes()

    @Test
    fun downloadsBothFilesAndInstallsThem() {
        val m = model()
        assertFalse(store.installed(m))
        assertNull(store.files(m))
        val seen = ArrayList<Long>()
        ModelDownloader(store).download(m) { done, total -> assertEquals(m.bytes, total); seen += done }
        assertTrue(store.installed(m))
        assertArrayEquals(files["/m/test-model.bin"], installedBytes(m, m.bin))
        assertArrayEquals(files["/m/test-model.param"], installedBytes(m, m.param))
        assertEquals(m.bytes, seen.last())
        assertEquals("progress only grows", seen.sorted(), seen)
        assertEquals(File(store.dir(m), "test-model.param"), store.files(m)!!.param)
        assertTrue(store.dir(m).list()!!.none { it.endsWith(ModelStore.PART) })
        assertEquals(m.bytes, store.bytesHeld(m))
    }

    @Test
    fun aFileWhoseChecksumTheCatalogChangedDownloadsAgain() {
        val m = model()
        ModelDownloader(store).download(m)
        val data = Random(7).nextBytes(m.bin.bytes.toInt())
        val updated = m.copy(bin = serve("/m/test-model.bin", data))
        assertTrue(store.has(updated, updated.param))
        assertFalse(store.has(updated, updated.bin))
        assertFalse(store.installed(updated))
        assertEquals(m.param.bytes, store.bytesHeld(updated))
        requests.clear()
        ModelDownloader(store).download(updated)
        assertTrue(store.installed(updated))
        assertArrayEquals(data, installedBytes(updated, updated.bin))
        assertEquals(listOf("/m/test-model.bin"), requests.map { it.path })
    }

    @Test
    fun anInstalledModelIsNotFetchedAgain() {
        val m = model()
        ModelDownloader(store).download(m)
        val before = requests.size
        ModelDownloader(store).download(m)
        assertEquals(before, requests.size)
    }

    @Test
    fun aBrokenDownloadKeepsItsPartAndResumesWithARange() {
        val m = model()
        cut = true
        val e = assertThrows(DownloadException::class.java) { ModelDownloader(store).download(m) }
        assertEquals(DownloadFailure.NETWORK, e.failure)
        assertFalse(store.installed(m))
        val part = store.part(m, m.bin)
        assertTrue("part kept", part.length() in 1..m.bin.bytes - 1)
        val held = part.length()
        cut = false
        requests.clear()
        ModelDownloader(store).download(m)
        assertTrue(store.installed(m))
        assertArrayEquals(files["/m/test-model.bin"], installedBytes(m, m.bin))
        assertEquals("bytes=$held-", requests.single { it.path == "/m/test-model.bin" }.getHeader("Range"))
    }

    @Test
    fun aServerWithoutRangesSendsTheWholeFileAgain() {
        val m = model()
        cut = true
        assertThrows(DownloadException::class.java) { ModelDownloader(store).download(m) }
        cut = false
        ranges = false
        ModelDownloader(store).download(m)
        assertTrue(store.installed(m))
        assertArrayEquals(files["/m/test-model.bin"], installedBytes(m, m.bin))
    }

    @Test
    fun aCanceledDownloadKeepsWhatArrived() {
        val m = model(binBytes = 1_000_000)
        var chunks = 0
        assertThrows(CancellationException::class.java) {
            ModelDownloader(store).download(m, canceled = { chunks > 3 }) { _, _ -> chunks++ }
        }
        assertFalse(store.installed(m))
        assertTrue(store.bytesHeld(m) > 0)
        ModelDownloader(store).download(m)
        assertTrue(store.installed(m))
    }

    @Test
    fun aFileUnlikeTheCatalogIsThrownAway() {
        val good = model()
        val m = good.copy(bin = good.bin.copy(sha256 = "0".repeat(64)))
        val e = assertThrows(DownloadException::class.java) { ModelDownloader(store).download(m) }
        assertEquals(DownloadFailure.MISMATCH, e.failure)
        assertFalse(store.installed(m))
        assertFalse(store.part(m, m.bin).exists())
        assertFalse(File(store.dir(m), m.bin.name).exists())
    }

    @Test
    fun aFileLargerThanTheCatalogSaysIsThrownAway() {
        val good = model()
        val m = good.copy(bin = good.bin.copy(bytes = good.bin.bytes - 10))
        val e = assertThrows(DownloadException::class.java) { ModelDownloader(store).download(m) }
        assertEquals(DownloadFailure.MISMATCH, e.failure)
        assertFalse(store.part(m, m.bin).exists())
    }

    @Test
    fun aMissingFileIsAServerFailure() {
        val good = model()
        val m = good.copy(bin = good.bin.copy(url = server.url("/gone.bin").toString()))
        val e = assertThrows(DownloadException::class.java) { ModelDownloader(store).download(m) }
        assertEquals(DownloadFailure.SERVER, e.failure)
    }

    @Test
    fun noServerIsANetworkFailure() {
        val m = model()
        server.shutdown()
        val e = assertThrows(DownloadException::class.java) { ModelDownloader(store).download(m) }
        assertEquals(DownloadFailure.NETWORK, e.failure)
    }

    @Test
    fun aPartLongerThanTheFileStartsOver() {
        val m = model()
        store.dir(m).mkdirs()
        store.part(m, m.bin).writeBytes(ByteArray(m.bin.bytes.toInt() + 5))
        ModelDownloader(store).download(m)
        assertTrue(store.installed(m))
    }

    @Test
    fun deleteAndPruneRemoveModelsFolders() {
        val m = model()
        val other = model(id = "retired-model")
        ModelDownloader(store).download(m)
        ModelDownloader(store).download(other)
        store.prune(listOf(m))
        assertTrue(store.installed(m))
        assertFalse(store.dir(other).exists())
        assertTrue(store.delete(m))
        assertFalse(store.installed(m))
        assertEquals(0, store.bytesUsed())
        assertEquals(0, store.bytesHeld(m))
    }

    /** A set of files that is not a catalog model. */
    private data class Set(override val id: String, override val files: List<ModelFile>) : FileSet

    private fun set(id: String, vararg sizes: Int): Set {
        val r = Random(id.hashCode())
        return Set(id, sizes.mapIndexed { i, n -> serve("/s/$id/file$i", r.nextBytes(n)) })
    }

    @Test
    fun aSetOfAnyFilesDownloadsInOrderAndInstalls() {
        val s = set("three-files", 10, 70_000, 300)
        assertEquals(70_310L, s.bytes)
        assertFalse(store.installed(s))
        ModelDownloader(store).download(s)
        assertTrue(store.installed(s))
        assertEquals(listOf("/s/three-files/file0", "/s/three-files/file1", "/s/three-files/file2"), requests.map { it.path })
        for ((i, f) in s.files.withIndex()) assertArrayEquals(files["/s/three-files/file$i"], File(store.dir(s), f.name).readBytes())
        assertEquals(s.bytes, store.bytesHeld(s))
    }

    @Test
    fun pruneKeepsSetsInTheCatalogItIsGiven() {
        val m = model()
        val s = set("kept-set", 100, 200)
        val gone = set("gone-set", 100)
        for (x in listOf(m, s, gone)) ModelDownloader(store).download(x)
        store.prune(listOf(m, s))
        assertTrue(store.installed(m))
        assertTrue(store.installed(s))
        assertFalse(store.dir(gone).exists())
        assertTrue(store.delete(s))
        assertFalse(store.installed(s))
    }

    @Test
    fun contentRangeStartIsRead() {
        assertEquals(100L, ModelDownloader.contentRangeStart("bytes 100-199/200"))
        assertEquals(0L, ModelDownloader.contentRangeStart("bytes 0-9/10"))
        assertNull(ModelDownloader.contentRangeStart("bytes */200"))
        assertNull(ModelDownloader.contentRangeStart(null))
    }

    @Test
    fun sha256MatchesTheStandard() {
        val f = tmp.newFile("abc")
        f.writeText("abc")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ModelDownloader.sha256(f))
    }
}
