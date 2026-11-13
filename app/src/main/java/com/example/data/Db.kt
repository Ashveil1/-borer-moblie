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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant

enum class NoteKind(val wire: String) {
    ASSET("asset"),
    ENDPOINT("endpoint"),
    PARAM("param"),
    HYPOTHESIS("hypothesis"),
    FINDING("finding"),
    CREDENTIAL("credential"),
    BLOCKED("blocked"),
    FACT("fact"),
    TODO("todo");

    companion object {
        fun fromWire(value: String?): NoteKind =
            entries.firstOrNull { it.wire.equals(value?.trim(), ignoreCase = true) } ?: FACT
    }
}

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val model: String,
    val baseUrl: String,
    val contextWindow: Int,
    val turns: Int = 0,
    val messageCount: Int = 0,
    val archived: Boolean = false,
    val summary: String? = null,
    val outcome: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val totalPromptTokens: Int = 0,
    val totalCompletionTokens: Int = 0,
    val totalCachedTokens: Int = 0,
    val requestsAllowed: Int = 0,
    val requestsDenied: Int = 0
)

@Entity(
    tableName = "notes",
    indices = [
        Index(value = ["runId", "kind"]),
        Index(value = ["runId", "kind", "key"], unique = true)
    ]
)
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: String,
    val kind: String,
    val key: String,
    val body: String,
    val tagsJson: String = "[]",
    val linksJson: String = "[]",
    val createdAt: String,
    val updatedAt: String
)

@Entity(tableName = "artifacts", indices = [Index(value = ["runId"])])
data class ArtifactEntity(
    @PrimaryKey val id: String,
    val runId: String,
    val kind: String,
    val label: String,
    val bytes: Long,
    val lines: Int,
    val path: String,
    val createdAt: String
)

@Entity(tableName = "findings", indices = [Index(value = ["runId"])])
data class FindingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: String,
    val title: String,
    val severity: String,
    val cvss: Double?,
    val cvssVector: String? = null,
    val asset: String,
    val cwe: String? = null,
    val noteId: Long?,
    val status: String = "draft",
    val reportPath: String? = null,
    val createdAt: String
)

