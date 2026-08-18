package com.unsupportedpastels.hermesandroid.ui

import com.unsupportedpastels.hermesandroid.gateway.SlashCompletionItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlashCommandsTest {
    @Test
    fun modelPickerCommandRequiresExactCommandToken() {
        assertTrue(isModelPickerCommand("/model"))
        assertTrue(isModelPickerCommand("  /model\n"))
        assertFalse(isModelPickerCommand("/models"))
        assertFalse(isModelPickerCommand("/model gpt-5"))
        assertFalse(isModelPickerCommand("please /model"))
    }

    @Test
    fun steerCommandIsAllowedDuringAnActiveTurn() {
        assertTrue(isSteerCommand("/steer"))
        assertTrue(isSteerCommand("  /steer Focus on the tests"))
        assertFalse(isSteerCommand("/steering Focus on the tests"))
        assertFalse(isSteerCommand("please /steer Focus on the tests"))
    }

    @Test
    fun reasoningCommandRequiresOneCanonicalEffort() {
        assertEquals("medium", reasoningEffortCommand("/reasoning medium"))
        assertEquals("xhigh", reasoningEffortCommand("  /reasoning XHIGH\n"))
        assertEquals("none", reasoningEffortCommand("/reasoning none"))
        assertEquals("ultra", reasoningEffortCommand("/reasoning ultra"))
        assertEquals(null, reasoningEffortCommand("/reasoning"))
        assertEquals(null, reasoningEffortCommand("/reasoning medium extra"))
        assertEquals(null, reasoningEffortCommand("/reasoning fastest"))
        assertEquals(null, reasoningEffortCommand("please /reasoning medium"))
    }

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
