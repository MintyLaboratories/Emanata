package minty.emanata.files

import minty.emanata.reader.ImageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** This device's Convert speeds: seeds before anything is measured, learning, the record kept. */
class WorkRatesTest {
    private val jpeg = ImageKind.JPEG
    private val mb = 1L shl 20

    @Test
    fun beforeAnyJobTheDefaultsScaledByTheDevicesSpeedApply() {
        val rates = WorkRates()
        assertEquals(WorkRates.defaultSeconds(Step.COMPACT, jpeg), rates.secondsPerPage(Step.COMPACT, jpeg, SizeClass.MEDIUM), 1e-9)
        assertEquals(WorkRates.defaultSeconds(Step.COMPACT, jpeg) * SizeClass.LARGE.scale, rates.secondsPerPage(Step.COMPACT, jpeg, SizeClass.LARGE), 1e-9)
        val slow = WorkRates(WorkRates.Seeds(speed = { 2.0 }))
        assertEquals(2 * WorkRates.defaultSeconds(Step.COMPACT, jpeg), slow.secondsPerPage(Step.COMPACT, jpeg, SizeClass.MEDIUM), 1e-9)
        assertEquals(WorkRates.DEVICE_READ / 2, slow.readRate(WorkRates.DEVICE, Container.ZIP), 1.0)
        assertEquals(WorkRates.BOOK_SECONDS, rates.bookSeconds("compact"), 1e-9)
    }

    @Test
    fun formatsAStepLeavesAsTheyAreTakeAGlance() {
        val rates = WorkRates()
        assertTrue(rates.secondsPerPage(Step.COMPACT, ImageKind.AVIF, SizeClass.LARGE) < 0.1)
        assertTrue(rates.secondsPerPage(Step.COMPATIBLE, jpeg, SizeClass.LARGE) < 0.1)
        assertTrue(rates.secondsPerPage(Step.COMPATIBLE, ImageKind.JXL, SizeClass.MEDIUM) > 0.1)
    }

    @Test
    fun whatWasMeasuredOutweighsTheSeedAsItGrows() {
        val rates = WorkRates()
        val seed = rates.secondsPerPage(Step.COMPACT, jpeg, SizeClass.MEDIUM)
        rates.page(Step.COMPACT, jpeg, 2 * mb, 2.0)
        val one = rates.secondsPerPage(Step.COMPACT, jpeg, SizeClass.MEDIUM)
        assertTrue(one > seed && one < 2.0)
        repeat(300) { rates.page(Step.COMPACT, jpeg, 2 * mb, 2.0) }
        assertEquals(2.0, rates.secondsPerPage(Step.COMPACT, jpeg, SizeClass.MEDIUM), 0.05)
    }

    @Test
    fun onlyRecentWorkCounts() {
        val rates = WorkRates()
        repeat(300) { rates.page(Step.COMPACT, jpeg, 2 * mb, 1.0) }
        // The device got slower (warmer, or busier): within a window it shows.
        repeat(900) { rates.page(Step.COMPACT, jpeg, 2 * mb, 3.0) }
        assertTrue(rates.secondsPerPage(Step.COMPACT, jpeg, SizeClass.MEDIUM) > 2.5)
    }

    @Test
    fun aSizeClassNotMeasuredFollowsOneThatWas() {
        val rates = WorkRates()
        repeat(10) { rates.page(Step.COMPACT, jpeg, 2 * mb, 4.0) }
        val large = rates.secondsPerPage(Step.COMPACT, jpeg, SizeClass.LARGE)
        assertEquals(4.0 * SizeClass.LARGE.scale / SizeClass.MEDIUM.scale, large, 0.01)
        // A lossless page's bytes say how well it compresses, not how many pixels it holds.
        repeat(10) { rates.page(Step.COMPACT, ImageKind.PNG, 2 * mb, 4.0) }
        assertEquals(4.0, rates.secondsPerPage(Step.COMPACT, ImageKind.PNG, SizeClass.SMALL), 0.01)
        assertEquals(WorkRates.defaultSeconds(Step.COMPACT, ImageKind.JXL), WorkRates().secondsPerPage(Step.COMPACT, ImageKind.JXL, SizeClass.SMALL), 1e-9)
    }

