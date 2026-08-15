package com.unsupportedpastels.hermesandroid.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolActivitySummaryTest {
    @Test
    fun bucketsKnownToolsIntoVerbPhrases() {
        assertEquals(
            "Edited 2 files, ran a command, read a file",
            toolActivitySummary(listOf("write_file", "patch", "shell", "read_file")),
        )
    }

    @Test
    fun unknownNamesFallBackToCountedRawNames() {
        assertEquals(
            "Browser_exec ×2",
            toolActivitySummary(listOf("browser_exec", "browser_exec")),
        )
    }

    @Test
    fun runningToolsLeadTheSummary() {
        assertEquals(
            "Running shell, read a file",
            toolActivitySummary(listOf("read_file"), listOf("shell")),
        )
    }

    @Test
    fun overflowCollapsesBeyondThreePhrases() {
        assertEquals(
            "Read a file, edited a file, ran a command, +1 more",
            toolActivitySummary(listOf("read_file", "write_file", "shell", "web_search")),
        )
    }
}
