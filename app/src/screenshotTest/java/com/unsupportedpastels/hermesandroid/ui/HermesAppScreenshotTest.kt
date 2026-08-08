package com.unsupportedpastels.hermesandroid.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import com.android.tools.screenshot.PreviewTest
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import com.unsupportedpastels.hermesandroid.connection.SlashCompletionState
import com.unsupportedpastels.hermesandroid.gateway.ConnectionState
import com.unsupportedpastels.hermesandroid.gateway.HermesGatewaySnapshot
import com.unsupportedpastels.hermesandroid.gateway.SlashCompletionItem
import com.unsupportedpastels.hermesandroid.theme.HermesAndroidTheme

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
@Preview(name = "Compact short", widthDp = 400, heightDp = 400, showBackground = true)
@Preview(name = "Compact standard", widthDp = 400, heightDp = 500, showBackground = true)
@Preview(name = "Compact tall", widthDp = 400, heightDp = 1000, showBackground = true)
@Preview(name = "Medium short", widthDp = 610, heightDp = 400, showBackground = true)
@Preview(name = "Medium standard", widthDp = 610, heightDp = 500, showBackground = true)
@Preview(name = "Medium tall", widthDp = 610, heightDp = 1000, showBackground = true)
@Preview(name = "Expanded short", widthDp = 900, heightDp = 400, showBackground = true)
@Preview(name = "Expanded standard", widthDp = 900, heightDp = 500, showBackground = true)
@Preview(name = "Expanded tall", widthDp = 900, heightDp = 1000, showBackground = true)
annotation class AdaptiveWindowPreviews

private val screenshotSessions = listOf(
    SessionSummary(DurableSessionId("stored-1"), "Android client planning"),
    SessionSummary(DurableSessionId("stored-2"), "Foldable UI review"),
    SessionSummary(DurableSessionId("stored-3"), "Hermes protocol notes"),
)
private val screenshotSnapshot = HermesGatewaySnapshot(
    connectionState = ConnectionState.Connected,
    durableSessions = screenshotSessions,
)

@PreviewTest
@AdaptiveWindowPreviews
@Composable
fun HermesAppAdaptiveScreenshot() {
    ScreenshotNavigationHost {
        HermesAndroidTheme(darkTheme = false) {
            HermesApp(snapshot = screenshotSnapshot)
        }
    }
}

@PreviewTest
@Preview(
    name = "Compact dark large text",
    widthDp = 400,
    heightDp = 500,
    fontScale = 1.5f,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Composable
fun HermesAppAccessibilityScreenshot() {
    ScreenshotNavigationHost {
        HermesAndroidTheme(darkTheme = true) {
            HermesApp(snapshot = screenshotSnapshot)
        }
    }
}

@PreviewTest
@Preview(name = "Compact server setup", widthDp = 400, heightDp = 900, showBackground = true)
@Composable
fun HermesServerSetupScreenshot() {
    ScreenshotNavigationHost {
        HermesAndroidTheme(darkTheme = false) {
            HermesApp(snapshot = HermesGatewaySnapshot())
        }
    }
}

@PreviewTest
@Preview(name = "Compact server settings", widthDp = 400, heightDp = 900, showBackground = true)
@Composable
fun HermesServerDialogScreenshot() {
    HermesAndroidTheme(darkTheme = false) {
        ServerSettingsScreen(
            serverOrigin = ServerOrigin.parse("https://hermes.example"),
            showBack = true,
            onBack = {},
            onSave = { Result.success(Unit) },
        )
    }
}

@PreviewTest
@Preview(name = "Slash completion picker", widthDp = 400, heightDp = 500, showBackground = true)
@Composable
fun SlashCompletionMenuScreenshot() {
    HermesAndroidTheme(darkTheme = false) {
        SlashCompletionMenu(
            completion = SlashCompletionState(
                composerText = "/go",
                items = listOf(
                    SlashCompletionItem("goal", "/goal", "Set a standing goal for this session"),
                    SlashCompletionItem("gol", "/gol"),
                    SlashCompletionItem("goodbye", "/goodbye", "End the conversation"),
                ),
                replaceFrom = 1,
            ),
            onItemSelected = {},
        )
    }
}

@Composable
private fun ScreenshotNavigationHost(content: @Composable () -> Unit) {
    val owner = rememberNavigationEventDispatcherOwner(parent = null)
    CompositionLocalProvider(
        LocalNavigationEventDispatcherOwner provides owner,
        content = content,
    )
}
