package com.example

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.data.ApiKind
import com.example.data.NoteKind
import com.example.data.ProviderProfile
import com.example.runtime.BackgroundKeepAliveController
import com.example.ui.BorerViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

        // 0. Top-right '+' New Session button works without crashing
        composeRule.onNodeWithTag("new_session_button").performClick()
        composeRule.waitForIdle()

        // 1. Open Model / Session bottom sheet
        composeRule.onNodeWithTag("model_selector_pill").performClick()
        composeRule.waitForIdle()

        // Back/dismiss sheet via clicking scrim or navigating? Press back via activity
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()

        // 2. Minimal empty state shows only '>' + submit without provider
        composeRule.onNodeWithTag("empty_prompt_glyph").assertExists()
        composeRule.onNodeWithTag("console_prompt_input").performTextInput("Map endpoints")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("console_send_button").performClick()
        composeRule.waitForIdle()

        // 3. Open Drawer and navigate to Settings
        composeRule.onNodeWithTag("open_drawer_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav_settings").performClick()
        composeRule.waitForIdle()

        // Exercise Settings tabs: Providers -> Add Provider dialog -> Runtime -> MCP -> Agent -> Alerts
        composeRule.onNodeWithTag("add_provider_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("tab_settings_runtime").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("switch_persistent_wakelock").assertExists()
        composeRule.onNodeWithTag("open_battery_settings_button").assertExists()
        composeRule.onNodeWithTag("runtime_settings_list").performScrollToNode(hasTestTag("shell_backend_direct"))
        composeRule.onNodeWithTag("shell_backend_direct").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("shell_backend_android").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("tab_settings_mcp").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("install_whsearch_button").assertExists()
        composeRule.onNodeWithTag("add_mcp_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("mcp_name_input").assertExists()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("import_mcp_json_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("mcp_import_json_input").assertExists()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("tab_settings_agent").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tab_settings_alerts").performScrollTo().performClick()
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
        vm.submitPrompt("/mode")
        vm.startNewSession()
        Thread.sleep(300)
    }

    @Test
    fun consoleSlashAutocomplete_modeChip_andProviderEditor_workCleanly() {
        composeRule.waitForIdle()

        // Toggle Chat <-> Auto via composer '+' commands menu
        composeRule.onNodeWithTag("composer_commands_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("mode_toggle_chip").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Auto").assertExists()

        // Type "/" to trigger slash-command autocomplete and select /mode to switch back to Chat
        composeRule.onNodeWithTag("console_prompt_input").performTextInput("/")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("autocomplete_mode").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("mode switched to Chat").assertExists()

        // Open Settings -> Add Provider -> fill valid fields -> Save
        composeRule.onNodeWithTag("open_drawer_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav_settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("add_provider_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("provider_dialog_test_button").assertExists()
        composeRule.onNodeWithTag("provider_model_input").performTextInput("gpt-4o")
        composeRule.onNodeWithTag("provider_key_input").performTextInput("sk-demo-key")
        composeRule.onNodeWithTag("provider_save_button").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("gpt-4o")[0].assertExists()
        composeRule.onNodeWithText("Saved provider 'primary'").assertExists()
    }

    @Test
    fun backgroundKeepAlive_andSessionControllerContinuity_workCorrectly() {
        val app = ApplicationProvider.getApplicationContext<BorerApplication>()
        val vm1 = BorerViewModel(app)
        assertTrue(vm1.settings.value.backgroundKeepAlive)
        assertFalse(vm1.settings.value.persistentWakeLock)

        vm1.setPersistentWakeLock(true)
        assertTrue(vm1.settings.value.persistentWakeLock)
        assertTrue(BackgroundKeepAliveController.snapshot.value.shouldKeepServiceRunning)

        vm1.submitPrompt("/mode")
        val vm2 = BorerViewModel(app)
        assertEquals(vm1.console.value.runId, vm2.console.value.runId)
        assertEquals(vm1.console.value.mode, vm2.console.value.mode)
        assertTrue(vm2.settings.value.persistentWakeLock)

        vm2.setPersistentWakeLock(false)
        assertFalse(vm1.settings.value.persistentWakeLock)
        assertFalse(BackgroundKeepAliveController.snapshot.value.shouldKeepServiceRunning)
    }
}
