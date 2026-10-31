package com.example.ai

import com.example.data.BlinkRepository
import com.example.data.BlinkSettings
import com.example.data.GeminiModelQuotaStatus
import com.example.data.GeminiModelTier
import com.example.data.GeminiRoutingMode
import com.example.data.GeminiVisionModel
import com.example.data.GroqVisionModel
import com.example.data.PromptPresetEntity
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Represents a single step in the Economy, Premium, or Manual execution pipeline.
 */
sealed class PipelineExecutionCandidate {
    data class GeminiStep(val model: GeminiVisionModel) : PipelineExecutionCandidate()
    data class GroqBackupStep(
        val modelId: String,
        val tierReason: GeminiModelTier
    ) : PipelineExecutionCandidate()
}

/**
 * Tracks real-time and server-synced RPM, TPM, and RPD rate limits for all 6 Gemini models
 * using the exact supplied configuration reference (dated 31 October 2026):
 *
 * A. Economy models (Everyday questions — 15 RPM, 250,000 TPM, 500 RPD):
 *    1. Gemini 3.5 Flash Lite (`gemini-3.5-flash-lite`)
 *    2. Gemini 3.1 Flash Lite (`gemini-3.1-flash-lite`)
 *    3. Configured backup provider's fast model (Groq, when configured)
 *
 * B. Premium models (Stronger reasoning — 5 RPM, 250,000 TPM, 20 RPD):
 *    1. Gemini 3.8 Flash (`gemini-3.8-flash`) — automatically skipped when API reports quota exhaustion
 *    2. Gemini 3.6 Flash (`gemini-3.6-flash`)
 *    3. Gemini 3 Flash (`gemini-3-flash`)
 *    4. Gemini 2.5 Flash (`gemini-2.5-flash`)
 *    5. Configured backup provider's stronger model (Groq, when configured)
 */
