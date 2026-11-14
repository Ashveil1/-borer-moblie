package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.BorerApplication
import com.example.data.AppConfig
import com.example.data.ArtifactEntity
import com.example.data.BorerSettings
import com.example.data.EventLog
import com.example.data.EventRecord
import com.example.data.ExperienceEntity
import com.example.data.FindingEntity
import com.example.data.HttpLogRecord
import com.example.data.JobEntity
import com.example.data.LearningMemory
import com.example.data.LearningStats
import com.example.data.NoteEntity
import com.example.data.NoteKind
import com.example.data.NotifyConfig
import com.example.data.ProviderProfile
import com.example.data.SessionEntity
import com.example.data.TargetHistoryEntity
import com.example.data.TechniqueEntity
import com.example.provider.ChatMessage
import com.example.provider.Usage
import com.example.runtime.ProotDiagnostics
import com.example.session.BorerSessionController
import com.example.tools.BorerSkill
import kotlinx.coroutines.flow.StateFlow
import java.io.File

sealed interface TranscriptEntry {
    val id: String

    data class User(override val id: String, val text: String) : TranscriptEntry
    data class Assistant(override val id: String, val text: String) : TranscriptEntry
    data class Reasoning(override val id: String, val text: String, val collapsed: Boolean = false) : TranscriptEntry
    data class ToolExec(
        override val id: String,
        val callId: String,
        val name: String,
        val arguments: String,
        val running: Boolean,
        val ok: Boolean = true,
        val ms: Long = 0,
        val preview: String = ""
    ) : TranscriptEntry
    data class Info(override val id: String, val text: String, val isWarn: Boolean = false) : TranscriptEntry
}

enum class RunMode { CHAT, AUTONOMOUS }

enum class HuntItemStatus { PENDING, RUNNING, DONE, FAILED }

data class HuntQueueItem(
    val target: String,
    val status: HuntItemStatus = HuntItemStatus.PENDING,
    val runId: String? = null,
    val findingsCount: Int = 0,
    val message: String? = null
)

data class ConsoleUiState(
    val runId: String = "",
    val mode: RunMode = RunMode.CHAT,
    val busy: Boolean = false,
    val stopRequested: Boolean = false,
    val turn: Int = 0,
    val contextTokens: Int = 0,
    val contextWindow: Int = 32_768,
    val contextSource: String = "fallback",
    val activeModel: String = "unconfigured",
    val providerConfigured: Boolean = false,
    val totals: Usage = Usage(),
    val queuedPrompts: List<String> = emptyList(),
    val entries: List<TranscriptEntry> = emptyList(),
    val hostsReached: Map<String, Int> = emptyMap(),
    val requestsAllowed: Int = 0,
    val requestsDenied: Int = 0,
    val learningStats: LearningStats = LearningStats(0, 0, 0, 0)
)

class BorerViewModel(application: Application) : AndroidViewModel(application) {
    private val controller: BorerSessionController = (application as BorerApplication).sessionController

    val memory: LearningMemory get() = controller.memory
    val workspaceDir: File get() = controller.workspaceDir
    val runsDir: File get() = controller.runsDir

    val settings: StateFlow<BorerSettings> get() = controller.settings
    val config: StateFlow<AppConfig> get() = controller.config
    val skills: StateFlow<List<BorerSkill>> get() = controller.skills
    val prootDiagnostics: StateFlow<ProotDiagnostics> get() = controller.prootDiagnostics
    val prootTestOutput: StateFlow<String?> get() = controller.prootTestOutput
    val prootTesting: StateFlow<Boolean> get() = controller.prootTesting
    val providerTestResult: StateFlow<String?> get() = controller.providerTestResult
    val providerTesting: StateFlow<Boolean> get() = controller.providerTesting
    val notifyTestResult: StateFlow<String?> get() = controller.notifyTestResult
    val console: StateFlow<ConsoleUiState> get() = controller.console
    val activeRunId: StateFlow<String> get() = controller.activeRunId
    val events: StateFlow<List<EventRecord>> get() = controller.events
    val httpLogs: StateFlow<List<HttpLogRecord>> get() = controller.httpLogs
    val huntItems: StateFlow<List<HuntQueueItem>> get() = controller.huntItems
    val huntRunning: StateFlow<Boolean> get() = controller.huntRunning

    val activeSessions: StateFlow<List<SessionEntity>> get() = controller.activeSessions
    val archivedSessions: StateFlow<List<SessionEntity>> get() = controller.archivedSessions
    val notes: StateFlow<List<NoteEntity>> get() = controller.notes
    val findings: StateFlow<List<FindingEntity>> get() = controller.findings
    val artifacts: StateFlow<List<ArtifactEntity>> get() = controller.artifacts
    val jobs: StateFlow<List<JobEntity>> get() = controller.jobs

