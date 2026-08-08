package com.unsupportedpastels.hermesandroid

import android.os.Bundle
import android.content.Intent

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.net.toUri
import com.unsupportedpastels.hermesandroid.connection.HermesConnectionViewModel
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.connection.HermesWindowFocus
import com.unsupportedpastels.hermesandroid.connection.launchBrowserAndAwaitReturn
import com.unsupportedpastels.hermesandroid.connection.ServerSettingsViewModel
import com.unsupportedpastels.hermesandroid.gateway.HermesGatewaySnapshot
import com.unsupportedpastels.hermesandroid.theme.HermesAndroidTheme
import com.unsupportedpastels.hermesandroid.ui.HermesApp

class MainActivity : ComponentActivity() {
    private val serverSettingsViewModel by viewModels<ServerSettingsViewModel> {
        ServerSettingsViewModel.Factory(this)
    }
    private val connectionViewModel by viewModels<HermesConnectionViewModel> {
        HermesConnectionViewModel.ProductionFactory(
            context = applicationContext,
            settingsStates = serverSettingsViewModel.states,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        setContent {
            HermesAndroidTheme {
                val snapshot by connectionViewModel.snapshots.collectAsStateWithLifecycle()
                HermesAppHost(
                    viewModel = serverSettingsViewModel,
                    snapshot = snapshot,
                    onSignIn = {
                        connectionViewModel.signIn { authorizationUrl ->
                            launchBrowserAndAwaitReturn(HermesWindowFocus.state) {
                                startActivity(
                                    Intent(Intent.ACTION_VIEW, authorizationUrl.toUri()),
                                )
                            }
                        }
                    },
                    onOpenSession = connectionViewModel::openSession,
                    onSendMessage = connectionViewModel::sendMessage,
                )
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        HermesWindowFocus.state.value = hasFocus
    }
}

@Composable
internal fun HermesAppHost(
    viewModel: ServerSettingsViewModel,
    snapshot: HermesGatewaySnapshot,
    onSignIn: () -> Unit = {},
    onOpenSession: (DurableSessionId) -> Unit = {},
    onSendMessage: (DurableSessionId, String) -> Unit = { _, _ -> },
) {
    val serverSettingsState by viewModel.states.collectAsStateWithLifecycle()

    HermesApp(
        snapshot = snapshot,
        serverSettingsState = serverSettingsState,
        onSaveServerOrigin = { origin -> viewModel.save(origin).await() },
        onSignIn = onSignIn,
        onOpenSession = onOpenSession,
        onSendMessage = onSendMessage,
    )
}
