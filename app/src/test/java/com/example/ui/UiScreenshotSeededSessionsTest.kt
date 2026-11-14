package com.example.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.example.BorerApplication
import com.example.MainActivity
import com.example.data.SessionEntity
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Drawer RECENT list + Sessions screen with seeded history (Robolectric render, not a device). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel7)
class UiScreenshotSeededSessionsPhoneTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun phone_seeded_drawerAndSessions() {
        seedSessions((composeRule.activity.application as BorerApplication))
        composeRule.waitForIdle()

        // Drawer with a populated RECENT section
        composeRule.onNodeWithTag("open_drawer_button").performClick()
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Recon on staging.example.com")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/seeded_drawer.png")

        // Sessions screen with rows
        try {
            composeRule.onNodeWithTag("nav_sessions").performClick()
            composeRule.waitForIdle()
        } catch (t: Throwable) {
            // drawer may already be closed
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("API auth probe")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/seeded_sessions.png")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w840dp-h980dp-xhdpi")
class UiScreenshotSeededSessionsTabletTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun tablet_seeded_sessions() {
        seedSessions((composeRule.activity.application as BorerApplication))
        composeRule.waitForIdle()
        try {
            composeRule.onNodeWithTag("nav_sessions").performClick()
            composeRule.waitForIdle()
        } catch (t: Throwable) {
            // permanent drawer may render without the tag visible
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Recon on staging.example.com")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/seeded_sessions_tablet.png")
    }
}

private fun seedSessions(app: BorerApplication) {
    val dao = app.runDatabase.sessionDao()
    val rows = listOf(
        SessionEntity(
            id = "seed-recon",
            title = "Recon on staging.example.com",
            model = "gpt-5",
            baseUrl = "https://api.openai.com/v1",
            contextWindow = 272000,
            turns = 14,
            messageCount = 31,
            createdAt = "2026-11-13T08:12:00Z",
            updatedAt = "2026-11-14T05:40:00Z",
            totalPromptTokens = 48210,
            totalCompletionTokens = 9120
        ),
        SessionEntity(
            id = "seed-auth",
            title = "API auth probe",
            model = "gpt-5",
            baseUrl = "https://api.openai.com/v1",
            contextWindow = 272000,
            turns = 6,
            messageCount = 15,
            createdAt = "2026-11-12T15:03:00Z",
            updatedAt = "2026-11-13T18:22:00Z",
            totalPromptTokens = 19980,
            totalCompletionTokens = 3412
        ),
        SessionEntity(
            id = "seed-dir",
            title = "Directory sweep on internal host",
            model = "gpt-5-mini",
            baseUrl = "https://api.openai.com/v1",
            contextWindow = 272000,
            turns = 22,
            messageCount = 47,
            createdAt = "2026-11-10T11:45:00Z",
            updatedAt = "2026-11-12T09:05:00Z",
            totalPromptTokens = 88040,
            totalCompletionTokens = 15230
        )
    )
    runBlocking { rows.forEach { dao.upsert(it) } }
}
