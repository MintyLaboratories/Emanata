package com.infinity.comicreader.debug

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.infinity.comicreader.R
import com.infinity.comicreader.files.BookUris
import com.infinity.comicreader.net.RemoteCache
import com.infinity.comicreader.net.Remotes
import com.infinity.comicreader.reader.NestedArchives
import com.infinity.comicreader.util.Formats
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Debug builds only: reads a book through [com.infinity.comicreader.files.BookProvider]'s
 * URI as a receiving app would, and checks its name, size and bytes against the book read
 * directly, and that nothing was copied into the network or nested-archive caches meanwhile.
 * It runs in a process of its own, as a receiving app does: a thread reading a proxy
 * descriptor in the process that serves it would wait on itself for good once that process
 * is killed meanwhile. A foreground service, so that process is neither frozen nor dropped
 * during a long read. Only the shell may start it:
 *
 * `adb shell am start-foreground-service -n <package>/com.infinity.comicreader.debug.ShareCheckService --es path <path> [--el limit <bytes>]`
 *
 * or `--es share <id>` (`first` for the first share kept) for the first book found on that
 * share (`--ez largest true`: the largest in the first folder holding books), named in no log. Up to
 * `limit` bytes from the start are compared (all by default), then 16 random ranges.
 * Results go to the log under "ShareCheck", ending with "done".
 */
class ShareCheckService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), TYPE)
        val path = intent?.getStringExtra("path")
        val share = intent?.getStringExtra("share")
        val limit = intent?.getLongExtra("limit", Long.MAX_VALUE) ?: Long.MAX_VALUE
        val largest = intent?.getBooleanExtra("largest", false) ?: false
        thread(name = "share-check") {
            try {
                val id = if (share == FIRST) Remotes.shares().firstOrNull()?.id else share
                val target = path ?: id?.let { firstBook(Remotes.rootOf(it), 3, largest) }
                if (target == null) log("no book found") else check(this, target, limit)
            } catch (t: Throwable) {
                log("failed: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                log("done")
                stopSelfResult(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager?.getNotificationChannel(CHANNEL) == null) {
            manager?.createNotificationChannel(NotificationChannel(CHANNEL, "Share check", NotificationManager.IMPORTANCE_LOW))
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_share)
            .setContentTitle("Checking a shared book")
            .setOngoing(true)
            .build()
    }

    private fun check(context: Context, path: String, limit: Long) {
        val uri = BookUris.of(context, path)
        val resolver = context.contentResolver
        val before = cacheFiles(context.cacheDir)
        val type = resolver.getType(uri)
        var t = System.nanoTime()
        val (name, size) = resolver.query(uri, null, null, null, null)!!.use {
            it.moveToFirst()
            it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) to it.getLong(it.getColumnIndexOrThrow(OpenableColumns.SIZE))
        }
        log("query ${ms(t)} ms: size $size, type $type, name matches ${name == path.substringAfterLast('/')}")
        val direct = if (Remotes.isRemote(path)) Remotes.open(path).length else File(path).length()
        log("size matches ${size == direct}")

        t = System.nanoTime()
        val pfd = resolver.openFileDescriptor(uri, "r")!!
        log("open ${ms(t)} ms, descriptor size ${pfd.statSize}")
        pfd.use {
            val stream = FileInputStream(it.fileDescriptor)
            val wanted = minOf(limit, size)
            t = System.nanoTime()
            val viaProvider = digest(stream, wanted)
            val took = ms(t)
            log("provider read ${wanted / 1024} KB in $took ms (${mbps(wanted, took)} MB/s)")
            t = System.nanoTime()
            val viaDirect = directDigest(path, 0, wanted)
            log("direct read in ${ms(t)} ms; bytes match ${viaProvider.contentEquals(viaDirect)}")

            val channel = stream.channel
            val random = Random(size)
            var matched = 0
            val buffer = java.nio.ByteBuffer.allocate(256 * 1024)
            repeat(16) {
                val at = if (size > buffer.capacity()) random.nextLong(size - buffer.capacity()) else 0L
                buffer.clear()
                channel.position(at)
                var n = 0
                while (buffer.hasRemaining()) {
                    val r = channel.read(buffer)
                    if (r < 0) break
                    n += r
                }
                val got = MessageDigest.getInstance("SHA-256").digest(buffer.array().copyOf(n))
                if (got.contentEquals(directDigest(path, at, n.toLong()))) matched++
            }
            log("random ranges matching: $matched / 16")
        }
        // Only what was added or grew counts: the app's cache trimming may remove files meanwhile.
        val added = cacheFiles(context.cacheDir).filter { (file, length) -> length > (before[file] ?: -1L) }
        log("cache files added or grown: ${added.size}, ${added.values.sum() / 1024} KB")
    }

    private fun digest(input: InputStream, count: Long): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 20)
        var left = count
        while (left > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n < 0) break
            md.update(buffer, 0, n)
            left -= n
        }
        return md.digest()
    }

    private fun directDigest(path: String, at: Long, count: Long): ByteArray {
        if (!Remotes.isRemote(path)) {
            return FileInputStream(path).use { s ->
                s.channel.position(at)
                digest(s, count)
            }
        }
        val md = MessageDigest.getInstance("SHA-256")
        Remotes.open(path).use { input ->
            val buffer = ByteArray(4 shl 20)
            var pos = at
            val end = at + count
            while (pos < end) {
                val n = input.read(pos, buffer, 0, minOf(buffer.size.toLong(), end - pos).toInt())
                if (n < 0) break
                md.update(buffer, 0, n)
                pos += n
            }
        }
        return md.digest()
    }

    /**
     * The first book found in [dir], breadth first, at most [depth] folders down (with
     * [largest], the largest in the first folder holding any); null when none.
     */
    private fun firstBook(dir: String, depth: Int, largest: Boolean): String? {
        var level = listOf(dir)
        repeat(depth + 1) {
            val next = ArrayList<String>()
            for (d in level) {
                val entries = runCatching { Remotes.list(d) }.getOrNull() ?: continue
                val books = entries.filter { !it.isDirectory && Formats.isArchiveName(it.name) }
                (if (largest) books.maxByOrNull { it.length() } else books.firstOrNull())?.let { return it.absolutePath }
                entries.filter { it.isDirectory && !it.name.startsWith(".") }.take(4).mapTo(next) { it.absolutePath }
            }
            level = next
        }
        return null
    }

    /** The files of the network and nested-archive caches in [cacheDir], with their lengths. */
    private fun cacheFiles(cacheDir: File): Map<File, Long> =
        listOf(RemoteCache.dirIn(cacheDir), RemoteCache.pagesIn(cacheDir), NestedArchives.dirIn(cacheDir))
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile }.toList() }
            .associateWith { it.length() }

    private fun ms(start: Long) = (System.nanoTime() - start) / 1_000_000

    private fun mbps(bytes: Long, ms: Long) = if (ms <= 0) "-" else "%.1f".format(bytes / 1e6 / (ms / 1e3))

    private fun log(text: String) = Log.i(TAG, text)

    private companion object {
        const val TAG = "ShareCheck"

        /** The share id that stands for the first share kept. */
        const val FIRST = "first"

        const val CHANNEL = "share-check"
        const val NOTIFICATION_ID = 0x5c
        val TYPE = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
    }
}