class GeminiServerQuotaTracker(
    private val onQuotaStateChanged: ((List<GeminiModelQuotaStatus>, String) -> Unit)? = null
) {

    private data class MinuteSample(
        val timestampMs: Long,
        val tokens: Int
    )

    private data class MutableModelQuotaRecord(
        var rpdUsed: Int = 0,
        var isServerDailyExhausted: Boolean = false,
        var minuteCooldownUntilMs: Long = 0L,
        var lastServerSyncEpochMs: Long = 0L,
        var lastServerNote: String = "Ready",
        val minuteSamples: MutableList<MinuteSample> = mutableListOf()
    )

    private val lock = Any()
    private var currentDayKey: String = formatUtcDayKey(System.currentTimeMillis())
    private val records: MutableMap<GeminiVisionModel, MutableModelQuotaRecord> = mutableMapOf()

    init {
        GeminiVisionModel.entries.forEach { model ->
            records[model] = MutableModelQuotaRecord()
        }
    }

    /**
     * Records a successful HTTP 200 response from the Gemini server, syncing token usage
     * (`usage.total_tokens` or `usageMetadata.totalTokenCount`) and incrementing RPM/TPM/RPD counters.
     */
    fun recordServerSuccess(
        modelId: String,
        tokensUsed: Int,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val model = GeminiVisionModel.fromModelIdOrNull(modelId) ?: return
        val safeTokens = tokensUsed.coerceAtLeast(1)
        var snapshotList: List<GeminiModelQuotaStatus> = emptyList()
        var exportedJson = ""

        synchronized(lock) {
            ensureDayRolloverLocked(nowMs)
            val rec = records.getOrPut(model) { MutableModelQuotaRecord() }
            pruneMinuteWindowLocked(rec, nowMs)
            rec.rpdUsed += 1
            rec.minuteSamples.add(MinuteSample(timestampMs = nowMs, tokens = safeTokens))
            rec.minuteCooldownUntilMs = 0L
            if (rec.rpdUsed >= model.rpdLimit) {
                rec.isServerDailyExhausted = true
                rec.lastServerNote = "Daily limit reached (${rec.rpdUsed}/${model.rpdLimit} RPD)"
            } else {
                rec.isServerDailyExhausted = false
                rec.lastServerNote = "Server Synced • ${rec.rpdUsed}/${model.rpdLimit} RPD (+$safeTokens tok)"
            }
            rec.lastServerSyncEpochMs = nowMs

            snapshotList = buildStatusListLocked(nowMs)
            exportedJson = serializeLocked()
        }

        onQuotaStateChanged?.invoke(snapshotList, exportedJson)
    }

    /**
     * Records an HTTP 429 rate-limit / quota exhaustion response from the Gemini server.
     * Distinguishes daily RPD exhaustion (e.g., Gemini 3.8 Flash 20/20 RPD limit) from
     * transient 60-second RPM/TPM rate limiting so exhausted models are automatically skipped.
     */
    fun recordServerQuotaExhausted(
        modelId: String,
        responseBody: String = "",
        retryAfterSeconds: Int? = null,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val model = GeminiVisionModel.fromModelIdOrNull(modelId) ?: return
        var snapshotList: List<GeminiModelQuotaStatus> = emptyList()
        var exportedJson = ""

        synchronized(lock) {
            ensureDayRolloverLocked(nowMs)
            val rec = records.getOrPut(model) { MutableModelQuotaRecord() }
            pruneMinuteWindowLocked(rec, nowMs)

            val lowerBody = responseBody.lowercase(Locale.US)
            val mentionsPerMinuteOnly = (lowerBody.contains("per minute") ||
                lowerBody.contains("rpm") ||
                lowerBody.contains("tpm") ||
                lowerBody.contains("tokens per minute")) &&
                !lowerBody.contains("per day") &&
                !lowerBody.contains("daily") &&
                !lowerBody.contains("rpd")

            val isDailyExhaustion = lowerBody.contains("per day") ||
                lowerBody.contains("daily") ||
                lowerBody.contains("rpd") ||
                lowerBody.contains("day") ||
                !mentionsPerMinuteOnly

            rec.lastServerSyncEpochMs = nowMs
            if (isDailyExhaustion) {
                rec.isServerDailyExhausted = true
                rec.rpdUsed = maxOf(rec.rpdUsed, model.rpdLimit)
                rec.lastServerNote =
                    "Server 429: Daily RPD Exhausted (${rec.rpdUsed}/${model.rpdLimit}) — Auto-Skipping"
            } else {
                val cooldownSec = (retryAfterSeconds ?: 60).coerceIn(10, 120)
                rec.minuteCooldownUntilMs = nowMs + cooldownSec * 1_000L
                rec.lastServerNote = "Server 429: RPM/TPM Cooldown (${cooldownSec}s)"
            }

            snapshotList = buildStatusListLocked(nowMs)
            exportedJson = serializeLocked()
        }

        onQuotaStateChanged?.invoke(snapshotList, exportedJson)
    }

    /**
     * Returns true if the given [model] should be automatically skipped because the server reported
     * quota exhaustion or its tracked RPD / RPM / TPM limit is currently reached.
     */
    fun shouldSkipModel(
        model: GeminiVisionModel,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        synchronized(lock) {
            ensureDayRolloverLocked(nowMs)
            val rec = records[model] ?: return false
            pruneMinuteWindowLocked(rec, nowMs)
            val rpmUsed = rec.minuteSamples.size
            val tpmUsed = rec.minuteSamples.sumOf { it.tokens }
            val inMinuteCooldown = nowMs < rec.minuteCooldownUntilMs
            return rec.isServerDailyExhausted ||
                rec.rpdUsed >= model.rpdLimit ||
                inMinuteCooldown ||
                rpmUsed >= model.rpmLimit ||
                tpmUsed >= model.tpmLimit
        }
    }

    /**
     * Returns the current server-synced quota status for all 6 Gemini models.
     */
    fun getAllQuotaStatuses(nowMs: Long = System.currentTimeMillis()): List<GeminiModelQuotaStatus> {
        synchronized(lock) {
            ensureDayRolloverLocked(nowMs)
            return buildStatusListLocked(nowMs)
        }
    }

    fun getQuotaStatusForModel(
        model: GeminiVisionModel,
        nowMs: Long = System.currentTimeMillis()
    ): GeminiModelQuotaStatus {
        return getAllQuotaStatuses(nowMs).first { it.model == model }
    }

    /**
     * Resets all local quota counters (useful when the user switches to a fresh Gemini API key).
     */
    fun resetAllQuotas(nowMs: Long = System.currentTimeMillis()) {
        var snapshotList: List<GeminiModelQuotaStatus> = emptyList()
        var exportedJson = ""
        synchronized(lock) {
            currentDayKey = formatUtcDayKey(nowMs)
            GeminiVisionModel.entries.forEach { model ->
                records[model] = MutableModelQuotaRecord(
                    lastServerSyncEpochMs = nowMs,
                    lastServerNote = "Reset • Ready"
                )
            }
            snapshotList = buildStatusListLocked(nowMs)
            exportedJson = serializeLocked()
        }
        onQuotaStateChanged?.invoke(snapshotList, exportedJson)
    }

    fun exportToJson(nowMs: Long = System.currentTimeMillis()): String {
        synchronized(lock) {
            ensureDayRolloverLocked(nowMs)
            return serializeLocked()
        }
    }

    fun importFromJson(jsonString: String, nowMs: Long = System.currentTimeMillis()) {
        if (jsonString.isBlank()) return
        synchronized(lock) {
            runCatching {
                val root = JSONObject(jsonString)
                val savedDay = root.optString("dayKey", "")
                val today = formatUtcDayKey(nowMs)
                if (savedDay != today) {
                    currentDayKey = today
                    return@runCatching
                }
                currentDayKey = savedDay
                val modelsArr = root.optJSONArray("models") ?: return@runCatching
                for (i in 0 until modelsArr.length()) {
                    val item = modelsArr.optJSONObject(i) ?: continue
                    val modelId = item.optString("modelId", "")
                    val model = GeminiVisionModel.fromModelIdOrNull(modelId) ?: continue
                    val rec = records.getOrPut(model) { MutableModelQuotaRecord() }
                    rec.rpdUsed = item.optInt("rpdUsed", 0).coerceAtLeast(0)
                    rec.isServerDailyExhausted = item.optBoolean("isServerDailyExhausted", false) ||
                        rec.rpdUsed >= model.rpdLimit
                    rec.minuteCooldownUntilMs = item.optLong("minuteCooldownUntilMs", 0L)
                    rec.lastServerSyncEpochMs = item.optLong("lastServerSyncEpochMs", 0L)
                    rec.lastServerNote = item.optString("lastServerNote", "Ready")
                }
            }
        }
    }

    private fun ensureDayRolloverLocked(nowMs: Long) {
        val today = formatUtcDayKey(nowMs)
        if (today != currentDayKey) {
            currentDayKey = today
            GeminiVisionModel.entries.forEach { model ->
                records[model] = MutableModelQuotaRecord()
            }
        }
    }

    private fun pruneMinuteWindowLocked(rec: MutableModelQuotaRecord, nowMs: Long) {
        val cutoff = nowMs - 60_000L
        rec.minuteSamples.removeAll { it.timestampMs < cutoff }
        if (rec.minuteCooldownUntilMs > 0L && nowMs >= rec.minuteCooldownUntilMs) {
            rec.minuteCooldownUntilMs = 0L
        }
    }

    private fun buildStatusListLocked(nowMs: Long): List<GeminiModelQuotaStatus> {
        return GeminiVisionModel.entries.map { model ->
            val rec = records.getOrPut(model) { MutableModelQuotaRecord() }
            pruneMinuteWindowLocked(rec, nowMs)
            val rpmUsed = rec.minuteSamples.size
            val tpmUsed = rec.minuteSamples.sumOf { it.tokens }
            val cooldownRemSec = if (nowMs < rec.minuteCooldownUntilMs) {
                ((rec.minuteCooldownUntilMs - nowMs + 999L) / 1000L).toInt()
            } else {
                0
            }
            GeminiModelQuotaStatus(
                model = model,
                rpmUsed = rpmUsed,
                tpmUsed = tpmUsed,
                rpdUsed = rec.rpdUsed,
                isServerDailyExhausted = rec.isServerDailyExhausted || rec.rpdUsed >= model.rpdLimit,
                isServerMinuteCooldown = cooldownRemSec > 0,
                cooldownRemainingSeconds = cooldownRemSec,
                lastServerSyncEpochMs = rec.lastServerSyncEpochMs,
                lastServerNote = rec.lastServerNote
            )
        }
    }

    private fun serializeLocked(): String {
        val modelsArray = JSONArray()
        GeminiVisionModel.entries.forEach { model ->
            val rec = records[model] ?: return@forEach
            modelsArray.put(
                JSONObject().apply {
                    put("modelId", model.modelId)
                    put("rpdUsed", rec.rpdUsed)
                    put("isServerDailyExhausted", rec.isServerDailyExhausted)
                    put("minuteCooldownUntilMs", rec.minuteCooldownUntilMs)
                    put("lastServerSyncEpochMs", rec.lastServerSyncEpochMs)
                    put("lastServerNote", rec.lastServerNote)
                }
            )
        }
        return JSONObject().apply {
            put("dayKey", currentDayKey)
            put("models", modelsArray)
        }.toString()
    }

    companion object {
        private fun formatUtcDayKey(epochMs: Long): String {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            return sdf.format(Date(epochMs))
        }

        /**
         * Extracts exact token usage reported by the Gemini server (`/v1beta/interactions` `usage` object
         * or `:generateContent` `usageMetadata` object), or falls back to a deterministic token estimate.
         */
        fun extractServerTokenUsage(
            responseBody: String,
            promptCharCount: Int = 120,
            imageCount: Int = 0
        ): Int {
            runCatching {
                val root = JSONObject(responseBody)
                val usageObj = root.optJSONObject("usage")
                if (usageObj != null) {
                    val total = usageObj.optInt("total_tokens", 0)
                        .takeIf { it > 0 }
                        ?: (usageObj.optInt("input_tokens", 0) + usageObj.optInt("output_tokens", 0))
                    if (total > 0) return total
                }
                val metadataObj = root.optJSONObject("usageMetadata")
                if (metadataObj != null) {
                    val total = metadataObj.optInt("totalTokenCount", 0)
                        .takeIf { it > 0 }
                        ?: (metadataObj.optInt("promptTokenCount", 0) + metadataObj.optInt("candidatesTokenCount", 0))
                    if (total > 0) return total
                }
            }
            return ((promptCharCount / 4).coerceAtLeast(16) + (imageCount * 258) + 64)
        }

        /**
         * Classifies whether a request belongs to the **Economy** tier (500 RPD Lite models)
         * or the **Premium** tier (20 RPD Flash models) according to the supplied rules:
         *
         * A. Economy models (`GeminiModelTier.ECONOMY`):
         *    - Simple factual questions, definitions, general knowledge, straightforward questions,
         *      basic explanations, text extraction, simple summarisation, questions not requiring extensive reasoning.
         *    - Preserves the 500 daily requests on each Lite model and avoids wasting 20 RPD Premium requests.
         *
         * B. Premium models (`GeminiModelTier.PREMIUM`):
         *    - Complex mathematical problems, DSA and programming problems, code debugging,
         *      complex technical questions, multi-step reasoning, architecture and engineering decisions,
         *      detailed analysis, and questions explicitly asking for deeper reasoning.
         */
        fun classifyQuestionTier(
            promptPreset: PromptPresetEntity,
            customTypedPrompt: String = "",
            supplementalContext: String = ""
        ): GeminiModelTier {
            val combined = "$customTypedPrompt $supplementalContext".trim().lowercase(Locale.US)

            if (combined.isNotEmpty()) {
                val hasPremiumIndicators = PREMIUM_KEYWORDS.any { keyword ->
                    combined.contains(keyword)
                }
                if (hasPremiumIndicators) {
                    return GeminiModelTier.PREMIUM
                }

                val hasEconomyIndicators = ECONOMY_KEYWORDS.any { keyword ->
                    combined.contains(keyword)
                }
                if (hasEconomyIndicators) {
                    return GeminiModelTier.ECONOMY
                }
            }

            // Default by active preconfigured mode:
            // - MCQ Answer mode -> Economy Tier (preserves 20 RPD Premium quota on everyday MCQs)
            // - Solve Accurately mode -> Premium Tier (coding, DSA, SQL, and complex math problem solving)
            return if (promptPreset.id == BlinkRepository.PROMPT_ID_MCQ || promptPreset.forceShortOutput) {
                GeminiModelTier.ECONOMY
            } else {
                GeminiModelTier.PREMIUM
            }
        }

        private val PREMIUM_KEYWORDS = listOf(
            "dsa",
            "dynamic programming",
            "algorithm",
            "leetcode",
            "codeforces",
            "time complexity",
            "space complexity",
            "big-o",
            "binary tree",
            "segment tree",
            "graph",
            "dijkstra",
            "backtracking",
            "linked list",
            "debug",
            "stack trace",
            "segmentation fault",
            "nullpointer",
            "compile error",
            "runtime error",
            "fix this code",
            "write code",
            "implement",
            "#include",
            "int main",
            "vector<",
            "def ",
            "public class",
            "select ",
            "group by",
            "inner join",
            "left join",
            "integral",
            "derivative",
            "calculus",
            "differential equation",
            "eigenvalue",
            "matrix",
            "theorem",
            "prove that",
            "proof",
            "multi-step",
            "step-by-step reasoning",
            "architecture",
            "system design",
            "distributed system",
            "microservice",
            "engineering decision",
            "detailed analysis",
            "deeper reasoning",
            "deep reasoning",
            "analyze in depth"
        )

        private val ECONOMY_KEYWORDS = listOf(
            "define ",
            "definition of",
            "what is the meaning",
            "capital of",
            "who is ",
            "when did ",
            "general knowledge",
            "simple explanation",
            "explain in a few words",
            "briefly explain",
            "extract text",
            "text extraction",
            "transcribe",
            "summarize",
            "summarisation",
            "summary of",
            "true or false",
            "synonym",
            "antonym",
            "simple factual"
        )

        /**
         * Builds the ordered execution plan for the active [settings], [targetTier], and [quotaTracker]:
         *
         * - **Economy pipeline**:
         *   1. `gemini-3.5-flash-lite` (Priority 1: 15 RPM, 250,000 TPM, 500 RPD)
         *   2. `gemini-3.1-flash-lite` (Priority 2: 15 RPM, 250,000 TPM, 500 RPD)
         *   3. Configured backup provider's fast model (Groq, when configured)
         *
         * - **Premium pipeline**:
         *   1. `gemini-3.8-flash` (Priority 1: 5 RPM, 250,000 TPM, 20 RPD — auto-skipped on quota exhaustion)
         *   2. `gemini-3.6-flash` (Priority 2: 5 RPM, 250,000 TPM, 20 RPD)
         *   3. `gemini-3-flash` (Priority 3: 5 RPM, 250,000 TPM, 20 RPD)
         *   4. `gemini-2.5-flash` (Priority 4: 5 RPM, 250,000 TPM, 20 RPD)
         *   5. Configured backup provider's stronger model (Groq, when configured)
         *
         * - **Manual model selection (`GeminiRoutingMode.MANUAL_MODEL`)**:
         *   Starts with the user's manually chosen model first (unless server-exhausted), then falls back
         *   through that model's tier pipeline and Groq backup if configured.
         */
        fun buildExecutionPlan(
            settings: BlinkSettings,
            classifiedTier: GeminiModelTier,
            quotaTracker: GeminiServerQuotaTracker,
            nowMs: Long = System.currentTimeMillis()
        ): List<PipelineExecutionCandidate> {
            val effectiveTier = when (settings.geminiRoutingMode) {
                GeminiRoutingMode.AUTO_SMART -> classifiedTier
                GeminiRoutingMode.ECONOMY_PIPELINE -> GeminiModelTier.ECONOMY
                GeminiRoutingMode.PREMIUM_PIPELINE -> GeminiModelTier.PREMIUM
                GeminiRoutingMode.MANUAL_MODEL -> {
                    GeminiVisionModel.fromModelIdOrNull(settings.fastModel)?.tier ?: classifiedTier
                }
            }

            val orderedGeminiModels: List<GeminiVisionModel> = when (settings.geminiRoutingMode) {
                GeminiRoutingMode.MANUAL_MODEL -> {
                    val pinned = GeminiVisionModel.fromModelIdOrNull(settings.fastModel)
                        ?: GeminiVisionModel.GEMINI_3_8_FLASH
                    val sameTier = if (pinned.tier == GeminiModelTier.ECONOMY) {
                        GeminiVisionModel.economyPipelineOrder
                    } else {
                        GeminiVisionModel.premiumPipelineOrder
                    }
                    val otherTier = if (pinned.tier == GeminiModelTier.ECONOMY) {
                        GeminiVisionModel.premiumPipelineOrder
                    } else {
                        GeminiVisionModel.economyPipelineOrder
                    }
                    buildList {
                        add(pinned)
                        sameTier.forEach { if (!contains(it)) add(it) }
                        otherTier.forEach { if (!contains(it)) add(it) }
                    }
                }

                GeminiRoutingMode.ECONOMY_PIPELINE -> {
                    GeminiVisionModel.economyPipelineOrder
                }

                GeminiRoutingMode.PREMIUM_PIPELINE -> {
                    GeminiVisionModel.premiumPipelineOrder
                }

                GeminiRoutingMode.AUTO_SMART -> {
                    if (effectiveTier == GeminiModelTier.ECONOMY) {
                        GeminiVisionModel.economyPipelineOrder
                    } else {
                        GeminiVisionModel.premiumPipelineOrder
                    }
                }
            }

            // Separate non-exhausted models first so exhausted models (like Gemini 3.8 Flash at 20/20 RPD) are automatically skipped
            val availableGeminiModels = orderedGeminiModels.filterNot { model ->
                quotaTracker.shouldSkipModel(model, nowMs)
            }

            val candidates = mutableListOf<PipelineExecutionCandidate>()
            availableGeminiModels.forEach { model ->
                candidates.add(PipelineExecutionCandidate.GeminiStep(model))
            }

            // Configured backup provider (Groq, when groqApiKey is configured)
            if (settings.groqApiKey.isNotBlank()) {
                val backupGroqModelId = if (effectiveTier == GeminiModelTier.ECONOMY) {
                    // Fast Groq vision model for Economy pipeline backup
                    GroqVisionModel.FAST_BACKUP_MODEL_ID
                } else {
                    // Stronger Groq vision model for Premium pipeline backup
                    val userGroq = settings.groqModel.trim()
                    if (GroqVisionModel.isSupportedVisionModel(userGroq)) {
                        userGroq
                    } else {
                        GroqVisionModel.STRONG_BACKUP_MODEL_ID
                    }
                }
                candidates.add(
                    PipelineExecutionCandidate.GroqBackupStep(
                        modelId = backupGroqModelId,
                        tierReason = effectiveTier
                    )
                )
            } else {
                // If Groq backup is not configured, append the secondary Gemini tier if all primary tier models were skipped
                val crossTierFallback = if (effectiveTier == GeminiModelTier.PREMIUM) {
                    GeminiVisionModel.economyPipelineOrder
                } else {
                    GeminiVisionModel.premiumPipelineOrder
                }
                crossTierFallback
                    .filterNot { quotaTracker.shouldSkipModel(it, nowMs) }
                    .forEach { fallbackModel ->
                        val step = PipelineExecutionCandidate.GeminiStep(fallbackModel)
                        if (step !in candidates) {
                            candidates.add(step)
                        }
                    }
            }

            // Last-resort safeguard: if every single model was marked skipped locally and no Groq key is set,
            // include the primary tier models so a live server request can verify if quota has reset.
            if (candidates.isEmpty()) {
                orderedGeminiModels.forEach { model ->
                    candidates.add(PipelineExecutionCandidate.GeminiStep(model))
                }
            }

            return candidates
        }
    }
}