    @Test
    fun readsAndWritesAreKeptByLocationAndContainer() {
        val rates = WorkRates()
        repeat(20) { rates.read(WorkRates.DEVICE, Container.RAR, 64 * mb, 4.0) }
        assertEquals(16.0 * mb, rates.readRate(WorkRates.DEVICE, Container.RAR), 0.03 * 16 * mb)
        // Other containers and locations keep their seeds.
        assertEquals(WorkRates.DEVICE_READ, rates.readRate(WorkRates.DEVICE, Container.ZIP), 1.0)
        assertEquals(WorkRates.SHARE_READ, rates.readRate(WorkRates.share("a"), Container.ZIP), 1.0)
        repeat(20) { rates.written(WorkRates.DEVICE, "compact", 64 * mb, 1.0) }
        assertEquals(64.0 * mb, rates.writeRate(WorkRates.DEVICE, "compact"), 0.03 * 64 * mb)
        // Pack's copies, reading loose images as they write, are kept apart.
        assertEquals(WorkRates.DEVICE_WRITE, rates.writeRate(WorkRates.DEVICE, "pack"), 1.0)
    }

    @Test
    fun theBytesEachActionWritesPerByteOfBookAreLearned() {
        val rates = WorkRates()
        assertEquals(0.8, rates.outputShare("compact"), 1e-9)
        assertEquals(1.0, rates.outputShare("repack"), 1e-9)
        repeat(20) { rates.output("compact", 100 * mb, 55 * mb) }
        assertEquals(0.55, rates.outputShare("compact"), 0.01)
        // A book left as it was wrote nothing.
        rates.output("repack", 100 * mb, 0)
        assertTrue(rates.outputShare("repack") < 0.5)
    }

    @Test
    fun aSharesMeasuredSpeedSeedsItsReadsAndWrites() {
        val rates = WorkRates(WorkRates.Seeds(shareRead = { id -> if (id == "a") 10e6 else null }))
        assertEquals(10e6, rates.readRate(WorkRates.share("a"), Container.ZIP), 1.0)
        assertEquals(10e6 * WorkRates.SHARE_WRITE_SHARE, rates.writeRate(WorkRates.share("a"), "compact"), 1.0)
        assertEquals(WorkRates.SHARE_READ, rates.readRate(WorkRates.share("b"), Container.ZIP), 1.0)
        // A solid archive unpacks slower than a fast share reads.
        val fast = WorkRates(WorkRates.Seeds(shareRead = { 500e6 }))
        assertEquals(WorkRates.SEVEN_ZIP_READ, fast.readRate(WorkRates.share("a"), Container.SEVEN_ZIP), 1.0)
    }

    @Test
    fun theRecordReadsBackAsItWasWritten() {
        val rates = WorkRates()
        repeat(5) { rates.page(Step.upscale("Model 2x"), ImageKind.PNG, 3 * mb, 12.0) }
        rates.read(WorkRates.share("x=1"), Container.ZIP, 10 * mb, 2.0)
        rates.book("repack", 0.4)
        val back = WorkRates.decode(rates.encode() + "\nbroken line\nkey=nope,1\n")
        assertEquals(rates.encode(), back.encode())
        assertEquals(rates.secondsPerPage(Step.upscale("Model 2x"), ImageKind.PNG, SizeClass.MEDIUM), back.secondsPerPage(Step.upscale("Model 2x"), ImageKind.PNG, SizeClass.MEDIUM), 1e-9)
        assertEquals(rates.readRate(WorkRates.share("x=1"), Container.ZIP), back.readRate(WorkRates.share("x=1"), Container.ZIP), 1e-6)
        assertEquals("upscale:model_2x", Step.upscale("Model 2x").key)
    }

    @Test
    fun calibrationTimingsGiveTheDevicesSpeed() {
        assertEquals(1.0, WorkRates.speedOf(null, null), 0.0)
        assertEquals(1.0, WorkRates.speedOf(WorkRates.REF_JPEG_MS, WorkRates.REF_JXL_CPU_MS), 1e-9)
        assertEquals(2.0, WorkRates.speedOf(2 * WorkRates.REF_JPEG_MS, null), 1e-9)
        assertEquals(1.5, WorkRates.speedOf(2 * WorkRates.REF_JPEG_MS, WorkRates.REF_JXL_CPU_MS), 1e-9)
        assertEquals(8.0, WorkRates.speedOf(1000 * WorkRates.REF_JPEG_MS, null), 0.0)
    }

