package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsupportedpastels.hermesandroid.app.ProcessRow
import com.unsupportedpastels.hermesandroid.app.RunEventState
import com.unsupportedpastels.hermesandroid.app.RunTodoItem
import com.unsupportedpastels.hermesandroid.app.RunTodoStatus
import com.unsupportedpastels.hermesandroid.app.RunToolRow
import com.unsupportedpastels.hermesandroid.app.RunToolState
import com.unsupportedpastels.hermesandroid.theme.HermesAndroidTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ActivityStackTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun activityStackIsCollapsedAndExpandsToolsAndTodos() {
        composeRule.setContent {
            HermesAndroidTheme {
                ActivityStack(
                    runActive = true,
                    runState = RunEventState(
                        tools = listOf(
                            RunToolRow("tool-1", "read_file", context = "gateway.kt", state = RunToolState.Running),
                            RunToolRow("tool-2", "shell", summary = "Checked tests", state = RunToolState.Completed),
                        ),
                        todos = listOf(
                            RunTodoItem("todo-1", "Inspect gateway", RunTodoStatus.Completed),
                            RunTodoItem("todo-2", "Render activity stack", RunTodoStatus.InProgress),
                        ),
                    ),
                )
            }
        }

        composeRule.onNodeWithContentDescription(
            "Activity stack, 2 tools, 1/2 tasks, collapsed",
        ).assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Inspect gateway").assertIsDisplayed()
        composeRule.onNodeWithText("Render activity stack").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            "Activity stack, 2 tools, 1/2 tasks, expanded",
        ).assertIsDisplayed()
    }

    @Test
    fun processRowsAreClearlyLabeledProcessLocalInTheUnifiedStack() {
        composeRule.setContent {
            HermesAndroidTheme {
                ActivityStack(
                    runState = RunEventState(),
                    processRows = listOf(
                        ProcessRow(
                            processId = "process-1",
                            command = "python server.py",
                            status = "running",
                        ),
                    ),
                )
            }
        }

        composeRule.onNodeWithContentDescription(
            "Activity stack, 0 tools, 0/0 tasks, 1 process-local process, collapsed",
        ).assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Processes · process-local").assertIsDisplayed()
        composeRule.onNodeWithText("python server.py").assertIsDisplayed()
        composeRule.onNodeWithText("running").assertIsDisplayed()
    }
}
