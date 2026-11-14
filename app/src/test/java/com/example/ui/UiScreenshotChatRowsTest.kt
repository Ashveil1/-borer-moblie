package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.example.MainActivity
import com.example.ui.screens.AssistantProse
import com.example.ui.screens.SubtleInfoLine
import com.example.ui.screens.ThoughtRow
import com.example.ui.screens.ToolActionRow
import com.example.ui.screens.UserBubble
import com.example.ui.theme.BorerTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Seeded populated-console render (Robolectric, not a device). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel7)
class UiScreenshotChatRowsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun phone_populatedChatRows() {
        composeRule.setContent {
            BorerTheme {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    UserBubble(
                        TranscriptEntry.User(
                            "u1",
                            "Run an unauthenticated check against https://staging.example.com and tell me what is exposed."
                        )
                    )
                    AssistantProse(
                        TranscriptEntry.Assistant(
                            "a1",
                            "I'll map the surface first, then only report what verifies.\n\n**Plan**\n1. Fetch `/` and `/robots.txt`\n2. Probe common API paths\n3. Verify anything interesting twice\n\nStarting the HTTP baseline now."
                        ),
                        isStreaming = false
                    )
                    ThoughtRow(
                        TranscriptEntry.Reasoning(
                            "r1",
                            "Host is in scope per the engagement rules, so the baseline request is allowed.",
                            collapsed = false
                        )
                    ) {}
                    ToolActionRow(
                        TranscriptEntry.ToolExec(
                            id = "t1",
                            callId = "c1",
                            name = "http",
                            arguments = "{\"url\":\"https://staging.example.com/\"}",
                            running = false,
                            ok = true,
                            ms = 412,
                            preview = "200 OK | 12.4 KB | text/html"
                        )
                    )
                    SubtleInfoLine(TranscriptEntry.Info("i1", "staging.example.com is in scope"))
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage()
    }
}
