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
 * What a ZIP says was done to it: one block at the end of its archive comment holding a
 * history chain and the XXH3-64 of its central directory as it is now, e.g.
 * `[i: Tool:compact-max:v2, Emanata:embed:v1 | 9f86d081884c7d65]`. Entries `Tool:action:vN`
 * run oldest first; spaces or tabs may stand around every separator and inside the brackets;
 * the hash is 16 hex digits, read in either case. A chain counts only while its hash matches
 * ([verified]): the central directory lists every entry's name, sizes and CRC-32, so any
 * change to the files changes it; reading it takes only the file's tail. Emanata's Compact,
 * Make compatible, Repack and Pack append their entries to the source's chain ([write]).
 */
object OutputMarks {
    /** The tool name Emanata writes. */
    const val TOOL = "Emanata"

    /** The longest ZIP archive comment, in bytes. */
    const val MAX_COMMENT = 0xFFFF

    /** Verbs that leave the pages as they were: any other changes them. */
    val KEEPS_PAGES = setOf("embed", "metadata", "tag")

    /** Any tool's compacting: this verb, or it with a variant after a hyphen. */
    private const val COMPACT_VERB = "compact"

    /**
     * What Emanata did, and the version of its output: bumped when that operation's output
     * would improve, so an older version's outputs may be offered it again.
     */
    enum class Operation(val verb: String, val version: Long) {
        COMPACT("compact", 1),

        /** Compacted, its hidden pages left out ("Skip hidden pages"). */
        COMPACT_PARTIAL("compact-partial", 1),
        COMPATIBLE("compatible", 1),
        REPACK("repack", 1),

        /** A folder of loose images made a book. */
        PACK("pack", 1);

        /** Emanata's entry for this operation. */
        val entry: Entry get() = Entry(TOOL, verb, version)
    }

    /** One step of a chain: [tool] did [action], its output at [version]. */
    data class Entry(val tool: String, val action: String, val version: Long) {
        override fun toString() = "$tool:$action:v$version"

        /** True for the same step, tool and action in any case. */
        fun sameAs(other: Entry) = tool.equals(other.tool, true) && action.equals(other.action, true) && version == other.version

        /** False for a verb of [KEEPS_PAGES]. */
        val changesPages: Boolean get() = KEEPS_PAGES.none { it.equals(action, true) }

        /** True for Emanata's. */
        val ours: Boolean get() = tool.equals(TOOL, true)
    }

    /** A block found: its [entries], oldest first, and the directory hash it was written for. */
    data class Block(val entries: List<Entry>, val hash: Long)

    private const val SPACE = "[ \\t]*"
    private const val NAME = "[A-Za-z0-9._-]+"
    private const val ENTRY_SHAPE = "$NAME$SPACE:$SPACE$NAME$SPACE:${SPACE}v\\d+"
    private val ENTRY = Regex("($NAME)$SPACE:$SPACE($NAME)$SPACE:${SPACE}v(\\d+)")
    private const val HASH_DIGITS = 16

    /** Any block, by shape alone. */
    private val BLOCK = Regex("\\[i:$SPACE($ENTRY_SHAPE(?:$SPACE,$SPACE$ENTRY_SHAPE)*)$SPACE\\|$SPACE([0-9A-Fa-f]{$HASH_DIGITS})$SPACE]")

    /** A block with the line break before it, dropped together. */
    private val BLOCK_LINE = Regex("(?:\\r?\\n)?" + BLOCK.pattern)

    private val LEADING_BREAK = Regex("^\\r?\\n")

    /** The blocks in [comment], in order; one with a version too large to read is no block. */
    fun blocks(comment: String): List<Block> = BLOCK.findAll(comment).mapNotNull(::blockOf).toList()

    private fun blockOf(m: MatchResult): Block? {
        val entries = ArrayList<Entry>()
        for (e in ENTRY.findAll(m.groupValues[1])) {
            val (tool, action, version) = e.destructured
            entries += Entry(tool, action, version.toLongOrNull() ?: return null)
        }
        return Block(entries, java.lang.Long.parseUnsignedLong(m.groupValues[2], 16))
    }

    /** [comment] without any block, each taken with the line break before it, or after it when it starts the comment. */
    fun withoutBlocks(comment: String): String {
        val starts = BLOCK.find(comment)?.let { it.range.first == 0 && blockOf(it) != null } == true
        val text = BLOCK_LINE.replace(comment) { m -> if (BLOCK.find(m.value)?.let(::blockOf) != null) "" else m.value }
        return if (starts) text.replaceFirst(LEADING_BREAK, "") else text
    }

    /** The block of [entries] over a central directory of hash [directory]. */
    fun block(entries: List<Entry>, directory: Long): String =
        "[i: ${entries.joinToString(", ")} | ${java.lang.Long.toHexString(directory).padStart(HASH_DIGITS, '0')}]"

    /** [chain] with [added] after it, no entry the same as the one before it. */
    fun appended(chain: List<Entry>, added: List<Entry>): List<Entry> {
        val out = ArrayList<Entry>(chain.size + added.size)
        for (e in chain + added) if (out.lastOrNull()?.sameAs(e) != true) out += e
        return out
    }

