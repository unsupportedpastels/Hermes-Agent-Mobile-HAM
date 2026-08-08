package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsupportedpastels.hermesandroid.app.ComposerAttachment
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.hermesandroid.connection.HermesAuthProvider
import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import com.unsupportedpastels.hermesandroid.connection.ServerSettingsState
import com.unsupportedpastels.hermesandroid.gateway.AuthenticationState
import com.unsupportedpastels.hermesandroid.gateway.ChatMessage
import com.unsupportedpastels.hermesandroid.gateway.ChatMessageRole
import com.unsupportedpastels.hermesandroid.gateway.ChatSessionSnapshot
import com.unsupportedpastels.hermesandroid.gateway.ConnectionState
import com.unsupportedpastels.hermesandroid.gateway.HermesGatewaySnapshot
import com.unsupportedpastels.hermesandroid.theme.HermesAndroidTheme
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class HermesAppTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val sessions = listOf(
        SessionSummary(DurableSessionId("stored-1"), "First session"),
        SessionSummary(DurableSessionId("stored-2"), "Second session"),
    )
    private val connectedSnapshot = HermesGatewaySnapshot(
        connectionState = ConnectionState.Connected,
        durableSessions = sessions,
    )

    @Test
    fun longTranscriptAutoScrollsToNewestMessage() {
        val history = (1..30).map { index ->
            ChatMessage(ChatMessageRole.User, "old message $index")
        } + ChatMessage(ChatMessageRole.Assistant, "newest streamed answer")
        val snapshot = connectedSnapshot.copy(
            chatSessions = mapOf(
                sessions.first().id to ChatSessionSnapshot(messages = history),
            ),
        )

        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(snapshot = snapshot)
            }
        }

        composeRule.onNodeWithText("First session").performClick()
        composeRule.onNodeWithText("newest streamed answer").assertIsDisplayed()
    }

    @Test
    fun assistantMarkdownRendersWithoutLiteralFormattingMarkers() {
        val snapshot = connectedSnapshot.copy(
            chatSessions = mapOf(
                sessions.first().id to ChatSessionSnapshot(
                    messages = listOf(
                        ChatMessage(
                            ChatMessageRole.Assistant,
                            """
                            - **Container:** removed; no `service` remains.
                            ```text
                            example/image:latest
                            ```
                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(snapshot = snapshot)
            }
        }

        composeRule.onNodeWithText("First session").performClick()
        composeRule.onNodeWithText("Container: removed; no service remains.").assertIsDisplayed()
        composeRule.onNodeWithText("example/image:latest").assertIsDisplayed()
    }

    @Test
    fun userMarkdownUsesTheSameRichTextRendererAsAssistantMessages() {
        val snapshot = connectedSnapshot.copy(
            chatSessions = mapOf(
                sessions.first().id to ChatSessionSnapshot(
                    messages = listOf(
                        ChatMessage(
                            ChatMessageRole.User,
                            "- **Status:** open `Settings`.",
                        ),
                    ),
                ),
            ),
        )
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(snapshot = snapshot)
            }
        }

        composeRule.onNodeWithText("First session").performClick()
        composeRule.onNodeWithText("Status: open Settings.").assertIsDisplayed()
    }

    @Test
    fun selectingSessionLoadsTranscriptAndSendsComposerText() {
        var opened: DurableSessionId? = null
        var sent: Pair<DurableSessionId, String>? = null
        val snapshot = connectedSnapshot.copy(
            authenticationState = AuthenticationState.Authenticated,
            chatSessions = mapOf(
                sessions.first().id to ChatSessionSnapshot(
                    messages = listOf(
                        ChatMessage(ChatMessageRole.User, "Earlier question"),
                        ChatMessage(ChatMessageRole.Assistant, "Earlier answer"),
                    ),
                ),
            ),
        )
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = snapshot,
                    onOpenSession = { opened = it },
                    onSendMessage = { sessionId, text -> sent = sessionId to text },
                )
            }
        }

        composeRule.onNodeWithText("Sessions").assertIsDisplayed()
        composeRule.onNodeWithText("First session").performClick()
        composeRule.onNodeWithText("Back").assertIsDisplayed()
        composeRule.onNodeWithText("Earlier question").assertIsDisplayed()
        composeRule.onNodeWithText("Earlier answer").assertIsDisplayed()
        composeRule.waitForIdle()
        assertEquals(sessions.first().id, opened)
        composeRule.onNode(hasSetTextAction()).performTextInput("New question")
        composeRule.onNodeWithText("Send").performClick()
        assertEquals(sessions.first().id to "New question", sent)
    }

    @Test
    fun selectedAttachmentsRenderAsRemovableInputChips() {
        var removed: Pair<DurableSessionId, String>? = null
        val sessionId = sessions.first().id
        val attachment = ComposerAttachment(
            id = "attachment-1",
            uri = "content://provider/report",
            displayName = "report.pdf",
            mimeType = "application/pdf",
            sizeBytes = 42,
        )
        val snapshot = connectedSnapshot.copy(authenticationState = AuthenticationState.Authenticated)

        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = snapshot,
                    attachments = mapOf(sessionId to listOf(attachment)),
                    onRemoveAttachment = { id, attachmentId -> removed = id to attachmentId },
                )
            }
        }

        composeRule.onNodeWithText("First session").performClick()
        composeRule.onNodeWithContentDescription("Attach files").assertIsDisplayed()
        composeRule.onNodeWithText("report.pdf").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Remove report.pdf").performClick()
        assertEquals(sessionId to "attachment-1", removed)
    }

    @Test
    fun attachmentOnlyDraftCanBeSentAndComposerControlsDisableWhileSending() {
        var sent: Pair<DurableSessionId, String>? = null
        val sessionId = sessions.first().id
        val attachment = ComposerAttachment(
            id = "attachment-1",
            uri = "content://provider/report",
            displayName = "report.pdf",
            mimeType = "application/pdf",
            sizeBytes = 42,
        )
        var isSending by mutableStateOf(false)
        val snapshot = connectedSnapshot.copy(
            authenticationState = AuthenticationState.Authenticated,
        )

        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = snapshot.copy(
                        chatSessions = mapOf(sessionId to ChatSessionSnapshot(isSending = isSending)),
                    ),
                    attachments = mapOf(sessionId to listOf(attachment)),
                    onSendMessage = { id, text -> sent = id to text },
                )
            }
        }

        composeRule.onNodeWithText("First session").performClick()
        composeRule.onNodeWithText("Send").performClick()
        assertEquals(sessionId to "", sent)

        composeRule.runOnIdle { isSending = true }
        composeRule.onNodeWithContentDescription("Attach files").assertIsNotEnabled()
        composeRule.onNodeWithText("Send").assertIsNotEnabled()
    }

    @Test
    fun failedSubmissionKeepsTypedDraftEditable() {
        val sessionId = sessions.first().id
        var snapshot by mutableStateOf(
            connectedSnapshot.copy(
                authenticationState = AuthenticationState.Authenticated,
                chatSessions = mapOf(sessionId to ChatSessionSnapshot()),
            ),
        )
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = snapshot,
                    onSendMessage = { _, text ->
                        snapshot = snapshot.copy(
                            chatSessions = mapOf(
                                sessionId to ChatSessionSnapshot(
                                    messages = listOf(ChatMessage(ChatMessageRole.User, text)),
                                    error = "prompt rejected",
                                ),
                            ),
                        )
                    },
                )
            }
        }

        composeRule.onNodeWithText("First session").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("Keep this draft")
        composeRule.onNodeWithText("Send").performClick()
        composeRule.onNodeWithText("prompt rejected").assertIsDisplayed()
        val inputText = composeRule.onNode(hasSetTextAction())
            .fetchSemanticsNode().config[SemanticsProperties.InputText].text
        assertEquals("Keep this draft", inputText)
    }

    @Test
    fun removingLastAttachmentRemovesItsChip() {
        var attachments by mutableStateOf(
            mapOf(
                sessions.first().id to listOf(
                    ComposerAttachment(
                        id = "attachment-1",
                        uri = "content://provider/report",
                        displayName = "report.pdf",
                        mimeType = "application/pdf",
                        sizeBytes = 42,
                    ),
                ),
            ),
        )
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = connectedSnapshot.copy(authenticationState = AuthenticationState.Authenticated),
                    attachments = attachments,
                    onRemoveAttachment = { sessionId, _ -> attachments = attachments - sessionId },
                )
            }
        }

        composeRule.onNodeWithText("First session").performClick()
        composeRule.onNodeWithContentDescription("Remove report.pdf").performClick()
        composeRule.onNodeWithText("report.pdf").assertDoesNotExist()
    }

    @Test
    fun authenticationFreeSessionDoesNotAdvertiseUnsupportedNativeSend() {
        val snapshot = connectedSnapshot.copy(
            authenticationState = AuthenticationState.NotRequired,
        )
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(snapshot = snapshot)
            }
        }

        composeRule.onNodeWithText("First session").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("Question")
        composeRule.onNodeWithText("Send").assertIsNotEnabled()
    }

    @Test
    fun composerDraftSurvivesSavedStateRestoration() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            HermesAndroidTheme {
                HermesApp(snapshot = connectedSnapshot)
            }
        }

        composeRule.onNodeWithText("First session").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("Restored draft")

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("First session").assertIsDisplayed()
        composeRule.onNodeWithText("Restored draft").assertIsDisplayed()
    }

    @Test
    fun connectedServerWithoutDurableSessionsIsNotShownAsUnconfigured() {
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = HermesGatewaySnapshot(
                        connectionState = ConnectionState.Connected,
                    ),
                )
            }
        }

        composeRule.onNodeWithText("No saved sessions").assertIsDisplayed()
    }

    @Test
    fun reachableGatedServerOffersNativeNousSignIn() {
        var signInRequested = false
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = HermesGatewaySnapshot(
                        connectionState = ConnectionState.Connected,
                        authenticationState = AuthenticationState.SignInRequired,
                        serverVersion = "0.20.0",
                        nativeOAuthSupported = true,
                        authProviders = listOf(HermesAuthProvider("nous", "Nous Research")),
                    ),
                    serverSettingsState = ServerSettingsState.Ready(
                        ServerOrigin.parse("https://hermes.example"),
                    ),
                    onSignIn = { signInRequested = true },
                )
            }
        }

        composeRule.onNodeWithText("Server reachable").assertIsDisplayed()
        composeRule.onNodeWithText("Hermes 0.20.0 · Sign in required").assertIsDisplayed()
        composeRule.onNodeWithText("Sign in with Nous").performClick()

        assertTrue(signInRequested)
    }

    @Test
    fun connectionFailureIsVisibleInsteadOfOnlyShowingSavedOrigin() {
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = HermesGatewaySnapshot(
                        connectionState = ConnectionState.Disconnected,
                        connectionError = "Could not reach Hermes Serve",
                    ),
                    serverSettingsState = ServerSettingsState.Ready(
                        ServerOrigin.parse("https://hermes.example"),
                    ),
                )
            }
        }

        composeRule.onNodeWithText("Could not reach Hermes Serve").assertIsDisplayed()
    }

    @Test
    fun unconfiguredScreenSavesCanonicalHttpsServerOrigin() {
        var savedOrigin: ServerOrigin? = null
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = HermesGatewaySnapshot(),
                    serverSettingsState = ServerSettingsState.Ready(null),
                    onSaveServerOrigin = { origin ->
                        savedOrigin = origin
                        Result.success(Unit)
                    },
                )
            }
        }

        composeRule.onNodeWithText("Configure server").performClick()
        composeRule.onNodeWithText("Server origin").assertIsDisplayed()
        composeRule.onNode(hasSetTextAction()).performTextInput("HTTPS://Example.COM/")
        composeRule.onNodeWithText("Save").performClick()

        assertEquals("https://example.com", savedOrigin?.value)
    }

    @Test
    fun serverDialogRejectsCleartextOrigin() {
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = HermesGatewaySnapshot(),
                    serverSettingsState = ServerSettingsState.Ready(null),
                    onSaveServerOrigin = { Result.success(Unit) },
                )
            }
        }

        composeRule.onNodeWithText("Configure server").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("http://example.com")

        composeRule.onNodeWithText("Server origin must use HTTPS").assertIsDisplayed()
        composeRule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun loadingSettingsCannotBeMistakenForUnconfigured() {
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = HermesGatewaySnapshot(),
                    serverSettingsState = ServerSettingsState.Loading,
                )
            }
        }

        composeRule.onNodeWithText("Loading server settings").assertIsDisplayed()
        composeRule.onNodeWithText("Server").assertIsNotEnabled()
    }

    @Test
    fun unavailableSettingsCanBeReplaced() {
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = HermesGatewaySnapshot(),
                    serverSettingsState = ServerSettingsState.Unavailable,
                )
            }
        }

        composeRule.onNodeWithText("Server settings unavailable").assertIsDisplayed()
        composeRule.onNodeWithText("Open Server to replace the saved origin.").assertIsDisplayed()
        composeRule.onNodeWithText("Server").performClick()
        composeRule.onNodeWithText("Hermes server").assertIsDisplayed()
    }

    @Test
    fun serverEditorIsACompactNavigationDestination() {
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = HermesGatewaySnapshot(),
                    serverSettingsState = ServerSettingsState.Ready(null),
                    onSaveServerOrigin = { Result.success(Unit) },
                )
            }
        }

        composeRule.onNodeWithText("Configure server").performClick()

        composeRule.onNodeWithText("Hermes server").assertIsDisplayed()
        composeRule.onNodeWithText("Back").assertIsDisplayed()
    }
}
