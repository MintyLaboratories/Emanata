package com.infinity.comicreader.debug

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.infinity.comicreader.R
import com.infinity.comicreader.data.BookIds
import com.infinity.comicreader.data.EntryType
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.SimilarFinder
import com.infinity.comicreader.data.SimilarStore
import com.infinity.comicreader.net.RemoteFile
import com.infinity.comicreader.net.Remotes
import com.infinity.comicreader.reader.BookPrints
import com.infinity.comicreader.reader.Decoders
import com.infinity.comicreader.reader.PreviewStore
import com.infinity.comicreader.reader.Previews
import com.infinity.comicreader.util.Formats
import java.io.File
import kotlin.concurrent.thread

/**
 * Debug builds only: times the similar-books search ([SimilarFinder]) over the books in a
 * folder, for each worker count asked, rounds interleaved so drift in temperature falls on
 * all alike. Only the shell may start it:
 *
 * `adb shell am start-foreground-service -n <package>/com.infinity.comicreader.debug.SimilarCheckService --es dir <path> [--es workers 1,2,3] [--es jxl coder,regions] [--ei rounds 3] [--es start cold|previews]`
 *
 * `dir` may be a share folder (`/net/<id>/…`): its books are read in place, read only, by
 * [SimilarFinder.SHARE_WORKERS] threads, as Find duplicate files reads them.
 *
 * `jxl` names how JPEG XL pages shrink for their hashes ([Decoders.jxlMeasuredByRegions]), each
 * timed in turn (the app's own choice when not given; put back after).
 * Each run reads every book anew into a store of its own (the app's is left alone); `cold`
 * (the default) also deletes the page previews first, `previews` keeps them. Each run logs
 * its time, the groups found, the thermal status before and after, and the battery level;
 * the end logs how many stored previews are smaller than a preview should be. Results go to
 * the log under "SimilarCheck", ending with "done".
 */
class SimilarCheckService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val dir = intent?.getStringExtra("dir")
        val workers = intent?.getStringExtra("workers")?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.ifEmpty { null } ?: listOf(SimilarFinder.WORKERS)
        val rounds = intent?.getIntExtra("rounds", 1)?.coerceAtLeast(1) ?: 1
        val cold = intent?.getStringExtra("start") != "previews"
        val jxl = intent?.getStringExtra("jxl")?.split(',')?.map { it.trim() }?.filter { it == "coder" || it == "regions" }?.ifEmpty { null }
        thread(name = "similar-check") {
            try {
                BookIds.init(this)
                if (dir != null) time(Remotes.file(dir), workers, jxl, rounds, cold)
            } catch (t: Throwable) {
                log("failed: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                log("done")
                stopSelfResult(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun time(dir: File, workers: List<Int>, jxl: List<String>?, rounds: Int, cold: Boolean) {
        val books = dir.walkTopDown().filter { it.isFile && Formats.typeOf(it.name).let { t -> t == EntryType.ARCHIVE || t == EntryType.PDF } }
            .sortedBy { it.path }.toList()
        log("${books.size} books, workers $workers, jxl ${jxl ?: "as set"}, $rounds rounds, ${if (cold) "cold" else "previews kept"}")
        val charset = Prefs(this).charset
        val previews = PreviewStore.dirIn(cacheDir)
        val storeFile = File(cacheDir, "similar-check.txt")
        val setting = Decoders.jxlMeasuredByRegions
        try {
            repeat(rounds) { round ->
                for (mode in jxl ?: listOf(null)) for (w in workers) {
                    if (mode != null) Decoders.jxlMeasuredByRegions = mode == "regions"
                    search(books, charset, previews, storeFile, cold, round, w, mode)
                }
            }
        } finally {
            Decoders.jxlMeasuredByRegions = setting
        }
        storeFile.delete()
        Thread.sleep(1000)
        val sizes = previews.listFiles()?.filter { it.isFile }.orEmpty().map { f ->
            BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(f.path, it) }.let { maxOf(it.outWidth, it.outHeight) }
        }
        log("previews stored: ${sizes.size}, smaller than ${Previews.BOX}: ${sizes.count { it < Previews.BOX }}")
    }

    /** One timed search of [books] by [w] workers, JPEG XL hashed by [mode] (null: as set). */
    private fun search(books: List<File>, charset: String, previews: File, storeFile: File, cold: Boolean, round: Int, w: Int, mode: String?) {
        if (cold) PreviewStore.clear(previews)
        storeFile.delete()
        val store = SimilarStore(storeFile)
        val finder = SimilarFinder(
            store, BookIds::find, { f, canceled, pace -> BookPrints.read(f, charset, cacheDir, canceled, pace) }, workers = w, remote = { it is RemoteFile }
        )
        val before = thermal()
        val t = System.nanoTime()
        val found = finder.find(books)
        val ms = (System.nanoTime() - t) / 1_000_000
        log(
            "round ${round + 1} workers $w${mode?.let { " jxl $it" }.orEmpty()}: $ms ms, ${found.groups.size} groups of ${found.groups.sumOf { it.size }}, " +
                "${found.compared}/${found.total} compared, thermal $before -> ${thermal()}, battery ${battery()}%"
        )
    }

    private fun thermal(): Int {
        val pm = getSystemService(PowerManager::class.java) ?: return -1
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) pm.currentThermalStatus else -1
    }

    private fun battery(): Int = getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager?.getNotificationChannel(CHANNEL) == null) {
            manager?.createNotificationChannel(NotificationChannel(CHANNEL, "Similar books check", NotificationManager.IMPORTANCE_LOW))
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_share)
            .setContentTitle("Timing the similar-books search")
            .setOngoing(true)
            .build()
    }

    private fun log(message: String) {
        Log.i(TAG, message)
    }

    private companion object {
        const val TAG = "SimilarCheck"
        const val CHANNEL = "similar_check"
        const val NOTIFICATION_ID = 7309
    }
}
