package com.infinity.comicreader.reader

import java.io.ByteArrayOutputStream

/**
 * Quick previews held inside AVIF files, cut out as small standalone AVIF files that
 * the platform decoder reads like any other ([extract]):
 *  - an embedded thumbnail: an `av01` item linked to the primary item by a `thmb`
 *    reference;
 *  - the first layer of a layered image (`a1lx`, as libavif's `--layered` writes it):
 *    the primary item's first layer of AV1 data alone, its size in `ispe`, and the
 *    sequence header's maximum frame size set to that layer's own. Left at the full
 *    size, Android 16's decoder (Codec2 dav1d) took seconds over it instead of tens of
 *    milliseconds.
 * Either needs only the start of the file ([needed]). Pure byte work: nothing is decoded.
 * A preview is only taken when it is at least the size `minSize` asks of a file's full
 * size, so it never stands in for a sharper one.
 */
object AvifPreview {
    /** Longest start of a file read for a preview. */
    const val MAX_PREFIX = 2 * 1024 * 1024

    /**
     * How many leading bytes of a file whose first [length] bytes are in [head] its
     * preview needs: more than [length] while its metadata is not all in (call again
     * with at least that many), 0 when it has no preview of at least `minSize`.
     */
    fun needed(head: ByteArray, length: Int, minSize: (Int, Int) -> IntArray): Int {
        val meta = when (val found = findMeta(head, length)) {
            is Found.More -> return if (found.length <= MAX_PREFIX) found.length else 0
            is Found.None -> return 0
            is Found.At -> found.box
        }
        val parsed = parseMeta(head, meta) ?: return 0
        val plan = plan(parsed, minSize) ?: return 0
        return plan.end.takeIf { it <= MAX_PREFIX }?.toInt() ?: 0
    }

    /**
     * The preview of the AVIF file starting with [bytes] as an AVIF file of its own, or
     * null when it has none of at least `minSize` (or [bytes] stop short of it).
     */
    fun extract(bytes: ByteArray, minSize: (Int, Int) -> IntArray): ByteArray? {
        val meta = (findMeta(bytes, bytes.size) as? Found.At)?.box ?: return null
        val parsed = parseMeta(bytes, meta) ?: return null
        val plan = plan(parsed, minSize) ?: return null
        if (plan.end > bytes.size) return null
        val item = parsed.items[plan.item] ?: return null
        val data = itemData(bytes, item, plan.length) ?: return null
        return when (plan) {
            is Plan.Thumbnail -> build(parsed, item, data, plan.width, plan.height, layered = false)
            is Plan.FirstLayer -> {
                val layer = Av1.firstLayer(data) ?: return null
                val want = minSize(plan.fullWidth, plan.fullHeight)
                if (layer.width < want[0] || layer.height < want[1]) return null
                build(parsed, item, layer.data, layer.width, layer.height, layered = true)
            }
        }
    }

    // ------------------------------------------------------------------ boxes

    /** A box at [start] whose contents run from [body] to [end] in the file. */
    private class Box(val type: String, val start: Int, val body: Int, val end: Int)

    private sealed class Found {
        class At(val box: Box) : Found()
        class More(val length: Int) : Found()
        object None : Found()
    }

    /** The top-level `meta` box of a file whose first [length] bytes are in [b]. */
    private fun findMeta(b: ByteArray, length: Int): Found {
        val n = minOf(length, b.size)
        var at = 0L
        while (true) {
            if (at + 8 > n) return Found.More((at + 16).toInt())
            var size = u32(b, at.toInt())
            val type = fourCc(b, at.toInt() + 4)
            var header = 8
            if (size == 1L) {
                if (at + 16 > n) return Found.More((at + 16).toInt())
                size = u64(b, at.toInt() + 8)
                header = 16
            }
            if (size == 0L) return Found.None
            if (size < header || at + size > Int.MAX_VALUE) return Found.None
            val end = at + size
            if (type == "meta") {
                if (end > n) return Found.More(end.toInt())
                return Found.At(Box(type, at.toInt(), at.toInt() + header, end.toInt()))
            }
            if (at > MAX_PREFIX) return Found.None
            at = end
        }
    }

