package com.infinity.comicreader.files

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.SystemClock
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import com.infinity.comicreader.net.Remotes
import java.io.File
import java.io.FileNotFoundException

/**
 * Serves the books the user shares to the apps they share them with, in place, at the
 * URIs [BookUris] issues. Not exported: an app reads only the URIs it was granted, read-only.
 * A file on the device opens as itself. A file on a share opens as a proxy descriptor
 * ([StorageManager.openProxyFileDescriptor]) that reads it on demand as the app reads
 * ([ShareReads]), on a thread of its own, so it is never downloaded or copied first and
 * the network cache plays no part; while the app holds it open, the file stays open on
 * the share, which keeps the share's connection, and its key is kept ([openKeys]). Each
 * request and read marks the files as in use ([lastUsed]), for [SharingService].
 */
class BookProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    private fun pathOf(uri: Uri): String? = context?.let { BookUris.pathOf(it, uri) }

    override fun getType(uri: Uri): String? = pathOf(uri)?.let { BookUris.mimeOf(it) }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val path = pathOf(uri) ?: return null
        touch()
        val columns = projection?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }?.toTypedArray() ?: COLUMNS
        val size = runCatching { sizeOf(path) }.getOrNull()
        val row = columns.map { if (it == OpenableColumns.DISPLAY_NAME) File(path).name else size }
        return MatrixCursor(columns, 1).apply { addRow(row) }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Read only")
        val path = pathOf(uri) ?: throw FileNotFoundException("Not a book")
        touch()
        if (!Remotes.isRemote(path)) return ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
        val size = sizeOf(path) ?: throw FileNotFoundException("Gone")
        val storage = context?.getSystemService(StorageManager::class.java) ?: throw FileNotFoundException("No storage service")
        val thread = HandlerThread("share-read").apply { start() }
        // A size taken from an old listing that no longer holds is dropped, so opening it again asks the share.
        val reads = ShareReads(size, onChanged = { Remotes.forgetStat(path) }) { Remotes.open(path) }
        val key = context?.let { BookUris.keyOf(it, uri) }
        hold(key)
        return try {
            storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, Proxy(reads, thread, key), Handler(thread.looper))
        } catch (e: Exception) {
            reads.close()
            thread.quitSafely()
            letGo(key)
            throw FileNotFoundException(e.message)
        }
    }

    /**
     * The size of the file at [path]; for a share's file as its listing found it where it
     * was listed, so opening asks the network nothing; null when nothing is there.
     */
    private fun sizeOf(path: String): Long? {
        if (!Remotes.isRemote(path)) return File(path).takeIf { it.isFile }?.length()
        val known = Remotes.cachedStat(path)?.takeIf { !it.isDirectory && it.size >= 0 }
        if (known != null) return known.size
        val stat = Remotes.stat(path)?.takeIf { !it.isDirectory } ?: return null
        return if (stat.size >= 0) stat.size else Remotes.open(path).use { it.length }
    }

    /** Hands an app's reads of a descriptor to [reads], on [thread], which goes with the descriptor, as does the hold on [key]. */
    private class Proxy(private val reads: ShareReads, private val thread: HandlerThread, private val key: String?) : ProxyFileDescriptorCallback() {
        override fun onGetSize(): Long = reads.size

        override fun onRead(offset: Long, size: Int, data: ByteArray): Int = try {
            touch()
            reads.read(offset, size, data)
        } catch (e: Exception) {
            throw ErrnoException("onRead", OsConstants.EIO)
        }

        override fun onRelease() {
            touch()
            reads.close()
            thread.quitSafely()
            letGo(key)
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read only")

    companion object {
        private val COLUMNS = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)

        /** Descriptors open now, by key. */
        private val open = HashMap<String, Int>()

        /**
         * When a shared file was last asked for, read or let go, by [SystemClock.elapsedRealtime];
         * 0 before any was.
         */
        @Volatile
        var lastUsed = 0L
            private set

        /** The keys of the files on shares open now: kept while they are, however many shares came since. */
        fun openKeys(): Set<String> = synchronized(open) { open.keys.toSet() }

        /** Marks the shared files as in use now. */
        fun touch() {
            lastUsed = SystemClock.elapsedRealtime()
        }

        private fun hold(key: String?) {
            key ?: return
            synchronized(open) { open[key] = (open[key] ?: 0) + 1 }
        }

        private fun letGo(key: String?) {
            key ?: return
            synchronized(open) {
                val left = (open[key] ?: 0) - 1
                if (left > 0) open[key] = left else open -= key
            }
        }
    }
}