@Entity(tableName = "jobs", indices = [Index(value = ["runId"])])
data class JobEntity(
    @PrimaryKey val id: String,
    val runId: String,
    val command: String,
    val pid: Long? = null,
    val logPath: String,
    val startedAt: String,
    val endedAt: String? = null,
    val exitCode: Int? = null,
    val status: String
)

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions WHERE archived = 0 ORDER BY updatedAt DESC")
    fun observeActiveSessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE archived = 1 ORDER BY updatedAt DESC")
    fun observeArchivedSessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id LIMIT 1")
    suspend fun getSession(id: String): SessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: SessionEntity)

    @Query("UPDATE sessions SET archived = :archived, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean, updatedAt: String)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSession(id: String)

    @Query("DELETE FROM sessions WHERE turns = 0 AND messageCount = 0 AND title = '(new session)'")
    suspend fun deleteEmptySessions()
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE runId = :runId ORDER BY updatedAt DESC")
    fun observeNotes(runId: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE runId = :runId AND (:kind IS NULL OR kind = :kind) ORDER BY updatedAt DESC LIMIT :limit")
    fun queryNotesSync(runId: String, kind: String?, limit: Int): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1")
    fun getNoteSync(id: Long): NoteEntity?

    @Query("SELECT * FROM notes WHERE runId = :runId AND kind = :kind AND `key` = :key LIMIT 1")
    fun findByKindKeySync(runId: String, kind: String, key: String): NoteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertSync(note: NoteEntity): Long

    @Query("UPDATE notes SET body = :body, tagsJson = :tagsJson, linksJson = :linksJson, updatedAt = :updatedAt WHERE id = :id")
    fun updateSync(id: Long, body: String, tagsJson: String, linksJson: String, updatedAt: String)

    @Query("DELETE FROM notes WHERE id = :id")
    fun deleteByIdSync(id: Long): Int

    @Query("DELETE FROM notes WHERE runId = :runId")
    suspend fun deleteByRun(runId: String)

    @Query("SELECT kind, COUNT(*) AS cnt FROM notes WHERE runId = :runId GROUP BY kind ORDER BY kind")
    fun countByKindSync(runId: String): List<KindCount>
}

data class KindCount(val kind: String, val cnt: Int)

@Dao
interface ArtifactDao {
    @Query("SELECT * FROM artifacts WHERE runId = :runId ORDER BY createdAt DESC")
    fun observeArtifacts(runId: String): Flow<List<ArtifactEntity>>

    @Query("SELECT * FROM artifacts WHERE runId = :runId ORDER BY createdAt ASC")
    fun listArtifactsSync(runId: String): List<ArtifactEntity>

    @Query("SELECT * FROM artifacts WHERE id = :id LIMIT 1")
    fun getArtifactSync(id: String): ArtifactEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertSync(artifact: ArtifactEntity)

    @Query("SELECT COUNT(*) AS cnt, COALESCE(SUM(bytes), 0) AS totalBytes FROM artifacts WHERE runId = :runId")
    fun summarySync(runId: String): ArtifactSummary

    @Query("DELETE FROM artifacts WHERE runId = :runId")
    suspend fun deleteByRun(runId: String)
}

data class ArtifactSummary(val cnt: Int, val totalBytes: Long)

@Dao
interface FindingDao {
    @Query("SELECT * FROM findings WHERE runId = :runId ORDER BY id DESC")
    fun observeFindings(runId: String): Flow<List<FindingEntity>>

    @Query("SELECT * FROM findings ORDER BY createdAt DESC")
    fun observeAllFindings(): Flow<List<FindingEntity>>

    @Query("SELECT * FROM findings WHERE runId = :runId ORDER BY id ASC")
    fun listFindingsSync(runId: String): List<FindingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertSync(finding: FindingEntity): Long

    @Query("UPDATE findings SET reportPath = :reportPath, status = :status WHERE id = :id")
    fun updateReportSync(id: Long, reportPath: String, status: String)

    @Query("SELECT * FROM findings WHERE id = :id LIMIT 1")
    fun getByIdSync(id: Long): FindingEntity?

    @Query("SELECT COUNT(*) FROM findings WHERE runId = :runId")
    fun countSync(runId: String): Int

    @Query("DELETE FROM findings WHERE runId = :runId")
    suspend fun deleteByRun(runId: String)
}

@Dao
interface JobDao {
    @Query("SELECT * FROM jobs WHERE runId = :runId ORDER BY startedAt DESC")
    fun observeJobs(runId: String): Flow<List<JobEntity>>

    @Query("SELECT * FROM jobs WHERE runId = :runId ORDER BY startedAt DESC LIMIT 50")
    fun listJobsSync(runId: String): List<JobEntity>

    @Query("SELECT * FROM jobs WHERE id = :id LIMIT 1")
    fun getJobSync(id: String): JobEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertSync(job: JobEntity)

    @Query("UPDATE jobs SET endedAt = :endedAt, exitCode = :exitCode, status = :status WHERE id = :id")
    fun completeSync(id: String, endedAt: String, exitCode: Int, status: String)

    @Query("DELETE FROM jobs WHERE runId = :runId")
    suspend fun deleteByRun(runId: String)
}

@Database(
    entities = [
        SessionEntity::class,
        NoteEntity::class,
        ArtifactEntity::class,
        FindingEntity::class,
        JobEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class RunDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun noteDao(): NoteDao
    abstract fun artifactDao(): ArtifactDao
    abstract fun findingDao(): FindingDao
    abstract fun jobDao(): JobDao

    companion object {
        @Volatile
        private var instance: RunDatabase? = null

        fun get(context: Context): RunDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    RunDatabase::class.java,
                    "borer_runs.db"
                ).build().also { instance = it }
            }
    }
}

data class NoteModel(
    val id: Long,
    val runId: String,
    val kind: NoteKind,
    val key: String,
    val body: String,
    val tags: List<String>,
    val links: List<Long>,
    val created: String,
    val updated: String
)

