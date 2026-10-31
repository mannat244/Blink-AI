package com.example.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

@Entity(tableName = "prompt_presets")
data class PromptPresetEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val shortBadge: String,
    val instruction: String,
    val isBuiltIn: Boolean = false,
    val forceWebSearch: Boolean = false,
    val forceShortOutput: Boolean = false,
    val sortOrder: Int = 0
)

@Entity(tableName = "session_history")
data class SessionHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val promptTitle: String,
    val extractedQuestion: String,
    val shortSummary: String,
    val detailedAnswer: String,
    val isShortAnswer: Boolean,
    val category: String,
    val verificationBadgeName: String,
    val verificationNote: String,
    val sourcesJson: String = "[]",
    val imageCount: Int,
    val fastModelUsed: String,
    val verifiedModelUsed: String,
    val latencyMs: Long,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toBlinkAnswer(): BlinkAnswer {
        val badge = try {
            VerificationBadge.valueOf(verificationBadgeName)
        } catch (_: Exception) {
            VerificationBadge.FAST_INITIAL
        }
        return BlinkAnswer(
            shortSummary = shortSummary,
            detailedAnswer = detailedAnswer,
            isShortAnswer = isShortAnswer,
            detectedCategory = category,
            extractedQuestion = extractedQuestion,
            fastModelUsed = fastModelUsed,
            fastLatencyMs = latencyMs,
            verificationBadge = badge,
            verificationNote = verificationNote,
            verifiedModelUsed = verifiedModelUsed,
            timestamp = timestamp
        )
    }
}

@Dao
interface BlinkDao {
    @Query("SELECT * FROM prompt_presets ORDER BY sortOrder ASC, id ASC")
    fun getAllPrompts(): Flow<List<PromptPresetEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPrompts(prompts: List<PromptPresetEntity>)

    @Query("DELETE FROM prompt_presets")
    suspend fun clearAllPrompts()

    @Query("SELECT * FROM session_history ORDER BY timestamp DESC")
    fun getAllHistory(): Flow<List<SessionHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(entry: SessionHistoryEntity): Long

    @Query("DELETE FROM session_history WHERE id = :id")
    suspend fun deleteHistoryById(id: Int)

    @Query("DELETE FROM session_history")
    suspend fun clearAllHistory()
}

@Database(
    entities = [PromptPresetEntity::class, SessionHistoryEntity::class],
    version = 1,
    exportSchema = false
)
abstract class BlinkDatabase : RoomDatabase() {
    abstract fun blinkDao(): BlinkDao

    companion object {
        @Volatile
        private var INSTANCE: BlinkDatabase? = null

        fun getInstance(context: Context): BlinkDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    BlinkDatabase::class.java,
                    "blink_ai_local.db"
                ).fallbackToDestructiveMigration(dropAllTables = true).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class BlinkRepository(private val dao: BlinkDao) {
    val allPrompts: Flow<List<PromptPresetEntity>> = dao.getAllPrompts()
    val allHistory: Flow<List<SessionHistoryEntity>> = dao.getAllHistory()

    /**
     * Ensures strictly the two preconfigured prompts exist with the latest instructions:
     * 1. MCQ Answer (solve this MCQ and choose the correct option accurately without giving any explanation)
     * 2. Solve Accurately (direct optimal code without comments, or direct accurate solution)
     */
    suspend fun ensureDefaultPromptsSeeded() {
        val current = dao.getAllPrompts().first()
        val expected = defaultPromptPresets()
        if (current.size != expected.size ||
            current.map { Triple(it.id, it.title, it.instruction) } != expected.map { Triple(it.id, it.title, it.instruction) }
        ) {
            dao.clearAllPrompts()
            dao.insertPrompts(expected)
        }
    }

    suspend fun recordSession(
        promptTitle: String,
        answer: BlinkAnswer,
        imageCount: Int
    ): Long {
        val entity = SessionHistoryEntity(
            promptTitle = promptTitle,
            extractedQuestion = answer.extractedQuestion,
            shortSummary = answer.shortSummary,
            detailedAnswer = answer.detailedAnswer,
            isShortAnswer = answer.isShortAnswer,
            category = answer.detectedCategory,
            verificationBadgeName = answer.verificationBadge.name,
            verificationNote = answer.verificationNote,
            sourcesJson = "[]",
            imageCount = imageCount,
            fastModelUsed = answer.fastModelUsed,
            verifiedModelUsed = answer.verifiedModelUsed,
            latencyMs = answer.fastLatencyMs,
            timestamp = answer.timestamp
        )
        return dao.insertHistory(entity)
    }

    suspend fun deleteHistoryItem(id: Int) = dao.deleteHistoryById(id)

    suspend fun clearAllHistory() = dao.clearAllHistory()

    companion object {
        const val PROMPT_ID_MCQ = 1
        const val PROMPT_ID_SOLVE = 2

        const val MCQ_CORE_INSTRUCTION =
            "Solve this MCQ and choose the correct option accurately without giving any explanation. Output ONLY the correct option (e.g., Option B: <exact option text>) and nothing else."

        fun buildSolveInstruction(codingLanguage: CodingLanguage = CodingLanguage.CPP): String {
            return buildString {
                append("Solve the given question with 100% accuracy. ")
                append("If it is an MCQ, solve this MCQ and choose the correct option accurately without giving any explanation. ")
                append("If it is an SQL or database query question, provide ONLY the exact, accurate SQL query directly without any explanation or comments. ")
                append("If it is a coding problem, give the code implementation directly in ${codingLanguage.promptName}. ")
                append("Write human-readable yet non-verbose, optimized code that uses the most optimal time and space complexity method for this question. ")
                append("Do NOT include code comments (no // or # comments) and do NOT include any unnecessary text, introductions, or explanations in the answer. ")
                append("If it is a non-coding question, provide the direct, accurate answer concisely without unnecessary fluff.")
            }
        }

        fun buildPromptForPreset(
            preset: PromptPresetEntity,
            codingLanguage: CodingLanguage = CodingLanguage.CPP
        ): String {
            return if (preset.id == PROMPT_ID_MCQ || preset.forceShortOutput) {
                MCQ_CORE_INSTRUCTION
            } else {
                buildSolveInstruction(codingLanguage)
            }
        }

        /**
         * Strictly the TWO preconfigured prompts requested:
         * 1. MCQ Answer — solve this MCQ and choose the correct option accurately without giving any explanation.
         * 2. Solve Accurately — direct optimal code (no comments/fluff) in selected language, or direct accurate answer.
         */
        fun defaultPromptPresets(): List<PromptPresetEntity> = listOf(
            PromptPresetEntity(
                id = PROMPT_ID_MCQ,
                title = "MCQ Answer",
                shortBadge = "MCQ",
                instruction = MCQ_CORE_INSTRUCTION,
                isBuiltIn = true,
                forceWebSearch = false,
                forceShortOutput = true,
                sortOrder = 1
            ),
            PromptPresetEntity(
                id = PROMPT_ID_SOLVE,
                title = "Solve Accurately",
                shortBadge = "Solve",
                instruction = buildSolveInstruction(CodingLanguage.CPP),
                isBuiltIn = true,
                forceWebSearch = false,
                forceShortOutput = false,
                sortOrder = 2
            )
        )
    }
}
