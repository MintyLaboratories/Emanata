package com.infinity.comicreader.reader

import com.infinity.comicreader.net.RemoteChannel
import com.infinity.comicreader.net.RemoteInput
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/** A zip on a share, read in place by several threads at once. */
class ChannelZipReaderTest {

    /** [bytes] as a file on a slow share: each read waits a little and answers at most [most] bytes. */
    private class SlowShare(private val bytes: ByteArray, private val most: Int) : RemoteInput {
        override val length: Long = bytes.size.toLong()

        override fun read(position: Long, buffer: ByteArray, offset: Int, count: Int): Int {
            val until = System.nanoTime() + 30_000
            while (System.nanoTime() < until) Thread.onSpinWait()
            if (position >= bytes.size) return -1
            val n = minOf(count, most, (bytes.size - position).toInt())
            System.arraycopy(bytes, position.toInt(), buffer, offset, n)
            return n
        }

        override fun close() {}
    }

    /** Entries named at varied lengths, deflated and stored, of compressible but distinct bytes. */
    private fun book(count: Int): Pair<ByteArray, Map<String, ByteArray>> {
        val random = Random(7)
        val contents = LinkedHashMap<String, ByteArray>()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for (i in 0 until count) {
                val name = "p".repeat(1 + i % 13) + "/page $i.jpg"
                val data = ByteArray(2_000 + random.nextInt(6_000)) { (random.nextInt(16) + i).toByte() }
                val entry = ZipEntry(name)
                if (i % 5 == 0) {
                    entry.method = ZipEntry.STORED
                    entry.size = data.size.toLong()
                    entry.crc = CRC32().apply { update(data) }.value
                }
                zip.putNextEntry(entry)
                zip.write(data)
                zip.closeEntry()
                contents[name] = data
            }
        }
        return out.toByteArray() to contents
    }

    @Test
    fun entriesReadAtOnceByManyThreadsComeWhole() {
        val (zip, contents) = book(48)
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        try {
            repeat(4) { round ->
                val channel = RemoteChannel(SlowShare(zip, most = 700), minBlock = 512, maxBlock = 2048, maxCached = 4096, readAhead = false)
                ChannelZipReader(channel, "UTF-8").use { reader ->
                    assertEquals(contents.size, reader.entries.size)
                    val start = CountDownLatch(1)
                    val jobs = (0 until threads).map { t ->
                        pool.submit(Callable {
                            start.await()
                            reader.entries.shuffled(Random(round * threads + t)).map { entry ->
                                entry.path to runCatching { reader.read(entry) { it.readBytes() } }
                            }
                        })
                    }
                    start.countDown()
                    for (job in jobs) {
                        for ((path, got) in job.get(60, TimeUnit.SECONDS)) {
                            assertArrayEquals(path, contents.getValue(path), got.getOrElse { throw AssertionError("$path: $it", it) })
                        }
                    }
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }
}
