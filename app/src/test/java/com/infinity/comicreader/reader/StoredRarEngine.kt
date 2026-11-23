package com.infinity.comicreader.reader

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32

/**
 * Stands in for libarchive on test JVMs, where it cannot load (registered as a service under
 * META-INF/services): reads RAR 4 and RAR 5 archives whose entries are stored, as [Fixtures]
 * writes them, the way libarchive's RAR readers do as far as [RarReader] sees it:
 * - the source is read in blocks up to its chunk and data is skipped, through the same callbacks;
 * - a header's position is where the walk stood when [next] began (0 for the first);
 * - header CRCs are checked by [next], each entry's data CRC once its last byte is read;
 * - an encrypted entry is flagged, RAR 5 header encryption fails, a RAR 5 file whose dictionary
 *   is over 64 MiB fails, and a solid archive is unpacked (read through) to skip an entry, except
 *   a solid RAR 4 opened for listing: its data is jumped over, and reading an entry with the
 *   RAR 4 solid flag (0x0010) after that fails ("RAR solid entry needs the entries before it").
 * Unlike libarchive, names come as stored: RAR 4's "\" stays.
 */
internal class StoredRarEngine : ArchiveEngine {
    private val handles = ConcurrentHashMap<Long, Walk>()
    private val ids = AtomicLong(1)

    override fun open(source: LibArchive.Source, listing: Boolean): Long {
        val walk = Walk(source, listing)
        walk.signature()
        return ids.getAndIncrement().also { handles[it] = walk }
    }

    override fun next(handle: Long, meta: LongArray): ByteArray? = walk(handle).next(meta)

    override fun read(handle: Long, dst: ByteArray, off: Int, len: Int): Int = walk(handle).read(dst, off, len)

    override fun close(handle: Long) {
        handles.remove(handle)
    }

    private fun walk(handle: Long) = handles[handle] ?: throw IllegalStateException("No handle $handle")

    private class Walk(private val source: LibArchive.Source, private val listing: Boolean) {
        private val block = ByteBuffer.allocateDirect(LibArchive.BLOCK_BYTES)
        private var buffered = ByteArray(0)
        private var at = 0

        /** Bytes consumed from the source's start. */
        private var position = 0L
        private var version = 0
        private var solid = false
        private var started = false
        private var ended = false

        /** A solid RAR 4 entry's data was jumped over: the solid entries after it cannot be read. */
        private var solidBroken = false

        /** The current entry carries the RAR 4 solid flag and comes after a jumped-over one. */
        private var needsEarlier = false

        /** Of the current entry: data left to read, whether it can be read, its CRC so far and the one stored. */
        private var left = 0L
        private var stored = false
        private var crc = CRC32()
        private var expectedCrc = -1L

        private fun available() = buffered.size - at

        /** At least [n] bytes buffered; false at the source's end before that. */
        private fun fill(n: Int): Boolean {
            while (available() < n) {
                val got = source.read(block)
                if (got < 0) return false
                if (got == 0) continue
                val more = ByteArray(available() + got)
                System.arraycopy(buffered, at, more, 0, available())
                block.position(0)
                block.get(more, available(), got)
                buffered = more
                at = 0
            }
            return true
        }

        private fun take(n: Int): ByteArray {
            if (!fill(n)) throw IOException("Truncated RAR header")
            return buffered.copyOfRange(at, at + n).also { at += n; position += n }
        }

        private fun peek(n: Int): ByteArray? = if (fill(n)) buffered.copyOfRange(at, at + n) else null

        /** Passes [n] bytes, those buffered first, then by skipping; a solid RAR 5 reads them through. */
        private fun pass(n: Long) {
            val fromBuffer = minOf(n, available().toLong()).toInt()
            at += fromBuffer
            position += fromBuffer
            var rest = n - fromBuffer
            if (solid && !(version == 4 && listing)) {
                while (rest > 0) {
                    if (!fill(1)) throw IOException("Truncated input file")
                    val step = minOf(rest, available().toLong()).toInt()
                    at += step
                    position += step
                    rest -= step
                }
                return
            }
            if (solid) solidBroken = true
            if (rest > 0) {
                val skipped = source.skip(rest)
                position += skipped
                if (skipped < rest) throw IOException("Truncated input file")
            }
        }

        fun signature() {
            val head = peek(7) ?: throw IOException("Unrecognized archive format")
            val rar = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07)
            if (!rar.indices.all { head[it] == rar[it] }) throw IOException("Unrecognized archive format")
            version = when (head[6].toInt()) {
                0 -> 4
                1 -> 5
                else -> throw IOException("Unrecognized archive format")
            }
        }

        fun next(meta: LongArray): ByteArray? {
            if (ended) return null
            if (left > 0) pass(left)
            left = 0
            val headerAt = position
            if (!started) {
                started = true
                take(if (version == 4) 7 else 8)
            }
            while (true) {
                val name = if (version == 4) block4(meta) else block5(meta)
                if (ended) return null
                if (name != null) {
                    meta[LibArchive.META_POSITION] = headerAt
                    return name
                }
            }
        }

