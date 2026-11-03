package com.infinity.comicreader.compact

import com.infinity.comicreader.data.Xxh3
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.StandardOpenOption

/**
 * What a ZIP says was done to it: blocks `[i:<tool> <operation> v<version> <hash>]` at the
 * end of its archive comment, hash the XXH3-64 of its central directory in 16 hex digits.
 * Emanata's Compact, Make compatible and Repack append theirs ([write]); any tool's are
 * read ([blocks]), and count only while the hash still matches ([verified]). The central
 * directory lists every entry's name, sizes and CRC-32, so any change to the files
 * changes it; reading it takes only the file's tail.
 */
object OutputMarks {
    /** The tool name Emanata writes. */
    const val TOOL = "Emanata"

    /** The longest ZIP archive comment, in bytes. */
    const val MAX_COMMENT = 0xFFFF

    /**
     * What Emanata did, and the version of its output: bumped when that operation's output
     * would improve, so an older version's outputs may be offered it again.
     */
    enum class Operation(val word: String, val version: Long) {
        COMPACTED("compacted", 1),

        /** Compacted, its hidden pages left out ("Skip hidden pages"). */
        COMPACTED_PARTIAL("compacted-partial", 1),
        COMPATIBLE("compatible", 1),
        REPACKED("repacked", 1)
    }

    /** A block found: [hash] as written, verifiable only as 16 hex digits ([value]). */
    data class Block(val tool: String, val operation: String, val version: Long, val hash: String) {
        /** The hash as a number; null for any other form, which nothing verifies. */
        val value: Long?
            get() = if (hash.length == HASH_DIGITS && hash.all { Character.digit(it, 16) >= 0 }) java.lang.Long.parseUnsignedLong(hash, 16) else null
    }

    /** Any tool's block, by shape alone. */
    private val BLOCK = Regex("""\[i:(\S+?) (\S+?) v(\d+) (\S+?)]""")

    /** A block with the line break before it, dropped together. */
    private val BLOCK_LINE = Regex("""(?:\r?\n)?""" + BLOCK.pattern)

    private const val HASH_DIGITS = 16

    /** The blocks in [comment], in order; one whose version is too large to read is left out. */
    fun blocks(comment: String): List<Block> = BLOCK.findAll(comment).mapNotNull { m ->
        val (tool, operation, version, hash) = m.destructured
        version.toLongOrNull()?.let { Block(tool, operation, it, hash) }
    }.toList()

    /** [comment] without any block, each taken with the line break before it. */
    fun withoutBlocks(comment: String): String = BLOCK_LINE.replace(comment, "")

    /** Emanata's block for [operation] over a central directory of hash [directory]. */
    fun block(operation: Operation, directory: Long): String =
        "[i:$TOOL ${operation.word} v${operation.version} ${java.lang.Long.toHexString(directory).padStart(HASH_DIGITS, '0')}]"

    /**
     * The comment for a new archive from its source's [kept] comment (bytes as they were,
     * blocks then dropped) with [block] after it on a new line; just what is kept when both
     * would be longer than [MAX_COMMENT].
     */
    fun comment(kept: ByteArray, block: String): ByteArray {
        val text = withoutBlocks(String(kept, Charsets.ISO_8859_1))
        val base = text.toByteArray(Charsets.ISO_8859_1)
        val joined = (if (text.isEmpty()) block else text + "\n" + block).toByteArray(Charsets.ISO_8859_1)
        return if (joined.size <= MAX_COMMENT) joined else base
    }

    /** What [blocks] verified against a central directory say: compacted (by which tool), made compatible, repacked. */
    data class Done(val compactedBy: String?, val compatible: Boolean, val repacked: Boolean) {
        companion object {
            val NOTHING = Done(null, compatible = false, repacked = false)
        }
    }

    /**
     * What the blocks of [tail] verified by its central directory's hash say was done: by
     * Emanata, each operation at its current version or a later one; by any other tool,
     * compacting at any version. A block from before the archive last changed counts for nothing.
     */
    fun verified(tail: Tail): Done {
        val valid = blocks(String(tail.comment, Charsets.ISO_8859_1)).filter { it.value == tail.directory }
        fun ours(op: Operation) = valid.any { it.tool == TOOL && it.operation == op.word && it.version >= op.version }
        val compacted = when {
            ours(Operation.COMPACTED) || ours(Operation.COMPACTED_PARTIAL) -> TOOL
            else -> valid.firstOrNull { it.tool != TOOL && it.operation == Operation.COMPACTED.word }?.tool
        }
        return Done(compacted, ours(Operation.COMPATIBLE), ours(Operation.REPACKED))
    }

    /**
     * [verified] for the ZIP [file] on the device; nothing for one that is no ZIP or cannot
     * be read, a file on a share included (rebuilds never run there): a share's tail reads
     * through [tail] with its remote channel. Blocking.
     */
    fun verified(file: File, hash: Xxh3): Done = try {
        FileChannel.open(file.toPath(), StandardOpenOption.READ).use { tail(it, hash) }?.let(::verified) ?: Done.NOTHING
    } catch (e: IOException) {
        Done.NOTHING
    } catch (e: SecurityException) {
        Done.NOTHING
    }

    /**
     * A ZIP's tail: its archive [comment] as stored, the XXH3-64 of its central [directory],
     * and where its end of central directory record starts ([end]).
     */
    class Tail(val comment: ByteArray, val directory: Long, val end: Long)

    /** The comment of the ZIP [file], as stored; empty when it is no ZIP or cannot be read. Blocking. */
    fun commentOf(file: File): ByteArray = try {
        FileChannel.open(file.toPath(), StandardOpenOption.READ).use { tailBytes(it) }?.comment ?: ByteArray(0)
    } catch (e: IOException) {
        ByteArray(0)
    }

