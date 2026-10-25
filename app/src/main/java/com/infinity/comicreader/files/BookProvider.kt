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
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import com.infinity.comicreader.net.Remotes
import java.io.File
import java.io.FileNotFoundException

/**
 * Serves the books the user shares to the apps they share them with, in place, at the
 * URIs [BookUris] makes. Not exported: an app reads only the URIs it was granted, read-only.
 * A file on the device opens as itself. A file on a share opens as a proxy descriptor
 * ([StorageManager.openProxyFileDescriptor]) that reads it on demand as the app reads
 * ([ShareReads]), on a thread of its own, so it is never downloaded or copied first and
 * the network cache plays no part; while the app holds it open, the file stays open on
 * the share, which keeps the share's connection.
 */
class BookProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = BookUris.pathOf(uri)?.let { BookUris.mimeOf(it) }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val path = BookUris.pathOf(uri) ?: return null
        val columns = projection?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }?.toTypedArray() ?: COLUMNS
        val size = runCatching { sizeOf(path) }.getOrNull()
        val row = columns.map { if (it == OpenableColumns.DISPLAY_NAME) File(path).name else size }
        return MatrixCursor(columns, 1).apply { addRow(row) }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Read only")
        val path = BookUris.pathOf(uri) ?: throw FileNotFoundException("Not a book")
        if (!Remotes.isRemote(path)) return ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
        val size = sizeOf(path) ?: throw FileNotFoundException("Gone")
        val storage = context?.getSystemService(StorageManager::class.java) ?: throw FileNotFoundException("No storage service")
        val thread = HandlerThread("share-read").apply { start() }
        // A size taken from an old listing that no longer holds is dropped, so opening it again asks the share.
        val reads = ShareReads(size, onChanged = { Remotes.forgetStat(path) }) { Remotes.open(path) }
        return try {
            storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, Proxy(reads, thread), Handler(thread.looper))
        } catch (e: Exception) {
            reads.close()
            thread.quitSafely()
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

    /** Hands an app's reads of a descriptor to [reads], on [thread], which goes with the descriptor. */
    private class Proxy(private val reads: ShareReads, private val thread: HandlerThread) : ProxyFileDescriptorCallback() {
        override fun onGetSize(): Long = reads.size

        override fun onRead(offset: Long, size: Int, data: ByteArray): Int = try {
            reads.read(offset, size, data)
        } catch (e: Exception) {
            throw ErrnoException("onRead", OsConstants.EIO)
        }

        override fun onRelease() {
            reads.close()
            thread.quitSafely()
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read only")

    private companion object {
        val COLUMNS = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
    }
}