    @Test
    fun aBooksMeterFeedsTheRatesAndTellsTheTimeBeyondItsParts() {
        val rates = WorkRates()
        val meter = BookMeter(rates, ConvertOp.COMPACT, WorkRates.DEVICE, Container.ZIP) { true }
        val jpegPage = ByteArray(2 * mb.toInt()).also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[2] = 0xFF.toByte() }
        repeat(20) { meter.page(jpegPage, 1.0) }
        meter.read(40 * mb, 4.0)
        meter.written(30 * mb, 2.0)
        assertTrue(rates.secondsPerPage(Step.COMPACT, jpeg, SizeClass.MEDIUM) > 0.8)
        // Converting (20 s) outlasted reading (4 s), which ran beside it.
        assertEquals(3.0, meter.beyond(25.0), 1e-9)
        assertEquals(0.0, meter.beyond(10.0), 0.0)
        repeat(30) { meter.book(40 * mb, 25.0) }
        assertEquals(3.0, rates.bookSeconds("compact"), 0.3)
        assertEquals(0.75, rates.outputShare("compact"), 0.02)
    }

    @Test
    fun withTheAppAwayOnlyHowMuchSlowerItRanIsLearned() {
        val rates = WorkRates()
        assertEquals(WorkRates.BACK_SLOWDOWN, rates.slowdown(), 0.0)
        val page = rates.secondsPerPage(Step.COMPACT, jpeg, SizeClass.MEDIUM)
        val meter = BookMeter(rates, ConvertOp.COMPACT, WorkRates.DEVICE, Container.ZIP) { false }
        val jpegPage = ByteArray(2 * mb.toInt()).also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[2] = 0xFF.toByte() }
        // Pages twice as slow as the figures say.
        repeat(20000) { meter.page(jpegPage, 2 * page) }
        meter.read(mb, 2 * mb / rates.readRate(WorkRates.DEVICE, Container.ZIP))
        assertEquals(page, rates.secondsPerPage(Step.COMPACT, jpeg, SizeClass.MEDIUM), 1e-9)
        assertEquals(WorkRates.DEVICE_READ, rates.readRate(WorkRates.DEVICE, Container.ZIP), 1.0)
        assertEquals(2.0, rates.slowdown(), 0.1)
    }

    @Test
    fun lossyWebpIsKeptApartFromWebpCompactConverts() {
        val rates = WorkRates()
        assertTrue(rates.secondsPerPage(Step.COMPACT, ImageKind.WEBP, SizeClass.MEDIUM, lossy = true) < 0.1)
        assertTrue(rates.secondsPerPage(Step.COMPACT, ImageKind.WEBP, SizeClass.MEDIUM) > 0.1)
        val meter = BookMeter(rates, ConvertOp.COMPACT, WorkRates.DEVICE, Container.ZIP) { true }
        repeat(50) { meter.page(webp("VP8 "), 0.02) }
        repeat(50) { meter.page(webp("VP8L"), 3.0) }
        assertEquals(0.02, rates.secondsPerPage(Step.COMPACT, ImageKind.WEBP, SizeClass.SMALL, lossy = true), 0.05)
        assertEquals(3.0, rates.secondsPerPage(Step.COMPACT, ImageKind.WEBP, SizeClass.SMALL), 0.3)
        // A JPEG XL holding a JPEG is kept apart too.
        assertTrue(rates.secondsPerPage(Step.COMPATIBLE, ImageKind.JXL, SizeClass.MEDIUM, lossy = true) < rates.secondsPerPage(Step.COMPATIBLE, ImageKind.JXL, SizeClass.MEDIUM) / 10)
        // The flag means nothing for other formats.
        assertEquals(rates.secondsPerPage(Step.COMPACT, jpeg, SizeClass.SMALL), rates.secondsPerPage(Step.COMPACT, jpeg, SizeClass.SMALL, lossy = true), 0.0)
    }

    /** A WebP whose first chunk is [chunk]. */
    private fun webp(chunk: String) = "RIFF".toByteArray() + ByteArray(4) + "WEBP$chunk".toByteArray() + byteArrayOf(10, 0, 0, 0) + ByteArray(10)

    @Test
    fun containersAndSizeClassesByName() {
        assertEquals(Container.ZIP, Container.of("A.cbz", false))
        assertEquals(Container.RAR, Container.of("A.CBR", false))
        assertEquals(Container.SEVEN_ZIP, Container.of("A.cb7", false))
        assertEquals(Container.FOLDER, Container.of("A.cbz", true))
        assertEquals(SizeClass.SMALL, SizeClass.of(SizeClass.MEDIUM_FROM - 1))
        assertEquals(SizeClass.MEDIUM, SizeClass.of(SizeClass.MEDIUM_FROM))
        assertEquals(SizeClass.LARGE, SizeClass.of(SizeClass.LARGE_FROM))
    }
}