    /** The boxes inside [from] until [to]; null when one runs past [to]. */
    private fun children(b: ByteArray, from: Int, to: Int): List<Box>? {
        val out = ArrayList<Box>()
        var at = from
        while (at + 8 <= to) {
            var size = u32(b, at)
            var header = 8
            if (size == 1L) {
                if (at + 16 > to) return null
                size = u64(b, at + 8)
                header = 16
            }
            if (size == 0L) size = (to - at).toLong()
            if (size < header || at + size > to) return null
            out += Box(fourCc(b, at + 4), at, at + header, (at + size).toInt())
            at += size.toInt()
        }
        return out
    }

    // ------------------------------------------------------------------ meta

    private class Item(val id: Int) {
        var type = ""
        var method = 0
        val extents = ArrayList<LongArray>()
        /** Property indexes into [Meta.properties], 1-based, with their essential flags. */
        val properties = ArrayList<Pair<Int, Boolean>>()
    }

    private class Meta(val primary: Int, val items: Map<Int, Item>, val properties: List<Box>, val thumbnails: List<Int>, val bytes: ByteArray) {
        fun property(item: Item, type: String): Box? =
            item.properties.firstNotNullOfOrNull { (index, _) -> properties.getOrNull(index - 1)?.takeIf { it.type == type } }

        /** `[width, height]` from [item]'s `ispe`. */
        fun size(item: Item): IntArray? = property(item, "ispe")?.let {
            if (it.end - it.body < 12) null else intArrayOf(u32(bytes, it.body + 4).toInt(), u32(bytes, it.body + 8).toInt())
        }?.takeIf { it[0] > 0 && it[1] > 0 }
    }

