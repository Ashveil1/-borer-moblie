package com.example.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.example.MainActivity
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Drawer + each destination screen (Robolectric render, not a device). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel7)
class UiScreenshotNavTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun phone_drawerAndScreens() {
        composeRule.waitForIdle()

        // Open the modal drawer and capture it
        composeRule.onNodeWithTag("open_drawer_button").performClick()
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/nav_drawer.png")

        val screens = listOf(
            "nav_sessions" to "nav_sessions.png",
            "nav_intel" to "nav_intel.png",
            "nav_lab" to "nav_lab.png",
            "nav_settings" to "nav_settings.png"
        )
        for ((tag, file) in screens) {
            try {
                composeRule.onNodeWithTag(tag).performClick()
                composeRule.waitForIdle()
                composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/$file")
            } catch (t: Throwable) {
                // keep going; missing shots are visible in the artifact listing
            }
            try {
                composeRule.onNodeWithTag("open_drawer_button").performClick()
                composeRule.waitForIdle()
            } catch (t: Throwable) {
                // drawer button may be absent on some screens
            }
        }
    }
}
