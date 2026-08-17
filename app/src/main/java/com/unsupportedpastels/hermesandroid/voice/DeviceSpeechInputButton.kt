package com.unsupportedpastels.hermesandroid.voice

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Composer mic for app-owned, pause-tolerant device speech recognition. */
@Composable
fun DeviceSpeechInputButton(
    controller: DeviceSpeechRecognizerController,
    available: Boolean,
    enabled: Boolean,
    currentDraft: String,
    onDraftChanged: (String) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsState()
    val active = state != DeviceSpeechRecognizerState.Idle
    val description = if (active) "Stop voice input" else "Voice input"

    IconButton(
        onClick = {
            if (active) {
                controller.finish()
            } else if (!controller.start(currentDraft, onDraftChanged, onError)) {
                onError("Voice input is unavailable")
            }
        },
        enabled = enabled && (available || active),
        modifier = modifier.semantics { contentDescription = description },
    ) {
        Icon(
            imageVector = if (active) Icons.Outlined.Stop else Icons.Outlined.Mic,
            contentDescription = null,
            tint = if (active) {
                MaterialTheme.colorScheme.primary
            } else if (available && enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
        )
    }
}