    private fun parseMeta(b: ByteArray, meta: Box): Meta? = try {
        val boxes = children(b, meta.body + 4, meta.end) ?: throw IllegalArgumentException()
        val items = HashMap<Int, Item>()
        fun item(id: Int) = items.getOrPut(id) { Item(id) }
        var primary = -1
        var properties: List<Box> = emptyList()
        val thumbnails = ArrayList<Pair<Int, List<Int>>>()
        for (box in boxes) {
            val version = b[box.body].toInt() and 0xFF
            val at = box.body + 4
            when (box.type) {
                "pitm" -> primary = if (version == 0) u16(b, at) else u32(b, at).toInt()
                "iloc" -> parseIloc(b, at, version, ::item)
                "iinf" -> {
                    val first = at + if (version == 0) 2 else 4
                    for (infe in children(b, first, box.end) ?: emptyList()) {
                        if (infe.type != "infe") continue
                        val v = b[infe.body].toInt() and 0xFF
                        if (v < 2) continue
                        var p = infe.body + 4
                        val id = if (v == 2) u16(b, p).also { p += 2 } else u32(b, p).toInt().also { p += 4 }
                        item(id).type = fourCc(b, p + 2)
                    }
                }
                "iref" -> for (ref in children(b, at, box.end) ?: emptyList()) {
                    val wide = version != 0
                    var p = ref.body
                    val from = if (wide) u32(b, p).toInt().also { p += 4 } else u16(b, p).also { p += 2 }
                    val count = u16(b, p)
                    p += 2
                    val to = (0 until count).map { if (wide) u32(b, p + 4 * it).toInt() else u16(b, p + 2 * it) }
                    if (ref.type == "thmb") thumbnails += from to to
                }
                "iprp" -> for (child in children(b, box.body, box.end) ?: emptyList()) {
                    when (child.type) {
                        "ipco" -> properties = children(b, child.body, child.end) ?: emptyList()
                        "ipma" -> parseIpma(b, child, ::item)
                    }
                }
            }
        }
        if (primary < 0 || primary !in items) null
        else Meta(primary, items, properties, thumbnails.filter { primary in it.second }.map { it.first }, b)
    } catch (e: IndexOutOfBoundsException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun parseIloc(b: ByteArray, start: Int, version: Int, item: (Int) -> Item) {
        if (version > 2) throw IllegalArgumentException()
        var p = start
        val offsetSize = (b[p].toInt() shr 4) and 15
        val lengthSize = b[p].toInt() and 15
        val baseSize = (b[p + 1].toInt() shr 4) and 15
        val indexSize = if (version >= 1) b[p + 1].toInt() and 15 else 0
        p += 2
        val count = if (version < 2) u16(b, p).also { p += 2 } else u32(b, p).toInt().also { p += 4 }
        repeat(count) {
            val id = if (version < 2) u16(b, p).also { p += 2 } else u32(b, p).toInt().also { p += 4 }
            val method = if (version >= 1) (u16(b, p) and 15).also { p += 2 } else 0
            val reference = u16(b, p)
            p += 2
            val base = sized(b, p, baseSize)
            p += baseSize
            val extents = u16(b, p)
            p += 2
            val target = item(id)
            target.method = if (reference != 0) -1 else method
            repeat(extents) {
                p += indexSize
                val offset = base + sized(b, p, offsetSize)
                p += offsetSize
                val length = sized(b, p, lengthSize)
                p += lengthSize
                target.extents += longArrayOf(offset, length)
            }
        }
    }

    private fun parseIpma(b: ByteArray, box: Box, item: (Int) -> Item) {
        val version = b[box.body].toInt() and 0xFF
        val wideIndex = (b[box.body + 3].toInt() and 1) != 0
        var p = box.body + 4
        val count = u32(b, p).toInt()
        p += 4
        repeat(count) {
            val id = if (version < 1) u16(b, p).also { p += 2 } else u32(b, p).toInt().also { p += 4 }
            val n = b[p].toInt() and 0xFF
            p++
            val target = item(id)
            repeat(n) {
                if (wideIndex) {
                    val v = u16(b, p)
                    p += 2
                    target.properties += (v and 0x7FFF) to (v and 0x8000 != 0)
                } else {
                    val v = b[p].toInt() and 0xFF
                    p++
                    target.properties += (v and 0x7F) to (v and 0x80 != 0)
                }
            }
        }
    }

    // ------------------------------------------------------------------ plans

    private sealed class Plan(val item: Int, val length: Long, val end: Long) {
        class Thumbnail(item: Int, length: Long, end: Long, val width: Int, val height: Int) : Plan(item, length, end)
        class FirstLayer(item: Int, length: Long, end: Long, val fullWidth: Int, val fullHeight: Int) : Plan(item, length, end)
    }

    /** The thumbnail that covers `minSize`, smallest first; else the first layer of a layered primary item. */
    private fun plan(meta: Meta, minSize: (Int, Int) -> IntArray): Plan? {
        val primary = meta.items[meta.primary] ?: return null
        val full = meta.size(primary) ?: return null
        val want = minSize(full[0], full[1])
        val thumbnail = meta.thumbnails.mapNotNull { meta.items[it] }
            .filter { it.type == "av01" && it.method == 0 && it.extents.isNotEmpty() }
            .mapNotNull { t -> meta.size(t)?.let { t to it } }
            .filter { (_, size) -> size[0] >= want[0] && size[1] >= want[1] }
            .minByOrNull { (_, size) -> size[0].toLong() * size[1] }
        if (thumbnail != null) {
            val (t, size) = thumbnail
            val length = t.extents.sumOf { it[1] }
            return Plan.Thumbnail(t.id, length, endOf(t, length) ?: return null, size[0], size[1])
        }
        if (primary.type != "av01" || primary.method != 0) return null
        val a1lx = meta.property(primary, "a1lx") ?: return null
        val large = (meta.bytes[a1lx.body].toInt() and 1) != 0
        val first = if (large) u32(meta.bytes, a1lx.body + 1) else u16(meta.bytes, a1lx.body + 1).toLong()
        if (first <= 0) return null
        return Plan.FirstLayer(primary.id, first, endOf(primary, first) ?: return null, full[0], full[1])
    }

    /** Where the first [length] bytes of [item]'s data end in the file; null when its extents hold fewer. */
    private fun endOf(item: Item, length: Long): Long? {
        var left = length
        for ((offset, size) in item.extents) {
            if (size <= 0) return null
            if (left <= size) return offset + left
            left -= size
        }
        return null
    }

    /** The first [length] bytes of [item]'s data, gathered from its extents in [b]. */
    private fun itemData(b: ByteArray, item: Item, length: Long): ByteArray? {
        if (length > MAX_PREFIX) return null
        val out = ByteArray(length.toInt())
        var at = 0
        for ((offset, size) in item.extents) {
            if (at == out.size) break
            val take = minOf(size, (out.size - at).toLong()).toInt()
            if (offset < 0 || offset + take > b.size) return null
            System.arraycopy(b, offset.toInt(), out, at, take)
            at += take
        }
        return if (at == out.size) out else null
    }

    // ------------------------------------------------------------------ output

    /** Properties not carried over: replaced (`ispe`), or about layers and full-size geometry. */
    private val LAYER_ONLY = setOf("a1lx", "lsel", "a1op", "clap")

    /** One-item AVIF of [data] at [width] x [height], with [item]'s other properties. */
    private fun build(meta: Meta, item: Item, data: ByteArray, width: Int, height: Int, layered: Boolean): ByteArray {
        val props = ByteArrayOutputStream()
        val associations = ArrayList<Int>()
        props.write(fullBox("ispe", 0, u32Bytes(width.toLong()) + u32Bytes(height.toLong())))
        associations += 1
        for ((index, essential) in item.properties) {
            val p = meta.properties.getOrNull(index - 1) ?: continue
            if (p.type == "ispe" || (layered && p.type in LAYER_ONLY)) continue
            // Config OBUs could hold the old sequence header: decoders take it from the data.
            val bytes = if (p.type == "av1C" && p.end - p.body > 4) box("av1C", meta.bytes.copyOfRange(p.body, p.body + 4))
            else meta.bytes.copyOfRange(p.start, p.end)
            props.write(bytes)
            associations += (associations.size + 1) or (if (essential) 0x80 else 0)
        }
        if (associations.size > 127) throw IllegalArgumentException("Too many properties")
        val ipma = fullBox("ipma", 0, u32Bytes(1) + u16Bytes(1) + byteArrayOf(associations.size.toByte()) +
            ByteArray(associations.size) { associations[it].toByte() })
        val iprp = box("iprp", box("ipco", props.toByteArray()) + ipma)
        val hdlr = fullBox("hdlr", 0, ByteArray(4) + "pict".toByteArray(Charsets.US_ASCII) + ByteArray(12) + byteArrayOf(0))
        val pitm = fullBox("pitm", 0, u16Bytes(1))
        val infe = fullBox("infe", 2, u16Bytes(1) + u16Bytes(0) + "av01".toByteArray(Charsets.US_ASCII) + byteArrayOf(0))
        val iinf = fullBox("iinf", 0, u16Bytes(1) + infe)
        fun iloc(offset: Long) = fullBox("iloc", 0, byteArrayOf(0x44, 0) + u16Bytes(1) + u16Bytes(1) + u16Bytes(0) + u16Bytes(1) +
            u32Bytes(offset) + u32Bytes(data.size.toLong()))
        val ftyp = box("ftyp", "avif".toByteArray(Charsets.US_ASCII) + ByteArray(4) + "avifmif1miaf".toByteArray(Charsets.US_ASCII))
        val metaLength = fullBox("meta", 0, hdlr + pitm + iloc(0) + iinf + iprp).size
        val offset = ftyp.size + metaLength + 8L
        return ftyp + fullBox("meta", 0, hdlr + pitm + iloc(offset) + iinf + iprp) + box("mdat", data)
    }

    private fun box(type: String, body: ByteArray) = u32Bytes(8L + body.size) + type.toByteArray(Charsets.US_ASCII) + body

    private fun fullBox(type: String, version: Int, body: ByteArray) = box(type, byteArrayOf(version.toByte(), 0, 0, 0) + body)

    private fun u16Bytes(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())

    private fun u32Bytes(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    // ------------------------------------------------------------------ reading

    private fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun u32(b: ByteArray, at: Int): Long = (u16(b, at).toLong() shl 16) or u16(b, at + 2).toLong()

    private fun u64(b: ByteArray, at: Int): Long = (u32(b, at) shl 32) or u32(b, at + 4)

    /** A big-endian number of [size] bytes (0, 4 or 8) at [at]. */
    private fun sized(b: ByteArray, at: Int, size: Int): Long = when (size) {
        0 -> 0
        4 -> u32(b, at)
        8 -> u64(b, at)
        else -> throw IllegalArgumentException("Field size $size")
    }

    private fun fourCc(b: ByteArray, at: Int) = String(b, at, 4, Charsets.US_ASCII)
}