    val allExperience: StateFlow<List<ExperienceEntity>> get() = controller.allExperience
    val allTechniques: StateFlow<List<TechniqueEntity>> get() = controller.allTechniques
    val allTargets: StateFlow<List<TargetHistoryEntity>> get() = controller.allTargets

    fun setRunMode(mode: RunMode) = controller.setRunMode(mode)
    fun setPersistentWakeLock(enabled: Boolean) = controller.setPersistentWakeLock(enabled)

    fun startNewSession(
        runId: String = EventLog.newRunId(),
        initialMessages: List<ChatMessage> = emptyList(),
        taskHint: String? = null
    ) = controller.startNewSession(runId, initialMessages, taskHint)

    fun submitPrompt(rawInput: String) = controller.submitPrompt(rawInput)
    fun requestStop() = controller.requestStop()
    fun forceCancelTurn() = controller.forceCancelTurn()
    fun toggleReasoningCollapsed(entryId: String) = controller.toggleReasoningCollapsed(entryId)
    fun resumeSession(sessionEntity: SessionEntity): Boolean = controller.resumeSession(sessionEntity)
    fun forkSession(sessionEntity: SessionEntity): Boolean = controller.forkSession(sessionEntity)
    fun archiveSession(id: String, archived: Boolean) = controller.archiveSession(id, archived)
    fun deleteSession(id: String) = controller.deleteSession(id)
    fun startHuntQueue(targetsRaw: String, templateRaw: String) = controller.startHuntQueue(targetsRaw, templateRaw)
    fun stopHuntQueue() = controller.stopHuntQueue()

    fun saveNote(kind: NoteKind, key: String, body: String, tags: List<String> = emptyList()) =
        controller.saveNote(kind, key, body, tags)

    fun deleteNote(id: Long) = controller.deleteNote(id)
    fun addTodo(text: String) = controller.addTodo(text)
    fun toggleTodo(index: Int) = controller.toggleTodo(index)
    fun clearTodos() = controller.clearTodos()

    fun createFindingReport(
        title: String,
        asset: String,
        cvssVector: String,
        summary: String,
        impact: String,
        remediation: String
    ) = controller.createFindingReport(title, asset, cvssVector, summary, impact, remediation)

    fun readArtifact(id: String): String = controller.readArtifact(id)
    fun grepArtifact(id: String, pattern: String): List<String> = controller.grepArtifact(id, pattern)
    fun sliceArtifact(id: String, offset: Int, length: Int): String = controller.sliceArtifact(id, offset, length)

    fun addExperience(fingerprint: String, approach: String, outcome: String, worked: Boolean) =
        controller.addExperience(fingerprint, approach, outcome, worked)

    fun addTechnique(situation: String, steps: String, worked: Boolean) =
        controller.addTechnique(situation, steps, worked)

    fun deleteExperience(id: Long) = controller.deleteExperience(id)
    fun deleteTechnique(id: Long) = controller.deleteTechnique(id)
    fun deleteTargetHistory(host: String) = controller.deleteTargetHistory(host)

    fun saveCustomSkill(name: String, description: String, body: String) =
        controller.saveCustomSkill(name, description, body)

    fun refreshProotDiagnostics() = controller.refreshProotDiagnostics()
    fun launchTermuxUbuntuSetup(): String? = controller.launchTermuxUbuntuSetup()
    fun testProotUbuntu() = controller.testProotUbuntu()

    fun upsertProvider(profile: ProviderProfile, editIndex: Int? = null) = controller.upsertProvider(profile, editIndex)
    fun deleteProvider(index: Int) = controller.deleteProvider(index)
    fun moveProvider(index: Int, delta: Int) = controller.moveProvider(index, delta)
    fun resetProvidersToDefaults() = controller.resetProvidersToDefaults()
    fun testProviderConnection(profile: ProviderProfile) = controller.testProviderConnection(profile)
    fun clearProviderTestResult() = controller.clearProviderTestResult()

    fun updateNotifyConfig(next: NotifyConfig) = controller.updateNotifyConfig(next)
    fun resetNotifyToDefaults() = controller.resetNotifyToDefaults()
    fun testNotification() = controller.testNotification()

    fun updateSettings(transform: (BorerSettings) -> BorerSettings) = controller.updateSettings(transform)
    fun resetAllSettingsToDefaults() = controller.resetAllSettingsToDefaults()

    companion object {
        fun parseTodos(body: String?): List<Pair<Boolean, String>> = BorerSessionController.parseTodos(body)
    }
}
