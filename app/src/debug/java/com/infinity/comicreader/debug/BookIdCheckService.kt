package com.infinity.comicreader.debug

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.infinity.comicreader.R
import com.infinity.comicreader.data.BookId
import com.infinity.comicreader.data.BookIdBytes
import com.infinity.comicreader.data.BookIds
import com.infinity.comicreader.data.LibXxh3
import com.infinity.comicreader.net.RemoteFile
import com.infinity.comicreader.net.Remotes
import com.infinity.comicreader.util.Formats
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Debug builds only: checks the native XXH3-64 against xxHash's reference values, and
 * times finding Book IDs in a folder: from the index (no read) and by reading and
 * hashing each book, one at a time and [CONCURRENT] at once. Only the shell may start it:
 *
 * `adb shell am start-foreground-service -n <package>/com.infinity.comicreader.debug.BookIdCheckService [--es dir <path>] [--es share <id>]`
 *
 * With `share` (`first` for the first share kept), the first folder holding books on it,
 * named in no log. Results go to the log under "BookIdCheck", ending with "done".
 */
class BookIdCheckService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val dir = intent?.getStringExtra("dir")
        val share = intent?.getStringExtra("share")
        thread(name = "book-id-check") {
            try {
                BookIds.init(this)
                reference()
                val folder = dir ?: DebugBooks.shareId(share)?.let { id -> DebugBooks.firstBook(Remotes.rootOf(id), 3, largest = false)?.substringBeforeLast('/') }
                if (folder != null) time(folder)
            } catch (t: Throwable) {
                log("failed: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                log("done")
                stopSelfResult(startId)
            }
        }
        return START_NOT_STICKY
    }

    /** The native hash of each reference input against xxHash's own value, and the Book ID layout of each. */
    private fun reference() {
        val ramp = ByteArray(65_544) { it.toByte() }
        val cases = listOf(
            Triple("empty", ByteArray(0), "2d06800538d394c2"),
            Triple("abc", "abc".toByteArray(), "78af5f94892f3950"),
            Triple("65544 zero bytes", ByteArray(65_544), "34542675f8ba79ea"),
            Triple("65544 bytes i & 0xFF", ramp, "f3b879e4d702d1f2")
        ) + listOf(
            4 to "60dab036a58211f2", 9 to "e9612598145bb9dc", 17 to "9ef341a99de37328", 129 to "ec7642b431ba3e5a",
            241 to "02e8cd95421c6d02", 1025 to "78c86e91ee939852"
        ).map { (n, v) -> Triple("$n bytes i & 0xFF", ramp.copyOf(n), v) }
        var failed = 0
        for ((name, bytes, expected) in cases) {
            val got = BookId(LibXxh3.hash(bytes, bytes.size)).hex
            if (got != expected) failed++
            log("$name: native $got, reference $expected, ${if (got == expected) "ok" else "MISMATCH"}")
        }
        log("vector ${LibXxh3.vector}; ${cases.size - failed} of ${cases.size} match")
        // One JNI call per book: its cost alone, over a full 65,544-byte layout.
        repeat(200) { LibXxh3.hash(ramp, ramp.size) }
        val t = System.nanoTime()
        repeat(2000) { LibXxh3.hash(ramp, ramp.size) }
        log("hash of a 65544-byte layout: ${"%.1f".format((System.nanoTime() - t) / 2000 / 1000.0)} us")
    }

    /** Times finding the IDs of up to [MAX_BOOKS] books in [folder]. */
    private fun time(folder: String) {
        val remote = Remotes.isRemote(folder)
        var t = System.nanoTime()
        val files: List<File> = (if (remote) Remotes.list(folder) else File(folder).listFiles()?.toList().orEmpty())
            .filter { it.isFile && Formats.isOpenable(it.name) }
            .take(MAX_BOOKS)
        log("${if (remote) "share" else "local"}: ${files.size} books listed in ${ms(t)} ms")
        if (files.isEmpty()) return
        // Each found once, so the index knows them all.
        files.forEach { BookIds.find(it) }

        val hits = files.map { f ->
            t = System.nanoTime()
            val id = BookIds.known(f)
            val took = (System.nanoTime() - t) / 1000.0
            check(id != null) { "not indexed" }
            took
        }
        log("index hit: median ${"%.1f".format(hits.sorted()[hits.size / 2])} us, max ${"%.1f".format(hits.max())} us")

        val reads = files.map { f ->
            t = System.nanoTime()
            val layout = BookIds.bytesOf(f, (f as? RemoteFile)?.stat()?.modified ?: f.lastModified())
            val read = System.nanoTime() - t
            BookIdBytes.idOf(layout, LibXxh3)
            read / 1_000_000.0 to (System.nanoTime() - t) / 1_000_000.0
        }
        val total = reads.map { it.second }.sorted()
        log("read and hash, one at a time: median ${"%.1f".format(total[total.size / 2])} ms, max ${"%.1f".format(total.max())} ms, all ${"%.0f".format(total.sum())} ms")

        val pool = Executors.newFixedThreadPool(CONCURRENT)
        try {
            t = System.nanoTime()
            pool.invokeAll(files.map { f -> Callable { BookIdBytes.idOf(BookIds.bytesOf(f, (f as? RemoteFile)?.stat()?.modified ?: f.lastModified()), LibXxh3) } })
                .forEach { it.get() }
            log("read and hash, $CONCURRENT at once: all ${ms(t)} ms")
        } finally {
            pool.shutdown()
        }
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager?.getNotificationChannel(CHANNEL) == null) {
            manager?.createNotificationChannel(NotificationChannel(CHANNEL, "Book ID check", NotificationManager.IMPORTANCE_LOW))
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_share)
            .setContentTitle("Checking Book IDs")
            .setOngoing(true)
            .build()
    }

    private fun ms(since: Long) = (System.nanoTime() - since) / 1_000_000

    private fun log(message: String) {
        Log.i(TAG, message)
    }

    private companion object {
        const val TAG = "BookIdCheck"
        const val CHANNEL = "book_id_check"
        const val NOTIFICATION_ID = 7303
        const val MAX_BOOKS = 40
        const val CONCURRENT = 4
    }
}
