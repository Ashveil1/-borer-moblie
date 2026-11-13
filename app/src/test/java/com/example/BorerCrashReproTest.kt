package com.example

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.data.ApiKind
import com.example.data.NoteKind
import com.example.data.ProviderProfile
import com.example.ui.BorerViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BorerCrashReproTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchMainActivity_andNavigateAllScreens_doesNotCrash() {
        composeRule.waitForIdle()

        // 1. Open Model / Session bottom sheet
        composeRule.onNodeWithTag("model_selector_pill").performClick()
        composeRule.waitForIdle()

        // Back/dismiss sheet via clicking scrim or navigating? Press back via activity
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()

        // 2. Quick prompt + submit without provider + slash commands
        composeRule.onNodeWithTag("quick_probe_chip").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("console_send_button").performClick()
        composeRule.waitForIdle()

        // 3. Open Drawer and navigate to Settings
        composeRule.onNodeWithTag("open_drawer_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav_settings").performClick()
        composeRule.waitForIdle()

        // Exercise Settings tabs: Providers -> Add Provider dialog -> Runtime -> Agent -> Alerts
        composeRule.onNodeWithTag("add_provider_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("tab_settings_runtime").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("shell_backend_direct").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("shell_backend_android").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("tab_settings_agent").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tab_settings_alerts").performClick()
        composeRule.waitForIdle()

        // 4. Navigate to Sessions & Hunt
        composeRule.onNodeWithTag("open_drawer_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav_sessions").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tab_sessions_archived").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tab_hunt_queue").performClick()
        composeRule.waitForIdle()

        // 5. Navigate to Intel & Reports
        composeRule.onNodeWithTag("open_drawer_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav_intel").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tab_intel_notes").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tab_intel_audit").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tab_intel_artifacts").performClick()
        composeRule.waitForIdle()

        // 6. Navigate to Security Lab
        composeRule.onNodeWithTag("open_drawer_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav_lab").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tab_lab_workbench").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tab_lab_skills").performClick()
        composeRule.waitForIdle()

        // 7. Back to Console
        composeRule.onNodeWithTag("open_drawer_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav_console").performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun viewModel_immediateCallsBeforeAsyncSessionInit_doNotCrash() {
        val app = ApplicationProvider.getApplicationContext<BorerApplication>()
        val vm = BorerViewModel(app)
        // Call methods immediately after construction before Dispatchers.IO sessionInitJob finishes
        vm.upsertProvider(
            ProviderProfile(
                id = "test",
                name = "test",
                baseUrl = "https://api.openai.com/v1/chat/completions",
                model = "gpt-4o",
                apiKey = "sk-test",
                api = ApiKind.CHAT
            )
        )
        vm.saveNote(NoteKind.FACT, "k1", "v1")
        vm.addTodo("check auth")
        vm.toggleTodo(0)
        vm.clearTodos()
        vm.readArtifact("nonexistent")
        vm.grepArtifact("nonexistent", "abc")
        vm.sliceArtifact("nonexistent", 0, 100)
        vm.submitPrompt("/help")
        vm.submitPrompt("/usage")
        vm.startNewSession()
        Thread.sleep(300)
    }
}
