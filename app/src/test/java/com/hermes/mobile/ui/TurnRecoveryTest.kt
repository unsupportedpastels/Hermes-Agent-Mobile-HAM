package com.hermes.mobile.ui

import com.hermes.mobile.data.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TurnRecoveryTest {
    @Test fun returnsAssistantPersistedAfterSubmittedPrompt() {
        val messages = listOf(
            ChatMessage("1", "user", "earlier"),
            ChatMessage("2", "assistant", "earlier answer"),
            ChatMessage("3", "user", "recover this"),
            ChatMessage("4", "assistant", "complete stored answer"),
        )

        val recovered = findRecoveredAssistant(messages, 4, "recover this")

        assertEquals("complete stored answer", recovered?.text)
    }

    @Test fun doesNotReturnPreviousAnswerBeforeCurrentTurnIsPersisted() {
        val messages = listOf(
            ChatMessage("1", "user", "same prompt"),
            ChatMessage("2", "assistant", "old answer"),
        )

        assertNull(findRecoveredAssistant(messages, 4, "same prompt"))
    }

    @Test fun waitsForAssistantAfterPersistedUserMessage() {
        val messages = listOf(
            ChatMessage("1", "user", "earlier"),
            ChatMessage("2", "assistant", "earlier answer"),
            ChatMessage("3", "user", "recover this"),
            ChatMessage("tool", "tool", "Running terminal…"),
        )

        assertNull(findRecoveredAssistant(messages, 4, "recover this"))
    }

    @Test fun acceptsAssistantAfterToolMessages() {
        val messages = listOf(
            ChatMessage("1", "user", "earlier"),
            ChatMessage("2", "assistant", "earlier answer"),
            ChatMessage("3", "user", "recover this"),
            ChatMessage("tool", "tool", "tool result"),
            ChatMessage("4", "assistant", "final answer"),
        )

        val recovered = findRecoveredAssistant(messages, 4, "recover this")

        assertEquals("final answer", recovered?.text)
    }

    @Test fun doesNotTreatAssistantBeforeTrailingToolAsComplete() {
        val messages = listOf(
            ChatMessage("1", "user", "earlier"),
            ChatMessage("2", "assistant", "earlier answer"),
            ChatMessage("3", "user", "recover this"),
            ChatMessage("4", "assistant", "I will check that"),
            ChatMessage("tool", "tool", "tool result"),
        )

        assertNull(findRecoveredAssistant(messages, 4, "recover this"))
    }

    @Test fun doesNotTreatPersistedAssistantToolCallAsCompleteBeforeToolResult() {
        val messages = listOf(
            ChatMessage("1", "user", "earlier"),
            ChatMessage("2", "assistant", "earlier answer"),
            ChatMessage("3", "user", "recover this"),
            ChatMessage("4", "assistant", "I will check that", hasToolCalls = true),
        )

        assertNull(findRecoveredAssistant(messages, 4, "recover this"))
    }

    @Test fun mergesAuthoritativeRecoveredTailWithoutDroppingEarlierHistory() {
        val local = listOf(
            ChatMessage("1", "user", "earlier"),
            ChatMessage("2", "assistant", "earlier answer"),
            ChatMessage("local-user", "user", "recover this"),
            ChatMessage("stream", "assistant", "partial", streaming = true),
        )
        val stored = listOf(
            ChatMessage("stored-user", "user", "recover this"),
            ChatMessage("tool", "tool", "tool result"),
            ChatMessage("stored-assistant", "assistant", "complete answer"),
        )

        val merged = mergeRecoveredTranscript(local, stored, "recover this")

        assertEquals(listOf("1", "2", "stored-user", "tool", "stored-assistant"), merged.map { it.id })
    }
}
