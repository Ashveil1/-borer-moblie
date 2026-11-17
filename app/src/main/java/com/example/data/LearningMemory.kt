package com.example.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Entity(
    tableName = "experience",
    indices = [Index(value = ["fingerprint", "approach"], unique = true)]
)
data class ExperienceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fingerprint: String,
    val approach: String,
    val outcome: String = "",
    val success: Int = 0,
    val attempts: Int = 1,
    val updatedAt: String
) {
    val score: Double
        get() = if (attempts <= 0) 0.0 else (success + 1.0) / (attempts + 2.0)
}

@Entity(
    tableName = "techniques",
    indices = [Index(value = ["situation"], unique = true)]
)
data class TechniqueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val situation: String,
    val steps: String,
    val success: Int = 0,
    val attempts: Int = 1,
    val updatedAt: String
) {
    val score: Double
        get() = if (attempts <= 0) 0.0 else (success + 1.0) / (attempts + 2.0)
}

@Entity(tableName = "targets")
data class TargetHistoryEntity(
    @PrimaryKey val host: String,
    val profile: String = "",
    val findings: String = "",
    val deadEnds: String = "",
    val visits: Int = 1,
    val updatedAt: String
)

data class LearningStats(
    val experience: Int,
    val techniques: Int,
    val targets: Int,
    val confirmed: Int
)

@Dao
interface MemoryDao {
    @Query("SELECT * FROM experience ORDER BY (success + 1.0) / (attempts + 2.0) DESC, updatedAt DESC")
    fun observeExperience(): Flow<List<ExperienceEntity>>

    @Query("SELECT * FROM techniques ORDER BY (success + 1.0) / (attempts + 2.0) DESC, updatedAt DESC")
    fun observeTechniques(): Flow<List<TechniqueEntity>>

    @Query("SELECT * FROM targets ORDER BY updatedAt DESC")
    fun observeTargets(): Flow<List<TargetHistoryEntity>>

    @Query("SELECT * FROM experience WHERE fingerprint = :fingerprint AND approach = :approach LIMIT 1")
    fun findExperienceSync(fingerprint: String, approach: String): ExperienceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertExperienceSync(entity: ExperienceEntity)

    @Query("UPDATE experience SET outcome = :outcome, success = success + :incSuccess, attempts = attempts + 1, updatedAt = :updatedAt WHERE id = :id")
    fun updateExperienceSync(id: Long, outcome: String, incSuccess: Int, updatedAt: String)

    @Query("SELECT * FROM experience WHERE fingerprint = :fingerprint ORDER BY (success + 1.0) / (attempts + 2.0) DESC LIMIT :limit")
    fun experienceForSync(fingerprint: String, limit: Int): List<ExperienceEntity>

    @Query("SELECT * FROM experience WHERE fingerprint LIKE '%' || :fragment || '%' ORDER BY (success + 1.0) / (attempts + 2.0) DESC, attempts DESC LIMIT :limit")
    fun experienceLikeSync(fragment: String, limit: Int): List<ExperienceEntity>

    @Query("SELECT * FROM techniques WHERE situation = :situation LIMIT 1")
    fun findTechniqueSync(situation: String): TechniqueEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertTechniqueSync(entity: TechniqueEntity)

    @Query("UPDATE techniques SET steps = :steps, success = success + :incSuccess, attempts = attempts + 1, updatedAt = :updatedAt WHERE id = :id")
    fun updateTechniqueSync(id: Long, steps: String, incSuccess: Int, updatedAt: String)

    @Query("SELECT * FROM techniques ORDER BY (success + 1.0) / (attempts + 2.0) DESC, updatedAt DESC LIMIT :limit")
    fun topTechniquesRawSync(limit: Int): List<TechniqueEntity>

    @Query("SELECT * FROM targets WHERE host = :host LIMIT 1")
    fun getTargetSync(host: String): TargetHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertTargetSync(entity: TargetHistoryEntity)

    @Query("SELECT COUNT(*) FROM experience")
    fun countExperienceSync(): Int

    @Query("SELECT COUNT(*) FROM techniques")
    fun countTechniquesSync(): Int

    @Query("SELECT COUNT(*) FROM targets")
    fun countTargetsSync(): Int

    @Query("SELECT COALESCE(SUM(success), 0) FROM experience")
    fun sumConfirmedSync(): Int

    @Query("DELETE FROM experience WHERE id = :id")
    suspend fun deleteExperience(id: Long)

    @Query("DELETE FROM techniques WHERE id = :id")
    suspend fun deleteTechnique(id: Long)

    @Query("DELETE FROM targets WHERE host = :host")
    suspend fun deleteTarget(host: String)
}

