package com.unsupportedpastels.hermesandroid

import android.os.Bundle
import android.content.Intent

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.net.toUri
import com.unsupportedpastels.hermesandroid.app.ComposerAttachment
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.connection.HermesConnectionViewModel
import com.unsupportedpastels.hermesandroid.connection.HermesWindowFocus
import com.unsupportedpastels.hermesandroid.connection.ServerSettingsViewModel
import com.unsupportedpastels.hermesandroid.connection.launchBrowserAndAwaitReturn
import com.unsupportedpastels.hermesandroid.connection.SlashCompletionState
import com.unsupportedpastels.hermesandroid.gateway.HermesGatewaySnapshot
import com.unsupportedpastels.hermesandroid.theme.HermesAndroidTheme
import com.unsupportedpastels.hermesandroid.ui.HermesApp
import kotlinx.coroutines.flow.MutableStateFlow

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
                    connectionViewModel = connectionViewModel,
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
    connectionViewModel: HermesConnectionViewModel? = null,
    snapshot: HermesGatewaySnapshot,
    onSignIn: () -> Unit = {},
    onOpenSession: (DurableSessionId) -> Unit = {},
    onSendMessage: (DurableSessionId, String) -> Unit = { _, _ -> },
) {
    val serverSettingsState by viewModel.states.collectAsStateWithLifecycle()
    val slashCompletionsFlow = connectionViewModel?.slashCompletions
        ?: remember { MutableStateFlow(emptyMap<DurableSessionId, SlashCompletionState>()) }
    val slashCompletions by slashCompletionsFlow.collectAsStateWithLifecycle()
    val attachmentsFlow = connectionViewModel?.attachments
        ?: remember { MutableStateFlow(emptyMap<DurableSessionId, List<ComposerAttachment>>()) }
    val attachments by attachmentsFlow.collectAsStateWithLifecycle()

    HermesApp(
        snapshot = snapshot,
        serverSettingsState = serverSettingsState,
        onSaveServerOrigin = { origin -> viewModel.save(origin).await() },
        onSignIn = onSignIn,
        onOpenSession = onOpenSession,
        onSendMessage = onSendMessage,
        onCreateSession = { connectionViewModel?.createNewSession() },
        slashCompletions = slashCompletions,
        attachments = attachments,
        onAddAttachments = { sessionId, candidates ->
            connectionViewModel?.addAttachments(sessionId, candidates).orEmpty()
        },
        onRemoveAttachment = { sessionId, attachmentId ->
            connectionViewModel?.removeAttachment(sessionId, attachmentId)
        },
        onSlashCompletionRequested = { sessionId, text ->
            connectionViewModel?.updateSlashCompletion(sessionId, text)
        },
    )
}
