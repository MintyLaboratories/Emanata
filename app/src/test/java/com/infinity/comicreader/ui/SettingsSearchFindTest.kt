package com.infinity.comicreader.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Settings search matches titles and summaries whatever the case and accents, titles first. */
class SettingsSearchFindTest {

    private fun entry(key: String, title: String, summary: String = "") = SettingEntry(key, title, title, summary, "Section A", advanced = false)

    private val entries = listOf(
        entry("a", "Pause background scans", "Folder facts wait"),
        entry("b", "Écran de veille"),
        entry("c", "Momentum scrolling", "Zoomed pages keep gliding after a FLICK"),
        entry("d", "Flick speed"),
        entry("e", "Page gap"),
    )

    private fun keys(query: String) = SettingsSearch.find(entries, query).map { it.key }

    @Test
    fun foldsCaseAccentsAndCompatibilityForms() {
        assertEquals("ecran", SettingsSearch.fold("ÉCRAN"))
        assertEquals("naive", SettingsSearch.fold("naïve"))
        assertEquals("fi", SettingsSearch.fold("ﬁ"))
    }

    @Test
    fun matchesWithoutCaseOrAccents() {
        assertEquals(listOf("b"), keys("ecran"))
        assertEquals(listOf("b"), keys("ÉCRAN"))
        assertEquals(listOf("a"), keys("PAUSE"))
    }

    @Test
    fun matchesSummariesAndListsTitleMatchesFirst() {
        assertEquals(listOf("d", "c"), keys("flick"))
        assertEquals(listOf("a"), keys("folder"))
    }

    @Test
    fun everyWordMustMatchSomewhere() {
        assertEquals(listOf("c"), keys("momentum gliding"))
        assertEquals(emptyList<String>(), keys("momentum folder"))
        assertEquals(listOf("e"), keys("  gap   page "))
    }

    @Test
    fun aBlankQueryFindsNothing() {
        assertEquals(emptyList<String>(), keys(""))
        assertEquals(emptyList<String>(), keys("   "))
    }
}