@Database(
    entities = [
        ExperienceEntity::class,
        TechniqueEntity::class,
        TargetHistoryEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class MemoryDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao

    companion object {
        @Volatile
        private var instance: MemoryDatabase? = null

        fun get(context: Context): MemoryDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MemoryDatabase::class.java,
                    "borer_memory.db"
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
    }
}

class LearningMemory(private val db: MemoryDatabase) {
    private val dao = db.memoryDao()

    val experienceFlow: Flow<List<ExperienceEntity>> = dao.observeExperience()
    val techniquesFlow: Flow<List<TechniqueEntity>> = dao.observeTechniques()
    val targetsFlow: Flow<List<TargetHistoryEntity>> = dao.observeTargets()

    fun recordExperience(fingerprint: String, approach: String, outcome: String, worked: Boolean) {
        val now = Instant.now().toString()
        val existing = dao.findExperienceSync(fingerprint, approach)
        if (existing != null) {
            dao.updateExperienceSync(existing.id, outcome, if (worked) 1 else 0, now)
        } else {
            dao.insertExperienceSync(
                ExperienceEntity(
                    fingerprint = fingerprint,
                    approach = approach,
                    outcome = outcome,
                    success = if (worked) 1 else 0,
                    attempts = 1,
                    updatedAt = now
                )
            )
        }
    }

    fun experienceFor(fingerprint: String, limit: Int = 6): List<ExperienceEntity> =
        dao.experienceForSync(fingerprint, limit)

    fun experienceLike(fragment: String, limit: Int = 8): List<ExperienceEntity> =
        dao.experienceLikeSync(fragment, limit)

    fun recordTechnique(situation: String, steps: String, worked: Boolean) {
        val now = Instant.now().toString()
        val existing = dao.findTechniqueSync(situation)
        if (existing != null) {
            dao.updateTechniqueSync(existing.id, steps, if (worked) 1 else 0, now)
        } else {
            dao.insertTechniqueSync(
                TechniqueEntity(
                    situation = situation,
                    steps = steps,
                    success = if (worked) 1 else 0,
                    attempts = 1,
                    updatedAt = now
                )
            )
        }
    }

    fun topTechniques(limit: Int = 6, minScore: Double = 0.4): List<TechniqueEntity> =
        dao.topTechniquesRawSync(limit * 3).filter { it.score >= minScore }.take(limit)

    fun noteTarget(host: String, profile: String? = null, finding: String? = null, deadEnd: String? = null) {
        val now = Instant.now().toString()
        val row = dao.getTargetSync(host)
        if (row == null) {
            dao.upsertTargetSync(
                TargetHistoryEntity(
                    host = host,
                    profile = profile.orEmpty(),
                    findings = finding.orEmpty(),
                    deadEnds = deadEnd.orEmpty(),
                    visits = 1,
                    updatedAt = now
                )
            )
            return
        }
        fun merge(old: String, next: String?): String {
            if (next.isNullOrBlank()) return old
            if (old.contains(next)) return old
            return if (old.isBlank()) next else "$old; $next"
        }
        dao.upsertTargetSync(
            row.copy(
                profile = merge(row.profile, profile),
                findings = merge(row.findings, finding),
                deadEnds = merge(row.deadEnds, deadEnd),
                visits = row.visits + 1,
                updatedAt = now
            )
        )
    }

    fun targetHistory(host: String): TargetHistoryEntity? = dao.getTargetSync(host)

    fun stats(): LearningStats = LearningStats(
        experience = dao.countExperienceSync(),
        techniques = dao.countTechniquesSync(),
        targets = dao.countTargetsSync(),
        confirmed = dao.sumConfirmedSync()
    )

    fun brief(fingerprintHint: String? = null): String {
        val lines = mutableListOf<String>()
        val techniques = topTechniques(5)
        if (techniques.isNotEmpty()) {
            lines.add("Techniques that have worked before:")
            for (t in techniques) {
                lines.add("- when ${t.situation}: ${t.steps} (${t.success}/${t.attempts} confirmed, score ${(t.score * 100).toInt()}%)")
            }
        }
        if (!fingerprintHint.isNullOrBlank()) {
            val exp = experienceLike(fingerprintHint, 5)
            if (exp.isNotEmpty()) {
                lines.add("Prior results on $fingerprintHint:")
                for (e in exp) {
                    val outcomeText = e.outcome.ifBlank { if (e.success > 0) "worked" else "no result" }
                    lines.add("- ${e.approach} -> $outcomeText (${e.success}/${e.attempts})")
                }
            }
        }
        return lines.joinToString("\n")
    }

    suspend fun deleteExperience(id: Long) = dao.deleteExperience(id)
    suspend fun deleteTechnique(id: Long) = dao.deleteTechnique(id)
    suspend fun deleteTarget(host: String) = dao.deleteTarget(host)
}
