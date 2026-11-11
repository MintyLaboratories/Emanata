package com.infinity.comicreader.files

import com.infinity.comicreader.reader.Calibration
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The device's speed seeds from the calibration kept. Robolectric for org.json. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ConvertRatesTest {
    private fun kept(version: Int, jpegMs: Double, jxlCpuMs: Double) = JSONObject()
        .put("fingerprint", Calibration.fingerprint(version, "brand/device:16/BUILD1"))
        .put("timings", JSONArray()
            .put(JSONObject().put("setup", "jpeg/big").put("wallMs", jpegMs).put("cpuMs", jpegMs))
            .put(JSONObject().put("setup", "jxl/4/big").put("wallMs", 60.0).put("cpuMs", jxlCpuMs)))

    @Test
    fun aCalibrationOfThisVersionGivesTheSpeed() {
        val k = kept(Calibration.VERSION, 2 * WorkRates.REF_JPEG_MS, 2 * WorkRates.REF_JXL_CPU_MS)
        assertEquals(2.0, ConvertRates.calibrationSpeed(k), 1e-9)
    }

    @Test
    fun resamplingTimingsLeaveTheSpeedAlone() {
        val k = kept(Calibration.VERSION, 2 * WorkRates.REF_JPEG_MS, 2 * WorkRates.REF_JXL_CPU_MS)
        val timings = k.getJSONArray("timings")
        for (setup in listOf("pixels/gpu/any", "pixels/gpu/big", "pixels/8/any")) {
            timings.put(JSONObject().put("setup", setup).put("wallMs", 1.0).put("cpuMs", 1.0))
        }
        assertEquals(2.0, ConvertRates.calibrationSpeed(k), 1e-9)
    }

    @Test
    fun oneOfAnotherVersionTimedOtherPagesAndCountsForNothing() {
        assertEquals(1.0, ConvertRates.calibrationSpeed(kept(Calibration.VERSION - 1, 27.0, 190.0)), 0.0)
        assertEquals(1.0, ConvertRates.calibrationSpeed(null), 0.0)
    }
}