class RunStore(
    val runId: String,
    private val db: RunDatabase
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun putNote(
        kind: NoteKind,
        key: String,
        body: String,
        tags: List<String> = emptyList(),
        links: List<Long> = emptyList()
    ): NoteModel {
        val now = Instant.now().toString()
        val tagsJson = json.encodeToString(tags)
        val linksJson = json.encodeToString(links)
        val existing = db.noteDao().findByKindKeySync(runId, kind.wire, key)
        val id = if (existing != null) {
            db.noteDao().updateSync(existing.id, body, tagsJson, linksJson, now)
            existing.id
        } else {
            db.noteDao().insertSync(
                NoteEntity(
                    runId = runId,
                    kind = kind.wire,
                    key = key,
                    body = body,
                    tagsJson = tagsJson,
                    linksJson = linksJson,
                    createdAt = now,
                    updatedAt = now
                )
            )
        }
        return getNote(id)!!
    }

    fun getNote(id: Long): NoteModel? = db.noteDao().getNoteSync(id)?.toModel()

    fun queryNotes(
        kind: NoteKind? = null,
        text: String? = null,
        tag: String? = null,
        limit: Int = 50
    ): List<NoteModel> {
        val raw = db.noteDao().queryNotesSync(runId, kind?.wire, limit * 2).map { it.toModel() }
        val terms = text?.trim()?.lowercase()?.split(Regex("\\s+"))?.filter { it.isNotBlank() }.orEmpty()
        return raw.filter { n ->
            val matchesTag = tag.isNullOrBlank() || n.tags.any { it.equals(tag, ignoreCase = true) }
            val matchesText = terms.isEmpty() || terms.any { term ->
                n.key.lowercase().contains(term) || n.body.lowercase().contains(term) || n.tags.any { it.lowercase().contains(term) }
            }
            matchesTag && matchesText
        }.take(limit)
    }

    fun deleteNote(id: Long): Boolean = db.noteDao().deleteByIdSync(id) > 0

    fun registerArtifact(id: String, kind: String, label: String, bytes: Long, lines: Int, path: String) {
        db.artifactDao().upsertSync(
            ArtifactEntity(
                id = id,
                runId = runId,
                kind = kind,
                label = label,
                bytes = bytes,
                lines = lines,
                path = path,
                createdAt = Instant.now().toString()
            )
        )
    }

    fun addFinding(
        title: String,
        severity: String,
        cvss: Double?,
        cvssVector: String?,
        asset: String,
        cwe: String?,
        noteId: Long?
    ): FindingEntity {
        val entity = FindingEntity(
            runId = runId,
            title = title,
            severity = severity,
            cvss = cvss,
            cvssVector = cvssVector,
            asset = asset,
            cwe = cwe,
            noteId = noteId,
            status = "draft",
            createdAt = Instant.now().toString()
        )
        val id = db.findingDao().insertSync(entity)
        return db.findingDao().getByIdSync(id) ?: entity.copy(id = id)
    }

    fun updateFindingReport(id: Long, reportPath: String, status: String = "verified") {
        db.findingDao().updateReportSync(id, reportPath, status)
    }

    fun listFindings(): List<FindingEntity> = db.findingDao().listFindingsSync(runId)

    fun digest(): String {
        val counts = db.noteDao().countByKindSync(runId)
        val arts = db.artifactDao().summarySync(runId)
        val findings = db.findingDao().countSync(runId)
        val notesPart = counts.joinToString(" ") { "${it.kind}=${it.cnt}" }.ifBlank { "none" }
        return "notes: $notesPart | artifacts: ${arts.cnt} (${arts.totalBytes} bytes) | findings: $findings"
    }

    private fun NoteEntity.toModel(): NoteModel {
        val parsedTags = runCatching { json.decodeFromString<List<String>>(tagsJson) }.getOrDefault(emptyList())
        val parsedLinks = runCatching { json.decodeFromString<List<Long>>(linksJson) }.getOrDefault(emptyList())
        return NoteModel(
            id = id,
            runId = runId,
            kind = NoteKind.fromWire(kind),
            key = key,
            body = body,
            tags = parsedTags,
            links = parsedLinks,
            created = createdAt,
            updated = updatedAt
        )
    }
}