    /** The tail of the ZIP read through [channel]; null when it has none, or its central directory is out of reach. */
    fun tail(channel: SeekableByteChannel, hash: Xxh3): Tail? {
        val raw = tailBytes(channel) ?: return null
        val directory = read(channel, raw.directoryAt, raw.directorySize.toInt()) ?: return null
        return Tail(raw.comment, hash.hash(directory, directory.size), raw.end)
    }

    /** What the end of central directory record says, read without the directory itself. */
    private class RawTail(val comment: ByteArray, val directoryAt: Long, val directorySize: Long, val end: Long)

    private fun tailBytes(channel: SeekableByteChannel): RawTail? {
        val size = channel.size()
        if (size < EOCD_BYTES) return null
        val take = minOf(size, (EOCD_BYTES + MAX_COMMENT).toLong()).toInt()
        val buf = read(channel, size - take, take) ?: return null
        val b = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        // The record whose comment runs to the file's end, searched from the end back.
        var at = take - EOCD_BYTES
        while (at >= 0) {
            if (b.getInt(at) == EOCD_SIG && at + EOCD_BYTES + (b.getShort(at + 20).toInt() and 0xFFFF) == take) break
            at--
        }
        if (at < 0) return null
        val end = size - take + at
        val comment = buf.copyOfRange(at + EOCD_BYTES, take)
        var directorySize = b.getInt(at + 12).toLong() and 0xFFFFFFFFL
        val directoryAt = b.getInt(at + 16).toLong() and 0xFFFFFFFFL
        val entries = b.getShort(at + 10).toInt() and 0xFFFF
        // Where the directory ends: at the end record, or at a ZIP64 end record wherever one
        // precedes it (some writers add one to every archive), which then holds the directory's size.
        var directoryEnd = end
        val sentinels = directorySize == 0xFFFFFFFFL || directoryAt == 0xFFFFFFFFL || entries == 0xFFFF
        val record = zip64Record(channel, end)
        if (record != null) {
            directorySize = record.second
            directoryEnd = record.first
        } else if (sentinels) {
            return null
        }
        if (directorySize < 0 || directorySize > MAX_DIRECTORY_BYTES || directoryEnd - directorySize < 0) return null
        // Where the directory is by where it ends, so data before the archive (a self-extractor) moves nothing.
        return RawTail(comment, directoryEnd - directorySize, directorySize, end)
    }

    /**
     * The ZIP64 end record before the end record at [end], as where it starts and the
     * central directory's size it holds; null when there is none. Found through its locator,
     * or right before the locator when the locator's offset is off (data before the archive).
     */
    private fun zip64Record(channel: SeekableByteChannel, end: Long): Pair<Long, Long>? {
        val locatorAt = end - ZIP64_LOCATOR_BYTES
        val locator = read(channel, locatorAt, ZIP64_LOCATOR_BYTES)?.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN) } ?: return null
        if (locator.getInt(0) != ZIP64_LOCATOR_SIG) return null
        for (at in listOf(locator.getLong(8), locatorAt - ZIP64_EOCD_BYTES).distinct()) {
            if (at < 0 || at > locatorAt - ZIP64_EOCD_BYTES) continue
            val record = read(channel, at, ZIP64_EOCD_BYTES)?.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN) } ?: continue
            if (record.getInt(0) == ZIP64_EOCD_SIG) return at to record.getLong(40)
        }
        return null
    }

    /**
     * Ends [zip], a ZIP just written with no comment, with the comment of its source's
     * [kept] comment and Emanata's block for [operation] over its central directory
     * ([comment]), then reads it back: true when the block is there and verifies, false
     * when only [kept] fit. Throws when [zip] is no ZIP or already has a comment.
     */
    fun write(zip: File, kept: ByteArray, operation: Operation, hash: Xxh3): Boolean {
        FileChannel.open(zip.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
            val tail = tail(channel, hash) ?: throw IOException("No ZIP end in ${zip.name}")
            if (tail.comment.isNotEmpty()) throw IOException("${zip.name} has a comment")
            val text = comment(kept, block(operation, tail.directory))
            val out = ByteBuffer.allocate(2 + text.size).order(ByteOrder.LITTLE_ENDIAN)
            out.putShort(text.size.toShort()).put(text).flip()
            channel.position(tail.end + 20)
            while (out.hasRemaining()) channel.write(out)
            channel.truncate(tail.end + EOCD_BYTES + text.size)
            channel.force(true)
            val back = tail(channel, hash) ?: throw IOException("No ZIP end in ${zip.name}")
            if (!back.comment.contentEquals(text) || back.directory != tail.directory) throw IOException("Comment of ${zip.name} differs")
            val marked = blocks(String(text, Charsets.ISO_8859_1)).any { it.tool == TOOL && it.operation == operation.word && it.value == back.directory }
            return marked
        }
    }

    /** [length] bytes of [channel] from [at]; null when they are not all there. */
    private fun read(channel: SeekableByteChannel, at: Long, length: Int): ByteArray? {
        if (at < 0 || length < 0 || at + length > channel.size()) return null
        val buf = ByteBuffer.allocate(length)
        channel.position(at)
        while (buf.hasRemaining()) if (channel.read(buf) < 0) return null
        return buf.array()
    }

    private const val EOCD_SIG = 0x06054b50
    private const val EOCD_BYTES = 22
    private const val ZIP64_LOCATOR_SIG = 0x07064b50
    private const val ZIP64_LOCATOR_BYTES = 20
    private const val ZIP64_EOCD_SIG = 0x06064b50
    private const val ZIP64_EOCD_BYTES = 56

    /** Larger central directories are not read for their hash: hundreds of thousands of entries. */
    private const val MAX_DIRECTORY_BYTES = 32L * 1024 * 1024
}
