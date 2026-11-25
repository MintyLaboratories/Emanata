package minty.emanata.debug

import android.app.Application
import android.graphics.Bitmap
import minty.emanata.ai.Dims
import minty.emanata.ai.ShaderChain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The synthetic chain the GLES runner is checked against on the device, and its CPU arithmetic. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShaderReferenceTest {

    @Test
    fun theChainPlansAsTheReferenceComputesIt() {
        val chain = ShaderChain.parse(listOf(ShaderReference.CHAIN), 2)
        assertEquals(listOf("edge", "luma", "up", "darken", "clamp"), chain.programs.map { it.pass.desc })
        assertEquals(listOf(4, 1, 4, 4, 4), chain.programs.map { it.components })
        assertEquals(listOf("HOOKED", "luma", "MAIN"), chain.programs[4].bindings)
        assertEquals(Dims(16, 12), chain.plan(8, 6).output)
    }

    @Test
    fun aFlatPageComesOutFlatAtTheValueWorkedByHand() {
        val page = Bitmap.createBitmap(6, 5, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF808080.toInt()) }
        val out = ShaderReference.render(page)
        assertEquals(12, out.width)
        assertEquals(10, out.height)
        // m = 128/255; darkened 0.9 m under the clamp at m + 0.25; 0.7 of it, 0.2 of m and 0.1 of its luma m: 119.04 codes.
        val px = IntArray(120).also { out.getPixels(it, 0, 12, 0, 0, 12, 10) }
        assertTrue(px.all { it == 0xFF777777.toInt() })
    }

    @Test
    fun anEdgeShowsOnTheSideItsOffsetsRead() {
        // White from column 3 on: only columns whose right neighbor is white (2) carry the step, at 2x.
        val page = Bitmap.createBitmap(6, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF000000.toInt()) }
        for (y in 0 until 4) for (x in 3 until 6) page.setPixel(x, y, 0xFFFFFFFF.toInt())
        val out = ShaderReference.render(page)
        val flat = ShaderReference.render(Bitmap.createBitmap(6, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF000000.toInt()) })
        // Column pairs 0-1 see black only; pair 4-5 (input column 2) picks up the step.
        assertEquals(flat.getPixel(1, 4), out.getPixel(1, 4))
        assertTrue(out.getPixel(4, 4) != flat.getPixel(4, 4))
    }

    @Test
    fun comparingSaysHowFarApart() {
        val a = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF102030.toInt()) }
        val same = ShaderReference.compare(a, a.copy(Bitmap.Config.ARGB_8888, true))
        assertEquals(0, same.maxDiff)
        assertEquals(ShaderReference.PSNR_EQUAL, same.psnr, 0.0)
        val b = a.copy(Bitmap.Config.ARGB_8888, true).apply { setPixel(1, 1, 0xFF102036.toInt()) }
        val c = ShaderReference.compare(a, b)
        assertEquals(6, c.maxDiff)
        assertTrue(c.psnr in 30.0..60.0)
    }
}
