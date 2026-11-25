package minty.emanata.ui

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * A panel's print look: flat [fill], and halftone dots in a darker [dot] shade on a screen
 * turned [angle] degrees, growing along [dirX], [dirY] across the panel.
 */
data class PanelInk(val fill: Int, val dot: Int, val angle: Double, val dirX: Double, val dirY: Double)

/** A page's print style: [paper] (the gutters), [ink] (the borders) and each panel's [panels]. */
data class PrintStyle(val paper: Int, val ink: Int, val panels: List<PanelInk>) {
    companion object {
        /** Four-color process print: process colors, screens at print angles (C 15, M 75, Y 0, K 45 degrees). */
        val FOUR_COLOR = PrintStyle(
            paper = 0xFFFFFFFF.toInt(),
            ink = 0xFF17181C.toInt(),
            panels = listOf(
                PanelInk(0xFF00A3E0.toInt(), 0xFF00658C.toInt(), 15.0, 0.2, 1.0),
                PanelInk(0xFFFFE23A.toInt(), 0xFFC79A00.toInt(), 0.0, 1.0, 0.3),
                PanelInk(0xFFE8007F.toInt(), 0xFF8F004D.toInt(), 75.0, -0.4, 1.0),
                PanelInk(0xFF3BAB4A.toInt(), 0xFF17181C.toInt(), 45.0, 1.0, 1.0),
                PanelInk(0xFFFF5A26.toInt(), 0xFF9E2A07.toInt(), 75.0, 0.0, -1.0)
            )
        )
    }
}

/** Halftone dots for one panel on a page [size] pixels square, fixed to the page. */
object Halftone {
    /** A dot at [x], [y] of radius [r], in page pixels. */
    data class Dot(val x: Float, val y: Float, val r: Float)

    /** The screen's pitch on a page [size] pixels square: 62 dots across, at least 6 px apart. */
    fun step(size: Int): Double = max(6.0, size / 62.0)

    /**
     * The dots of [ink]'s screen on a page [size] pixels square that fall within [box]
     * (left, top, right, bottom in page pixels, a pitch of slack around it): the screen turns
     * about the page's middle, and each dot's radius grows from 8 % to half the pitch along
     * the ink's direction, half grown at the panel's resting middle ([centerX], [centerY], on
     * the unit page).
     */
    fun dots(size: Int, ink: PanelInk, centerX: Double, centerY: Double, box: FloatArray): List<Dot> {
        val step = step(size)
        val a = Math.toRadians(ink.angle)
        val ca = cos(a)
        val sa = sin(a)
        val len = hypot(ink.dirX, ink.dirY).takeIf { it > 0 } ?: 1.0
        val dx = ink.dirX / len
        val dy = ink.dirY / len
        val reach = size * 0.75
        val half = size / 2.0
        val out = ArrayList<Dot>()
        var u = -reach
        while (u <= reach) {
            var v = -reach
            while (v <= reach) {
                val x = half + u * ca - v * sa
                val y = half + u * sa + v * ca
                if (x >= box[0] - step && y >= box[1] - step && x <= box[2] + step && y <= box[3] + step) {
                    val k = (0.5 + 2.4 * ((x / size - centerX) * dx + (y / size - centerY) * dy)).coerceIn(0.0, 1.0)
                    out += Dot(x.toFloat(), y.toFloat(), (step * (0.08 + 0.42 * k)).toFloat())
                }
                v += step
            }
            u += step
        }
        return out
    }
}