        /** One RAR 4 block: a file's name with [meta] filled, or null for another block. */
        private fun block4(meta: LongArray): ByteArray? {
            if (peek(7) == null) {
                ended = true
                return null
            }
            val lead = take(7)
            val type = lead[2].toInt() and 0xFF
            val flags = u16(lead, 3)
            val size = u16(lead, 5)
            if (size < 7) throw IOException("Invalid header size")
            val rest = take(size - 7)
            val crc = CRC32().apply { update(lead, 2, 5); update(rest) }.value and 0xFFFF
            if (crc != u16(lead, 0).toLong()) throw IOException("Header CRC error")
            when (type) {
                0x73 -> {
                    solid = flags and 0x0008 != 0
                    return null
                }
                0x7B -> {
                    ended = true
                    return null
                }
                0x74 -> {
                    needsEarlier = flags and 0x0010 != 0 && solidBroken
                    val packed = u32(rest, 0)
                    val unpacked = u32(rest, 4)
                    val method = rest[18].toInt() and 0xFF
                    val nameSize = u16(rest, 19)
                    // Past the high halves of 64-bit sizes when present (0x0100).
                    val nameAt = if (flags and 0x0100 != 0) 33 else 25
                    val name = rest.copyOfRange(nameAt, nameAt + nameSize)
                    val folder = flags and 0x00E0 == 0x00E0
                    meta[LibArchive.META_SIZE] = unpacked
                    meta[LibArchive.META_TYPE] = if (folder) LibArchive.TYPE_DIR else LibArchive.TYPE_FILE
                    meta[LibArchive.META_ENCRYPTED] = if (flags and 0x0004 != 0) 1L else 0L
                    startEntry(packed, method == 0x30 && flags and 0x0004 == 0, u32(rest, 9))
                    return name
                }
                else -> {
                    if (flags and 0x8000 != 0) pass(u32(rest, 0))
                    return null
                }
            }
        }

        /** One RAR 5 block: a file's name with [meta] filled, or null for another block. */
        private fun block5(meta: LongArray): ByteArray? {
            val lead = peek(5)
            if (lead == null) {
                ended = true
                return null
            }
            val storedCrc = u32(lead, 0)
            take(4)
            val sizeBytes = ByteArrayOutputStream()
            var headerSize = 0L
            var shift = 0
            while (true) {
                val b = take(1)[0].toInt() and 0xFF
                sizeBytes.write(b)
                headerSize = headerSize or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            val body = take(headerSize.toInt())
            if (CRC32().apply { update(sizeBytes.toByteArray()); update(body) }.value != storedCrc) throw IOException("Header CRC error")
            val r = Vints(body)
            val type = r.next()
            val flags = r.next()
            val extra = if (flags and 1L != 0L) r.next() else 0L
            val data = if (flags and 2L != 0L) r.next() else 0L
            when (type) {
                1L -> {
                    solid = r.next() and 4L != 0L
                    return null
                }
                4L -> throw IOException("Encryption is not supported")
                5L -> {
                    ended = true
                    return null
                }
                2L -> {
                    val fileFlags = r.next()
                    val unpacked = r.next()
                    r.next()
                    if (fileFlags and 2L != 0L) r.at += 4
                    val crc = if (fileFlags and 4L != 0L) u32(body, r.at).also { r.at += 4 } else -1L
                    val compression = r.next()
                    if (fileFlags and 1L == 0L && (128L * 1024 shl ((compression shr 10) and 15).toInt()) > 64L * 1024 * 1024) {
                        throw IOException("Declared dictionary size is not supported")
                    }
                    r.next()
                    val nameLength = r.next().toInt()
                    val name = body.copyOfRange(r.at, r.at + nameLength)
                    var encrypted = false
                    if (extra > 0) {
                        val records = Vints(body).apply { at = body.size - extra.toInt() }
                        while (records.at < body.size) {
                            val size = records.next()
                            val next = records.at + size.toInt()
                            if (records.next() == 1L) encrypted = true
                            records.at = next
                        }
                    }
                    meta[LibArchive.META_SIZE] = unpacked
                    meta[LibArchive.META_TYPE] = if (fileFlags and 1L != 0L) LibArchive.TYPE_DIR else LibArchive.TYPE_FILE
                    meta[LibArchive.META_ENCRYPTED] = if (encrypted) 1L else 0L
                    startEntry(data, (compression shr 7) and 7L == 0L && !encrypted, crc)
                    return name
                }
                else -> {
                    pass(data)
                    return null
                }
            }
        }

        private fun startEntry(packed: Long, readable: Boolean, crc: Long) {
            left = packed
            stored = readable
            this.crc = CRC32()
            expectedCrc = crc
        }

        fun read(dst: ByteArray, off: Int, len: Int): Int {
            if (!started || ended) throw IOException("No entry")
            if (needsEarlier) throw IOException("RAR solid entry needs the entries before it")
            if (!stored) throw IOException("Unsupported in this stand-in: not stored, or encrypted")
            if (left == 0L) {
                if (expectedCrc >= 0 && crc.value != expectedCrc) {
                    expectedCrc = -1
                    throw IOException("File CRC error")
                }
                return -1
            }
            if (!fill(1)) throw IOException("Truncated input file")
            val n = minOf(len.toLong(), left, available().toLong()).toInt()
            System.arraycopy(buffered, at, dst, off, n)
            crc.update(dst, off, n)
            at += n
            position += n
            left -= n
            return n
        }

        private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

        private fun u32(b: ByteArray, at: Int) = u16(b, at).toLong() or (u16(b, at + 2).toLong() shl 16)
    }

    private class Vints(private val b: ByteArray) {
        var at = 0

        fun next(): Long {
            var value = 0L
            var shift = 0
            while (true) {
                if (at >= b.size) throw IOException("Bad RAR header")
                val x = b[at++].toInt() and 0xFF
                value = value or ((x and 0x7F).toLong() shl shift)
                if (x and 0x80 == 0) return value
                shift += 7
            }
        }
    }
}
