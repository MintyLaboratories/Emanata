package com.infinity.comicreader.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/** The load estimate: page times summed, learned ones first, on a 0-30 s scale read as light, moderate or heavy. */
class AiLoadTest {

    private val artifacts = ModelCatalog.forStep(AiStep.ARTIFACTS).first()
    private val descreen = ModelCatalog.forStep(AiStep.DESCREEN).first()
    private val upscale = ModelCatalog.byId(ModelCatalog.DEFAULT_UPSCALE)!!

    @Test
    fun aChainsLoadSumsEveryStep() {
        assertEquals(0L, AiLoad.pageMs(emptyList()))
        assertEquals(upscale.pageMs, AiLoad.pageMs(listOf(upscale)))
        assertEquals(artifacts.pageMs + descreen.pageMs + upscale.pageMs, AiLoad.pageMs(listOf(artifacts, descreen, upscale)))
    }

    @Test
    fun timesLearnedHereWinOverTheCatalogs() {
        val learned = mapOf(upscale.id to 1_000L)
        assertEquals(1_000L + descreen.pageMs, AiLoad.pageMs(listOf(descreen, upscale)) { learned[it.id] })
    }

    @Test
    fun theBarFillsTo30SecondsAndNoFurther() {
        assertEquals(0f, AiLoad.fraction(0), 0f)
        assertEquals(0.5f, AiLoad.fraction(15_000), 1e-6f)
        assertEquals(1f, AiLoad.fraction(30_000), 0f)
        assertEquals(1f, AiLoad.fraction(45_000), 0f)
        assertEquals(0f, AiLoad.fraction(-1), 0f)
    }

    @Test
    fun greenBelow10YellowTo20RedPast() {
        assertEquals(AiLoad.Level.LIGHT, AiLoad.level(0))
        assertEquals(AiLoad.Level.LIGHT, AiLoad.level(9_999))
        assertEquals(AiLoad.Level.MODERATE, AiLoad.level(10_000))
        assertEquals(AiLoad.Level.MODERATE, AiLoad.level(20_000))
        assertEquals(AiLoad.Level.HEAVY, AiLoad.level(20_001))
        assertEquals(AiLoad.Level.HEAVY, AiLoad.level(30_000))
        assertEquals(AiLoad.Level.HEAVY, AiLoad.level(90_000))
    }

    @Test
    fun theDefaultChainReadsLight() {
        assertEquals(AiLoad.Level.LIGHT, AiLoad.level(AiLoad.pageMs(listOf(upscale))))
    }
}