    /**
     * The comment for a new archive from its source's [kept] comment (bytes as they were):
     * its human text as it is, then, on its own last line, one block of every block's chain
     * in order with [added] after it, over a central directory of hash [directory]. The
     * oldest entries are dropped first when the comment would pass [MAX_COMMENT]; human text
     * alone when even the newest entry does not fit, or [directory] is null (not located).
     */
    fun comment(kept: ByteArray, added: List<Entry>, directory: Long?): ByteArray {
        val found = String(kept, Charsets.ISO_8859_1)
        val text = withoutBlocks(found)
        if (directory != null) {
            val chain = appended(blocks(found).flatMap { it.entries }, added)
            val lead = if (text.isEmpty()) "" else text + "\n"
            for (from in chain.indices) {
                val joined = lead + block(chain.subList(from, chain.size), directory)
                if (joined.length <= MAX_COMMENT) return joined.toByteArray(Charsets.ISO_8859_1)
            }
        }
        return text.toByteArray(Charsets.ISO_8859_1)
    }

    /** What a verified chain says: compacted (by which tool), made compatible, repacked. */
    data class Done(val compactedBy: String?, val compatible: Boolean, val repacked: Boolean) {
        companion object {
            val NOTHING = Done(null, compatible = false, repacked = false)
        }
    }

    /** The chain of the blocks in [comment] written for a central directory of hash [directory], in order. */
    fun chain(comment: String, directory: Long): List<Entry> = blocks(comment).filter { it.hash == directory }.flatMap { it.entries }

    /**
     * What [chain] says is done: an operation whose entry is there with no entry changing
     * pages after it. Emanata's count at the operation's current version or a later one;
     * any tool's `compact` or `compact-*` counts as compacted, at any version.
     */
    fun done(chain: List<Entry>): Done {
        fun last(test: (Entry) -> Boolean): Entry? {
            for (e in chain.asReversed()) {
                if (test(e)) return e
                if (e.changesPages) return null
            }
            return null
        }
        fun current(e: Entry) = !e.ours || e.version >= (Operation.entries.firstOrNull { it.verb.equals(e.action, true) }?.version ?: 0)
        fun ours(op: Operation) = last { it.ours && it.action.equals(op.verb, true) && it.version >= op.version } != null
        val compacted = last { e -> (e.action.equals(COMPACT_VERB, true) || e.action.startsWith("$COMPACT_VERB-", true)) && current(e) }
        return Done(compacted?.tool, ours(Operation.COMPATIBLE), ours(Operation.REPACK))
    }

    /** What the chain of [tail] verified by its central directory's hash says was done ([done]); a stale chain is history only. */
    fun verified(tail: Tail): Done = done(chain(String(tail.comment, Charsets.ISO_8859_1), tail.directory))

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
        return directoryHash(channel, raw, hash)?.let { Tail(raw.comment, it, raw.end) }
    }

    /** The XXH3-64 of the central directory [raw] locates; null when it locates none or it cannot be read. */
    private fun directoryHash(channel: SeekableByteChannel, raw: RawTail, hash: Xxh3): Long? {
        val at = raw.directoryAt ?: return null
        val directory = read(channel, at, raw.directorySize.toInt()) ?: return null
        return hash.hash(directory, directory.size)
    }

    /**
     * What the end of central directory record says, read without the directory itself:
     * where the directory starts ([directoryAt]), null when it is not located (or too large to hash).
     */
    private class RawTail(val comment: ByteArray, val directoryAt: Long?, val directorySize: Long, val end: Long)

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
            return RawTail(comment, null, 0, end)
        }
        if (directorySize < 0 || directorySize > MAX_DIRECTORY_BYTES || directoryEnd - directorySize < 0) return RawTail(comment, null, 0, end)
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
     * Ends [zip], a ZIP just written with no comment, with the comment ([comment]) of its
     * source's [kept] comment and the entries of [operations] (in order) over its central
     * directory, then reads it back: true when the block is there, verifies and ends with
     * the last operation's entry; false when only the human text was written (too long, or
     * the directory not located). Throws when [zip] is no ZIP or already has a comment.
     */
    fun write(zip: File, kept: ByteArray, operations: List<Operation>, hash: Xxh3): Boolean {
        require(operations.isNotEmpty()) { "No operation" }
        FileChannel.open(zip.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
            val raw = tailBytes(channel) ?: throw IOException("No ZIP end in ${zip.name}")
            if (raw.comment.isNotEmpty()) throw IOException("${zip.name} has a comment")
            val directory = directoryHash(channel, raw, hash)
            val text = comment(kept, operations.map { it.entry }, directory)
            val out = ByteBuffer.allocate(2 + text.size).order(ByteOrder.LITTLE_ENDIAN)
            out.putShort(text.size.toShort()).put(text).flip()
            channel.position(raw.end + 20)
            while (out.hasRemaining()) channel.write(out)
            channel.truncate(raw.end + EOCD_BYTES + text.size)
            channel.force(true)
            val back = tailBytes(channel) ?: throw IOException("No ZIP end in ${zip.name}")
            val backDirectory = directoryHash(channel, back, hash)
            if (!back.comment.contentEquals(text) || backDirectory != directory) throw IOException("Comment of ${zip.name} differs")
            if (directory == null) return false
            return chain(String(text, Charsets.ISO_8859_1), directory).lastOrNull()?.sameAs(operations.last().entry) == true
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
