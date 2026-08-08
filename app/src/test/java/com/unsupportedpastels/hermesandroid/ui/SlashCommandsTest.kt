package com.unsupportedpastels.hermesandroid.ui

import com.unsupportedpastels.hermesandroid.gateway.SlashCompletionItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlashCommandsTest {
    @Test
    fun recognizesSlashCommandAtComposerStart() {
        assertTrue(isSlashCommandContext("/"))
        assertTrue(isSlashCommandContext("/h"))
        assertTrue(isSlashCommandContext("/help"))
        assertTrue(isSlashCommandContext("/goal status"))
        assertTrue(isSlashCommandContext("/reasoning h"))
    }

    @Test
    fun rejectsAbsolutePathsAndProse() {
        assertFalse(isSlashCommandContext(""))
        assertFalse(isSlashCommandContext("open /help"))
        assertFalse(isSlashCommandContext("/home/user/file"))
        assertFalse(isSlashCommandContext(" /help"))
        assertFalse(isSlashCommandContext("what does /goal do"))
    }

    @Test
    fun argumentCompletionStaysLiveAcrossWhitespace() {
        assertTrue(isSlashCommandContext("/goal some argument text"))
        assertTrue(isSlashCommandContext("/cron ad"))
    }

    @Test
    fun appliesRootCompletionWithoutLeadingSlashOnItem() {
        assertEquals(
            "/help",
            applySlashCompletion("/he", SlashCompletionItem(text = "help"), replaceFrom = 1),
        )
    }

    @Test
    fun appliesRootCompletionDroppingDuplicateSlash() {
        assertEquals(
            "/details",
            applySlashCompletion("/det", SlashCompletionItem(text = "/details"), replaceFrom = 1),
        )
    }

    @Test
    fun appliesArgumentCompletionPreservingPrefix() {
        assertEquals(
            "/reasoning high",
            applySlashCompletion(
                "/reasoning h",
                SlashCompletionItem(text = "high"),
                replaceFrom = 11,
            ),
        )
    }

    @Test
    fun applyDropsRemainderAfterReplacementPoint() {
        assertEquals(
            "/goal",
            applySlashCompletion("/goa extra", SlashCompletionItem(text = "goal"), replaceFrom = 1),
        )
    }

    @Test
    fun applyClampsReplaceFromBeyondTextEnd() {
        assertEquals(
            "/go/goal",
            applySlashCompletion("/go", SlashCompletionItem(text = "/goal"), replaceFrom = 99),
        )
    }

    @Test
    fun applyClampsNegativeReplaceFromToWholeTextReplacement() {
        assertEquals(
            "goal",
            applySlashCompletion("/go", SlashCompletionItem(text = "goal"), replaceFrom = -3),
        )
        assertEquals(
            "/goal",
            applySlashCompletion("/go", SlashCompletionItem(text = "/goal"), replaceFrom = -3),
        )
    }
}
