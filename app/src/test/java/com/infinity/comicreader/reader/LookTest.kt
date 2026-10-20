package com.infinity.comicreader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.random.Random

class LookTest {

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun lab(argb: Int): FloatArray =
        Oklab.fromSrgb(((argb shr 16) and 0xFF) / 255f, ((argb shr 8) and 0xFF) / 255f, (argb and 0xFF) / 255f)

    private fun chroma(lab: FloatArray) = hypot(lab[1], lab[2])

    private fun hue(lab: FloatArray) = atan2(lab[2], lab[1])

    private fun hueDistance(a: Float, b: Float): Float {
        val d = abs(a - b) % (2 * PI).toFloat()
        return minOf(d, (2 * PI).toFloat() - d)
    }

    private fun colors(n: Int, seed: Int): List<Int> {
        val random = Random(seed)
        return List(n) { argb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
    }

    @Test
    fun knownColorsMatchPublishedOklabValues() {
        val white = Oklab.fromSrgb(1f, 1f, 1f)
        assertEquals(1f, white[0], 1e-4f)
        assertEquals(0f, white[1], 1e-4f)
        assertEquals(0f, white[2], 1e-4f)
        val red = Oklab.fromSrgb(1f, 0f, 0f)
        assertEquals(0.6280f, red[0], 1e-3f)
        assertEquals(0.2249f, red[1], 1e-3f)
        assertEquals(0.1258f, red[2], 1e-3f)
        val black = Oklab.fromSrgb(0f, 0f, 0f)
        assertEquals(0f, black[0], 1e-6f)
    }

    @Test
    fun oklabRoundTripsSrgb() {
        val random = Random(3)
        repeat(2000) {
            val c = FloatArray(3) { random.nextFloat() }
            val lab = Oklab.fromSrgb(c[0], c[1], c[2])
            val back = Oklab.toSrgb(lab[0], lab[1], lab[2])
            for (i in 0..2) assertEquals(c[i], back[i], 2e-4f)
        }
    }

    @Test
    fun neutralLookIsTheIdentity() {
        for (r in 0..255 step 17) for (g in 0..255 step 17) for (b in 0..255 step 17) {
            val c = argb(r, g, b)
            assertEquals(c, Look(null, false, Adjustments.NEUTRAL).apply(c))
        }
        for (c in colors(500, 4)) assertEquals(c, Look(null, false, Adjustments.NEUTRAL).apply(c))
        assertTrue(Look.NONE.plain)
        assertTrue(Look.of(Filters.SEPIA, Adjustments.NEUTRAL).plain)
        assertFalse(Look.of(Filters.INVERT, Adjustments.NEUTRAL).plain)
        assertFalse(Look.of(Filters.NONE, Adjustments(contrast = 1)).plain)
    }

    @Test
    fun alphaIsKept() {
        val c = (0x80 shl 24) or 0x336699
        assertEquals(0x80, Look.of(Filters.INVERT, Adjustments(brightness = 30)).apply(c) ushr 24)
        assertEquals(0, Look.of(Filters.INVERT, Adjustments.NEUTRAL).apply(0))
    }

    @Test
    fun invertFlipsLightnessAndKeepsHue() {
        val invert = Look.of(Filters.INVERT, Adjustments.NEUTRAL)
        assertEquals(argb(0, 0, 0), invert.apply(argb(255, 255, 255)))
        assertEquals(argb(255, 255, 255), invert.apply(argb(0, 0, 0)))
        for (c in colors(400, 5)) {
            val before = lab(c)
            val after = lab(invert.apply(c))
            // Lightness flips wherever the gamut leaves room for the color's chroma.
            if (chroma(after) > chroma(before) * 0.98f) assertEquals(1f - before[0], after[0], 0.01f)
            if (chroma(before) > 0.05f && chroma(after) > 0.05f) {
                assertTrue("hue of ${Integer.toHexString(c)}", hueDistance(hue(before), hue(after)) < 0.05f)
            }
        }
    }

    @Test
    fun invertIsNotTheRgbInvert() {
        // RGB invert turns blue (dark) into yellow (light): another hue. OKLab keeps it blue.
        val blue = argb(0, 0, 200)
        val out = lab(Look.of(Filters.INVERT, Adjustments.NEUTRAL).apply(blue))
        assertTrue(hueDistance(hue(lab(blue)), hue(out)) < 0.05f)
        assertTrue(out[0] > lab(blue)[0])
    }

    @Test
    fun brightnessBlendsTowardBlackOrWhite() {
        assertEquals(argb(0, 0, 0), Look.of(Filters.NONE, Adjustments(brightness = -100)).apply(argb(200, 40, 90)))
        assertEquals(argb(255, 255, 255), Look.of(Filters.NONE, Adjustments(brightness = 100)).apply(argb(20, 140, 90)))
        for (c in colors(200, 6)) {
            val before = lab(c)
            val darker = lab(Look.of(Filters.NONE, Adjustments(brightness = -50)).apply(c))
            assertEquals(before[0] * 0.5f, darker[0], 0.01f)
            assertEquals(chroma(before) * 0.5f, chroma(darker), 0.01f)
            val lighter = lab(Look.of(Filters.NONE, Adjustments(brightness = 50)).apply(c))
            assertEquals(before[0] + (1f - before[0]) * 0.5f, lighter[0], 0.01f)
            if (chroma(before) > 0.05f) assertTrue(hueDistance(hue(before), hue(lighter)) < 0.05f)
        }
    }

    @Test
    fun contrastScalesLightnessAboutMidGray() {
        val gray = Oklab.toSrgb(0.5f, 0f, 0f)
        val mid = argb((gray[0] * 255 + 0.5f).toInt(), (gray[1] * 255 + 0.5f).toInt(), (gray[2] * 255 + 0.5f).toInt())
        for (c in colors(100, 7)) assertEquals(mid, Look.of(Filters.NONE, Adjustments(contrast = -100)).apply(c))

        val half = Look.of(Filters.NONE, Adjustments(contrast = -50))
        val double = Look.of(Filters.NONE, Adjustments(contrast = 50))
        for (v in listOf(60, 100, 160, 210)) {
            val l = lab(argb(v, v, v))[0]
            assertEquals(0.5f + (l - 0.5f) * 0.5f, lab(half.apply(argb(v, v, v)))[0], 0.01f)
            assertEquals((0.5f + (l - 0.5f) * 2f).coerceIn(0f, 1f), lab(double.apply(argb(v, v, v)))[0], 0.01f)
        }
    }

    @Test
    fun fullContrastIsNearThresholdButFinite() {
        val k = Adjustments(contrast = 100).contrastFactor
        assertTrue(k.isFinite())
        assertEquals(50f, k, 0.01f)
        val full = Look.of(Filters.NONE, Adjustments(contrast = 100))
        assertEquals(argb(0, 0, 0), full.apply(argb(90, 90, 90)))
        assertEquals(argb(255, 255, 255), full.apply(argb(140, 140, 140)))
        assertEquals(1f, Adjustments(contrast = 0).contrastFactor)
        assertEquals(0f, Adjustments(contrast = -100).contrastFactor)
    }

    @Test
    fun saturationScalesChromaWithinTheGamut() {
        for (c in colors(300, 8)) {
            val before = lab(c)
            val gray = lab(Look.of(Filters.NONE, Adjustments(saturation = -100)).apply(c))
            assertEquals(before[0], gray[0], 0.01f)
            assertEquals(0f, chroma(gray), 0.01f)
            val more = lab(Look.of(Filters.NONE, Adjustments(saturation = 100)).apply(c))
            assertEquals(before[0], more[0], 0.01f)
            assertTrue(chroma(more) >= chroma(before) - 0.01f)
            assertTrue(chroma(more) <= chroma(before) * 2f + 0.01f)
            if (chroma(before) > 0.05f) assertTrue(hueDistance(hue(before), hue(more)) < 0.05f)
        }
        // A muted color has room to double.
        val muted = argb(140, 120, 110)
        assertEquals(chroma(lab(muted)) * 2f, chroma(lab(Look.of(Filters.NONE, Adjustments(saturation = 100)).apply(muted))), 0.004f)
    }

    @Test
    fun gamutClampKeepsLightnessAndHue() {
        val lab = Oklab.fromSrgb(0.1f, 0.9f, 0.2f)
        val wide = Look.gamut(lab[0], lab[1] * 3f, lab[2] * 3f)
        assertTrue(wide.all { it in 0f..1f })
        val back = Oklab.fromLinearRgb(wide[0], wide[1], wide[2])
        assertEquals(lab[0], back[0], 0.005f)
        assertTrue(hueDistance(hue(lab), hue(back)) < 0.02f)
        assertTrue(chroma(back) > chroma(lab))
    }

    @Test
    fun filterMatrixRunsBeforeTheAdjustments() {
        val c = argb(200, 60, 30)
        val grayAndBrighter = Look.of(Filters.GRAYSCALE, Adjustments(brightness = 20)).apply(c)
        val gray = Look.of(Filters.GRAYSCALE, Adjustments.NEUTRAL).apply(c)
        val expected = Look.of(Filters.NONE, Adjustments(brightness = 20)).apply(gray)
        for (shift in 0..16 step 8) {
            assertTrue(abs(((grayAndBrighter shr shift) and 0xFF) - ((expected shr shift) and 0xFF)) <= 1)
        }
        val v = (0.213f * 200 + 0.715f * 60 + 0.072f * 30 + 0.5f).toInt()
        assertEquals(argb(v, v, v), gray)
    }

    @Test
    fun valuesOutsideTheRangeAreClamped() {
        assertEquals(Adjustments(-100, 100, 0), Adjustments(-300, 250, 0).coerced())
        assertEquals(-1f, Adjustments(brightness = -400).brightnessAmount)
        assertEquals(2f, Adjustments(saturation = 400).saturationFactor)
    }

    @Test
    fun adjustmentsEqualToTheDefaultsAreNotTheBooksOwn() {
        val defaults = Adjustments(10, -20, 0)
        assertFalse(Adjustments(10, -20, 0).ownAgainst(defaults))
        assertTrue(Adjustments(10, -19, 0).ownAgainst(defaults))
        assertFalse(Adjustments(-300, 0, 0).ownAgainst(Adjustments(brightness = -100)))
        assertTrue(Adjustments.NEUTRAL.ownAgainst(defaults))
    }
}
