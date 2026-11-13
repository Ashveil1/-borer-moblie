package com.example

import android.app.Application
import com.example.data.LearningMemory
import com.example.data.MemoryDatabase
import com.example.data.RunDatabase
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

    override fun onCreate() {
        super.onCreate()
        runDatabase = RunDatabase.get(this)
        memoryDatabase = MemoryDatabase.get(this)
        learningMemory = LearningMemory(memoryDatabase)
        workspaceDir = File(filesDir, "workspace").apply { mkdirs() }
        runsDir = File(filesDir, "runs").apply { mkdirs() }
    }
}
