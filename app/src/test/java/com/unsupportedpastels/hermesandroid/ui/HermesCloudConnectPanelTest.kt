package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsupportedpastels.hermesandroid.connection.CloudAgent
import com.unsupportedpastels.hermesandroid.connection.CloudConnectState
import com.unsupportedpastels.hermesandroid.connection.CloudOrg
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class HermesCloudConnectPanelTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun agent(id: String, url: String?) = CloudAgent(
        id = id,
        name = id,
        status = "RUNNING",
        dashboardUrl = url,
        dashboardGatewayState = "unknown",
    )

    @Test
    fun signedOutShowsSignInAction() {
        var signedIn = false
        composeRule.setContent {
            MaterialTheme {
                HermesCloudConnectPanel(
                    state = CloudConnectState.SignedOut,
                    onSignIn = { signedIn = true },
                    onRefresh = {},
                    onSignOut = {},
                    onSelectOrg = {},
                    onSelectAgent = {},
                )
            }
        }

        composeRule.onNodeWithText("Sign in to Hermes Cloud").performClick()
        assertEquals(true, signedIn)
    }

    @Test
    fun agentsListSelectsConnectableAgentAndSkipsProvisioning() {
        val selected = mutableListOf<String>()
        composeRule.setContent {
            MaterialTheme {
                HermesCloudConnectPanel(
                    state = CloudConnectState.Agents(
                        agents = listOf(
                            agent("small", "https://small-9000.agents.nousresearch.com"),
                            agent("provisioning", null),
                        ),
                        org = CloudOrg("o1", "slug", "Personal", true, "OWNER"),
                    ),
                    onSignIn = {},
                    onRefresh = {},
                    onSignOut = {},
                    onSelectOrg = {},
                    onSelectAgent = { selected += it.id },
                )
            }
        }

        composeRule.onNodeWithText("small").assertIsDisplayed()
        composeRule.onNodeWithText("provisioning").assertIsDisplayed()

        // Connectable agent dispatches; provisioning agent's row is not clickable.
        composeRule.onNodeWithText("small").performClick()
        composeRule.onNodeWithText("provisioning").performClick()
        assertEquals(listOf("small"), selected)
    }

    @Test
    fun orgSelectionDispatchesChosenOrg() {
        val picked = mutableListOf<String>()
        composeRule.setContent {
            MaterialTheme {
                HermesCloudConnectPanel(
                    state = CloudConnectState.SelectOrg(
                        orgs = listOf(
                            CloudOrg("o1", "acme", "Acme", false, "MEMBER"),
                            CloudOrg("o2", null, "Personal", true, "OWNER"),
                        ),
                    ),
                    onSignIn = {},
                    onRefresh = {},
                    onSignOut = {},
                    onSelectOrg = { picked += it.id },
                    onSelectAgent = {},
                )
            }
        }

        composeRule.onNodeWithText("Acme").performClick()
        assertEquals(listOf("o1"), picked)
    }

    @Test
    fun errorWithSignedOutOffersReSignIn() {
        var signedIn = false
        composeRule.setContent {
            MaterialTheme {
                HermesCloudConnectPanel(
                    state = CloudConnectState.Error("Session expired", signedOut = true),
                    onSignIn = { signedIn = true },
                    onRefresh = {},
                    onSignOut = {},
                    onSelectOrg = {},
                    onSelectAgent = {},
                )
            }
        }

        composeRule.onNodeWithText("Session expired").assertIsDisplayed()
        composeRule.onNodeWithText("Sign in to Hermes Cloud").performClick()
        assertEquals(true, signedIn)
    }
}
