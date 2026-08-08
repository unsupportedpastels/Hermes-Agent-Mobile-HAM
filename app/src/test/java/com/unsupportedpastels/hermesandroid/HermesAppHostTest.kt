package com.unsupportedpastels.hermesandroid

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import com.unsupportedpastels.hermesandroid.connection.ServerSettingsRepository
import com.unsupportedpastels.hermesandroid.connection.ServerSettingsState
import com.unsupportedpastels.hermesandroid.connection.ServerSettingsViewModel
import com.unsupportedpastels.hermesandroid.gateway.HermesGatewaySnapshot
import com.unsupportedpastels.hermesandroid.theme.HermesAndroidTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class HermesAppHostTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun savedOriginFlowsBackIntoTheApp() {
        val repository = FakeServerSettingsRepository()
        val viewModel = ServerSettingsViewModel(repository)
        composeRule.setContent {
            HermesAndroidTheme {
                HermesAppHost(
                    viewModel = viewModel,
                    snapshot = HermesGatewaySnapshot(),
                )
            }
        }

        composeRule.onNodeWithText("Configure server").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("https://hermes.example/")
        composeRule.onNodeWithText("Save").performClick()

        composeRule.onNodeWithText("Server configured").assertIsDisplayed()
        composeRule.onNodeWithText("https://hermes.example").assertIsDisplayed()
    }
}

private class FakeServerSettingsRepository : ServerSettingsRepository {
    private val mutableOrigin = MutableStateFlow<ServerOrigin?>(null)
    override val states: Flow<ServerSettingsState> = mutableOrigin
        .map { ServerSettingsState.Ready(it) }

    override suspend fun save(serverOrigin: ServerOrigin) {
        mutableOrigin.value = serverOrigin
    }
}
