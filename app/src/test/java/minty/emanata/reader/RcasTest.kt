package minty.emanata.reader

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class RcasTest {

    /**
     * RCAS as AMD's reference writes it, on 0..1 values in double precision with exact
     * reciprocals: `(r, g, b)` of center [e] and its neighbors, unrounded and unclamped.
     */
    private fun reference(b: DoubleArray, d: DoubleArray, e: DoubleArray, f: DoubleArray, h: DoubleArray, con: Double): DoubleArray {
        fun luma(p: DoubleArray) = p[2] * 0.5 + (p[0] * 0.5 + p[1])
        val bL = luma(b)
        val dL = luma(d)
        val eL = luma(e)
        val fL = luma(f)
        val hL = luma(h)
        var nz = 0.25 * bL + 0.25 * dL + 0.25 * fL + 0.25 * hL - eL
        val range = maxOf(bL, dL, eL, fL, hL) - minOf(bL, dL, eL, fL, hL)
        nz = if (range > 0) min(1.0, abs(nz) / range) else 0.0
        nz = -0.5 * nz + 1.0
        var lobe = Double.NEGATIVE_INFINITY
        for (c in 0..2) {
            val mn4 = minOf(b[c], d[c], f[c], h[c])
            val mx4 = maxOf(b[c], d[c], f[c], h[c])
            val hitMin = if (mx4 > 0) min(mn4, e[c]) / (4.0 * mx4) else Double.POSITIVE_INFINITY
            val hitMax = if (mn4 < 1) (1.0 - max(mx4, e[c])) / (4.0 * mn4 - 4.0) else Double.NEGATIVE_INFINITY
            lobe = max(lobe, max(-hitMin, hitMax))
        }
        lobe = max(-(0.25 - 1.0 / 16.0), min(lobe, 0.0)) * con
        lobe *= nz
        val rcp = 1.0 / (4.0 * lobe + 1.0)
        return DoubleArray(3) { c -> (lobe * b[c] + lobe * d[c] + lobe * h[c] + lobe * f[c] + e[c]) * rcp }
    }

    private fun unit(p: Int) = doubleArrayOf(((p shr 16) and 0xFF) / 255.0, ((p shr 8) and 0xFF) / 255.0, (p and 0xFF) / 255.0)

    private fun opaque(random: Random) = (0xFF shl 24) or random.nextInt(0x1000000)

    private fun channels(p: Int) = intArrayOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)

    /** Crosses of noise, of near-flat colors, and of the extremes, where the limits bind. */
    private fun crosses(): List<IntArray> {
        val random = Random(7)
        val out = ArrayList<IntArray>()
        repeat(20_000) { out += IntArray(5) { opaque(random) } }
        repeat(5_000) {
            val base = random.nextInt(256)
            out += IntArray(5) {
                val v = (base + random.nextInt(-6, 7)).coerceIn(0, 255)
                (0xFF shl 24) or (v shl 16) or ((255 - v) shl 8) or (v / 2)
            }
        }
        val levels = intArrayOf(0, 1, 2, 128, 253, 254, 255)
        repeat(5_000) { out += IntArray(5) { val v = levels[random.nextInt(levels.size)]; (0xFF shl 24) or (v * 0x010101) } }
        return out
    }

    @Test
    fun matchesAmdsReferenceWithinOneCode() {
        for (strength in listOf(10, 50, 87, 100)) {
            val amount = Rcas.amount(strength)
            var worst = 0
            for (x in crosses()) {
                val got = channels(Rcas.pixel(x[0], x[1], x[2], x[3], x[4], amount))
                val want = reference(unit(x[0]), unit(x[1]), unit(x[2]), unit(x[3]), unit(x[4]), amount.toDouble())
                for (c in 0..2) worst = max(worst, abs(got[c] - Math.round(want[c] * 255).toInt()))
            }
            assertTrue("$strength %: off by $worst", worst <= 1)
        }
    }

    @Test
    fun referenceStaysInsideTheRangeUnclamped() {
        // RCAS's limiter keeps every result inside 0..1 before any clamp.
        for (x in crosses()) {
            val r = reference(unit(x[0]), unit(x[1]), unit(x[2]), unit(x[3]), unit(x[4]), 1.0)
            for (v in r) assertTrue("$v from ${x.joinToString { Integer.toHexString(it) }}", v >= -1e-12 && v <= 1 + 1e-12)
        }
    }

    @Test
    fun limitsAreRcassOwnNotTheNeighborsRange() {
        val gray = { v: Int -> (0xFF shl 24) or (v * 0x010101) }
        // A dark dot on a lighter ground: sharpened below every neighbor, as far as RCAS's limit allows.
        val dot = Rcas.pixel(gray(128), gray(128), gray(102), gray(128), gray(128), 1f) and 0xFF
        assertTrue("$dot", dot < 102)
        // Pure black and white neighbors: the dot's result stays inside 0..255 on both sides.
        assertEquals(0, Rcas.pixel(gray(0), gray(0), gray(0), gray(0), gray(0), 1f) and 0xFF)
        assertEquals(255, Rcas.pixel(gray(255), gray(255), gray(255), gray(255), gray(255), 1f) and 0xFF)
        // A lone bright pixel among black ones: no limit binds below, so it brightens (by half, as
        // noise), within 255; a lone dark one among white ones darkens within 0.
        val bright = Rcas.pixel(gray(0), gray(0), gray(200), gray(0), gray(0), 1f) and 0xFF
        assertTrue("$bright", bright in 201..255)
        val dark = Rcas.pixel(gray(255), gray(255), gray(40), gray(255), gray(255), 1f) and 0xFF
        assertTrue("$dark", dark in 0..39)
        // Flat color never changes, whatever the strength.
        for (v in listOf(0, 1, 77, 254, 255)) assertEquals(gray(v), Rcas.pixel(gray(v), gray(v), gray(v), gray(v), gray(v), 1f))
    }

    @Test
    fun strengthZeroLeavesEveryPixelAsItWas() {
        for (x in crosses()) assertEquals(x[2], Rcas.pixel(x[0], x[1], x[2], x[3], x[4], 0f))
        val w = 37
        val h = 23
        val src = noise(w, h, 3)
        assertArrayEquals(src, sharpened(src, w, h, 0f, bands = 4))
    }

    @Test
    fun onlyOpaqueCrossesChange() {
        val random = Random(2)
        repeat(1000) {
            val x = IntArray(5) { opaque(random) }
            val k = random.nextInt(5)
            x[k] = x[k] and 0x00FFFFFF or (random.nextInt(255) shl 24)
            assertEquals(x[2], Rcas.pixel(x[0], x[1], x[2], x[3], x[4], 1f))
        }
    }

    @Test
    fun strongerSharpensMoreOnAnEdge() {
        val gray = { v: Int -> (0xFF shl 24) or (v * 0x010101) }
        // The darker side of an edge: its right neighbor is light.
        val at = { s: Int -> Rcas.pixel(gray(60), gray(60), gray(60), gray(200), gray(60), Rcas.amount(s)) and 0xFF }
        val values = listOf(0, 25, 50, 75, 100).map(at)
        assertEquals(60, values.first())
        assertTrue("$values", values.zipWithNext().all { (a, b) -> b <= a } && values.last() < 60)
    }

    private fun noise(w: Int, h: Int, seed: Int): IntArray {
        val random = Random(seed)
        return IntArray(w * h) { opaque(random) }
    }

    /** [src] sharpened through [Rcas.sharpen] in [bands] bands on [threads] threads, with [rim]. */
    private fun sharpened(
        src: IntArray, w: Int, h: Int, amount: Float, bands: Int = 1, threads: Int = 1, rim: Rcas.Rim<IntArray> = Rcas.Rim()
    ): IntArray {
        val img = src.copyOf()
        Rcas.sharpen(
            w, h, amount, { y, out -> synchronized(img) { System.arraycopy(img, y * w, out, 0, w) } },
            { y, row -> synchronized(img) { System.arraycopy(row, 0, img, y * w, w) } }, rim, bands, threads
        )
        return img
    }

    /** Each pixel through [Rcas.pixel] with its neighbors from [src] itself, edges repeating. */
    private fun direct(src: IntArray, w: Int, h: Int, amount: Float): IntArray = IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        val at = { xx: Int, yy: Int -> src[yy.coerceIn(0, h - 1) * w + xx.coerceIn(0, w - 1)] }
        Rcas.pixel(at(x, y - 1), at(x - 1, y), at(x, y), at(x + 1, y), at(x, y + 1), amount)
    }

    @Test
    fun bandsInAnyNumberAndThreadsGiveTheSamePixels() {
        val w = 41
        val h = 57
        val src = noise(w, h, 5)
        val want = direct(src, w, h, 0.8f)
        for (bands in listOf(1, 2, 3, 7, 19, 57)) {
            for (threads in listOf(1, 4)) {
                assertArrayEquals("$bands bands on $threads", want, sharpened(src, w, h, 0.8f, bands, threads))
            }
        }
        // One row, one column.
        assertArrayEquals(direct(src.copyOf(w), w, 1, 1f), sharpened(src.copyOf(w), w, 1, 1f))
        val column = IntArray(h) { src[it * w] }
        assertArrayEquals(direct(column, 1, h, 1f), sharpened(column, 1, h, 1f, bands = 5, threads = 2))
    }

    @Test
    fun aRimJoinsARegionToItsNeighborsSeamlessly() {
        val w = 30
        val h = 24
        val src = noise(w, h, 9)
        val whole = direct(src, w, h, 1f)
        // The region x 7 until 19, y 5 until 17, with the pixels around it as its rim.
        val x0 = 7
        val y0 = 5
        val rw = 12
        val rh = 12
        val region = IntArray(rw * rh) { src[(y0 + it / rw) * w + x0 + it % rw] }
        val rim = Rcas.Rim(
            top = IntArray(rw) { src[(y0 - 1) * w + x0 + it] },
            bottom = IntArray(rw) { src[(y0 + rh) * w + x0 + it] },
            left = IntArray(rh) { src[(y0 + it) * w + x0 - 1] },
            right = IntArray(rh) { src[(y0 + it) * w + x0 + rw] }
        )
        val got = sharpened(region, rw, rh, 1f, bands = 3, threads = 2, rim = rim)
        for (i in got.indices) assertEquals("at $i", whole[(y0 + i / rw) * w + x0 + i % rw], got[i])
        // Without a rim the region's edges differ from the whole's.
        val plain = sharpened(region, rw, rh, 1f)
        assertTrue((0 until rw).any { plain[it] != whole[y0 * w + x0 + it] })
    }

    /**
     * The digest the host test of the native pass (`src/test/cpp/rcas_test.c`) also checks:
     * the same image, made the same way, sharpened at the same strengths, hashed in memory
     * order (R, G, B, A), so equal digests mean bit-identical results.
     */
    @Test
    fun matchesTheNativeHostTestsDigest() {
        val w = 61
        val h = 37
        var s = 12345
        val src = IntArray(w * h) { i ->
            s = s * 1664525 + 1013904223
            val y = i / w
            if (y % 9 == 4) return@IntArray 0xFF808080.toInt()
            val a = if (s and 0x3F == 0) 0x80 else 0xFF
            (a shl 24) or (((s ushr 8) and 0xFF) shl 16) or (((s ushr 16) and 0xFF) shl 8) or (s ushr 24)
        }
        var hash = 0x811C9DC5.toInt()
        for (amount in listOf(0.35f, 1f)) {
            for (p in sharpened(src, w, h, amount, bands = 3)) {
                for (byte in intArrayOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF, p ushr 24)) hash = (hash xor byte) * 0x01000193
            }
        }
        assertEquals(NATIVE_DIGEST, hash)
    }

    private companion object {
        /** FNV-1a 32 of [matchesTheNativeHostTestsDigest]'s results; `rcas_test.c` holds the same. */
        const val NATIVE_DIGEST = 0x883E28A7.toInt()
    }
}
