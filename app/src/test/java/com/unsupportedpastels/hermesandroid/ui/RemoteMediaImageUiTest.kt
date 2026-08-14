package com.unsupportedpastels.hermesandroid.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.getOrNull
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsupportedpastels.hermesandroid.theme.HermesAndroidTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RemoteMediaImageUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun tappingGeneratedImageOpensEnlargedViewerAndCloseDismissesIt() {
        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).asImageBitmap()
        composeRule.setContent {
            HermesAndroidTheme {
                LoadedRemoteMediaImage(bitmap = bitmap)
            }
        }

        composeRule.onNodeWithContentDescription("Generated image; tap to enlarge")
            .performClick()
        composeRule.onNodeWithContentDescription("Enlarged generated image")
            .assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Close enlarged image")
            .performClick()
        composeRule.onNodeWithContentDescription("Enlarged generated image")
            .assertDoesNotExist()
    }

    @Test
    fun pinchingEnlargedImageChangesItsZoomLevel() {
        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).asImageBitmap()
        composeRule.setContent {
            HermesAndroidTheme {
                LoadedRemoteMediaImage(bitmap = bitmap)
            }
        }
        composeRule.onNodeWithContentDescription("Generated image; tap to enlarge")
            .performClick()

        composeRule.onNodeWithContentDescription("Enlarged generated image")
            .performTouchInput {
                pinch(
                    start0 = center + Offset(-40f, 0f),
                    start1 = center + Offset(40f, 0f),
                    end0 = center + Offset(-140f, 0f),
                    end1 = center + Offset(140f, 0f),
                    durationMillis = 300L,
                )
            }
            .assert(
                SemanticsMatcher("image is zoomed") { node ->
                    node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.StateDescription)
                        ?.startsWith("Zoom 1") == false
                },
            )
    }
}