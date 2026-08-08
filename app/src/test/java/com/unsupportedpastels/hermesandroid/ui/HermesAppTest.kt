package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.hermesandroid.connection.HermesAuthProvider
import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import com.unsupportedpastels.hermesandroid.connection.ServerSettingsState
import com.unsupportedpastels.hermesandroid.gateway.AuthenticationState
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
    fun selectingSessionShowsDetailAndEditableComposer() {
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(snapshot = connectedSnapshot)
            }
        }

        composeRule.onNodeWithText("Sessions").assertIsDisplayed()
        composeRule.onNodeWithText("First session").performClick()
        composeRule.onNodeWithText("Back").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Transcript loading is not connected in this milestone. " +
                "Only the authenticated durable-session list is available.",
        ).assertIsDisplayed()
        composeRule.onNode(hasSetTextAction()).performTextInput("Fold-safe draft")
        composeRule.onNodeWithText("Fold-safe draft").assertIsDisplayed()
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
