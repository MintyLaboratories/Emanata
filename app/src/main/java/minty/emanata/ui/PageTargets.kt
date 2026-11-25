package minty.emanata.ui

import minty.emanata.reader.Fit
import minty.emanata.reader.Scaling
import minty.emanata.reader.Target
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The exact size each page bitmap is decoded to, so that at rest it is drawn pixel
 * for pixel with no GPU scaling. Mirrors how [PageAdapter] lays its items out; kept
 * free of Android types so it can be unit-tested.
 */
object PageTargets {

    /** Where a page sits in its item: alone, or one half of a two-page spread row. */
    enum class Slot { ALONE, LEFT, RIGHT }

    /**
     * `[width, height]` of the image view showing a page in [slot] of an [itemW] x [itemH]
     * item. A paged single page's container is padded by [spacingPx] on every side; a
     * continuous column's page takes the whole width, its gap below it ([stripHeight]); a
     * spread row is not padded, and splits its width the way equal
     * [android.widget.LinearLayout] weights do, the left half taking the floor. A lone
     * page of a spread takes the whole row.
     */
    fun viewSize(mode: PageAdapter.Mode, spread: Boolean, itemW: Int, itemH: Int, spacingPx: Int, slot: Slot): IntArray {
        val size = when {
            slot == Slot.LEFT -> intArrayOf(itemW / 2, itemH)
            slot == Slot.RIGHT -> intArrayOf(itemW - itemW / 2, itemH)
            spread || mode == PageAdapter.Mode.CONTINUOUS -> intArrayOf(itemW, itemH)
            else -> intArrayOf(itemW - 2 * spacingPx, itemH - 2 * spacingPx)
        }
        return intArrayOf(size[0].coerceAtLeast(1), size[1].coerceAtLeast(1))
    }

    /**
     * What a page shown in a [viewW] x [viewH] view needs for [fit]: the box [ZoomImageView]
     * fits it into, so its fitted scale comes out at exactly 1. A continuous column always
     * fits the width. A page too large for one bitmap is drawn in bands ([Target.banded]).
     */
    fun target(
        mode: PageAdapter.Mode,
        fit: ZoomImageView.FitMode,
        viewW: Int,
        viewH: Int,
        autoCrop: Boolean = false,
        linear: Boolean = false,
        scaling: Scaling = Scaling.PLAIN
    ): Target = when {
        mode == PageAdapter.Mode.CONTINUOUS -> Target(viewW, 0, autoCrop = autoCrop, linear = linear, scaling = scaling, banded = true)
        fit == ZoomImageView.FitMode.WIDTH -> Target(viewW, 0, autoCrop = autoCrop, linear = linear, scaling = scaling, banded = true)
        fit == ZoomImageView.FitMode.HEIGHT -> Target(0, viewH, autoCrop = autoCrop, linear = linear, scaling = scaling, banded = true)
        fit == ZoomImageView.FitMode.STRETCH -> Target(viewW, viewH, Fit.STRETCH, autoCrop, linear, scaling = scaling)
        else -> Target(viewW, viewH, autoCrop = autoCrop, linear = linear, scaling = scaling, banded = true)
    }

    /**
     * The one height the two pages of an [itemW] x [itemH] spread row show at with fit
     * screen: the tallest at which each, of width / height [leftAspect] and [rightAspect],
     * fits its half of the row ([viewSize]). Whole pixels, so a page decoded into its half
     * at that height comes out exactly that tall.
     */
    fun pairHeight(itemW: Int, itemH: Int, leftAspect: Double, rightAspect: Double): Int {
        val left = itemW / 2
        val right = itemW - left
        return floor(minOf(itemH.toDouble(), left / leftAspect, right / rightAspect)).toInt().coerceAtLeast(1)
    }

    /**
     * Height of a continuous column's item [width] wide showing a page of height / width
     * [aspect], with the page gap [gapPx] below it: the page's height rounded, so a page
     * decoded at that width gets back its exact bitmap height.
     */
    fun stripHeight(width: Int, aspect: Float, gapPx: Int = 0): Int =
        (width * aspect).roundToInt().coerceAtLeast(1) + gapPx.coerceAtLeast(0)
}
