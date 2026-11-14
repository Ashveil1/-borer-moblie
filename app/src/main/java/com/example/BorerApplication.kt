package com.example

import android.app.Application
import com.example.data.LearningMemory
import com.example.data.MemoryDatabase
import com.example.data.RunDatabase
import com.example.session.BorerSessionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class BorerApplication : Application() {
    lateinit var runDatabase: RunDatabase
        private set
    lateinit var memoryDatabase: MemoryDatabase
        private set
    lateinit var learningMemory: LearningMemory
        private set
    lateinit var workspaceDir: File
        private set
    lateinit var runsDir: File
        private set

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val sessionController: BorerSessionController by lazy { BorerSessionController(this, appScope) }

    override fun onCreate() {
        super.onCreate()
        runDatabase = RunDatabase.get(this)
        memoryDatabase = MemoryDatabase.get(this)
        learningMemory = LearningMemory(memoryDatabase)
        workspaceDir = File(filesDir, "workspace").apply { mkdirs() }
        runsDir = File(filesDir, "runs").apply { mkdirs() }
    }
}
