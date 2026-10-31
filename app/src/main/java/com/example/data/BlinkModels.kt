package com.example.data

import android.graphics.Bitmap

enum class DispatchMode(val displayName: String, val subtitle: String) {
    AI_APP_HANDOFF(
        "AI App Handoff (Dispatcher)",
        "Transfers captured image(s) + selected mode (MCQ or Solve) to Gemini, ChatGPT, Claude, or any installed AI app"
    ),
    SMART_DISPATCH(
        "Smart Auto-Dispatch",
        "Solves in-app if API key is configured; automatically falls back to AI App Handoff if not"
    ),
    CLOUD_API_ONLY(
        "In-App Cloud API",
        "Solves directly inside Blink AI using Google Gemini or Groq"
    )
}

enum class TargetAiApp(
    val displayName: String,
    val packageName: String,
    val fallbackPackageName: String = "",
    val webUrl: String = ""
) {
    SYSTEM_CHOOSER(
        displayName = "Ask / System Share Chooser",
        packageName = "",
        webUrl = "https://gemini.google.com"
    ),
    GOOGLE_GEMINI(
        displayName = "Google Gemini",
        packageName = "com.google.android.apps.bard",
        fallbackPackageName = "com.google.android.googlequicksearchbox",
        webUrl = "https://gemini.google.com/app"
    ),
    CHATGPT(
        displayName = "ChatGPT",
        packageName = "com.openai.chatgpt",
        webUrl = "https://chatgpt.com"
    ),
    CLAUDE(
        displayName = "Claude",
        packageName = "com.anthropic.claude",
        webUrl = "https://claude.ai/new"
    ),
    PERPLEXITY(
        displayName = "Perplexity",
        packageName = "ai.perplexity.app.android",
        webUrl = "https://www.perplexity.ai"
    ),
    COPILOT(
        displayName = "Microsoft Copilot",
        packageName = "com.microsoft.copilot",
        webUrl = "https://copilot.microsoft.com"
    ),
    DEEPSEEK(
        displayName = "DeepSeek",
        packageName = "com.deepseek.chat",
        webUrl = "https://chat.deepseek.com"
    )
}

/**
 * Only the two supported AI providers:
 * - Provider A: Google Gemini (`gemini-3.8-flash`)
 * - Provider B: Groq (`qwen/qwen3.8-27b` and verified Groq vision models)
 */
enum class AiProvider(val displayName: String, val description: String) {
    GEMINI_CLOUD(
        "Google Gemini",
        "Gemini 3.8 Flash via official Google Gemini HTTP REST API"
    ),
    GROQ(
        "Groq",
        "Vision models via official Groq OpenAI-compatible HTTP REST API"
    )
}

/**
 * Model Tier representing the Quality vs. Quantity trade-off from the Gemini rate-limit configuration:
 * - ECONOMY: 15 RPM, 250,000 TPM, 500 RPD per model (Quantity — everyday questions, MCQs, factual, extraction)
 * - PREMIUM: 5 RPM, 250,000 TPM, 20 RPD per model (Quality — complex math, DSA/coding, debugging, multi-step reasoning)
 */
enum class GeminiModelTier(
    val displayName: String,
    val shortBadge: String,
    val rpmLimit: Int,
    val tpmLimit: Int,
    val rpdLimit: Int,
    val summary: String
) {
    ECONOMY(
        displayName = "Economy Tier (High Quantity)",
        shortBadge = "Economy • 500 RPD",
        rpmLimit = 15,
        tpmLimit = 250_000,
        rpdLimit = 500,
        summary = "15 RPM • 250,000 TPM • 500 RPD per Lite model — Everyday questions, definitions, MCQs, extraction & summaries"
    ),
    PREMIUM(
        displayName = "Premium Tier (High Quality)",
        shortBadge = "Premium • 20 RPD",
        rpmLimit = 5,
        tpmLimit = 250_000,
        rpdLimit = 20,
        summary = "5 RPM • 250,000 TPM • 20 RPD per Flash model — Complex math, DSA/coding, debugging & deep multi-step reasoning"
    )
}

/**
 * Routing mode allowing the user to either auto-route between Economy & Premium pipelines,
 * lock a specific tier pipeline, or manually pick a particular model (Quality vs. Quantity trade-off).
 */
enum class GeminiRoutingMode(
    val displayName: String,
    val shortBadge: String,
    val subtitle: String
) {
    AUTO_SMART(
        displayName = "Smart Auto-Tier (Quality vs. Quantity)",
        shortBadge = "Auto Tier",
        subtitle = "Uses Economy Lite (500 RPD) for everyday/MCQ questions and Premium Flash (20 RPD) for coding, DSA & complex math"
    ),
    ECONOMY_PIPELINE(
        displayName = "Economy Pipeline Only (Quantity • 500 RPD)",
        shortBadge = "Economy",
        subtitle = "1. Gemini 3.5 Flash Lite → 2. Gemini 3.1 Flash Lite → 3. Groq fast model backup"
    ),
    PREMIUM_PIPELINE(
        displayName = "Premium Pipeline Only (Quality • 20 RPD)",
        shortBadge = "Premium",
        subtitle = "1. Gemini 3.8 Flash → 2. Gemini 3.6 Flash → 3. Gemini 3 Flash → 4. Gemini 2.5 Flash → 5. Groq strong backup"
    ),
    MANUAL_MODEL(
        displayName = "Manual Model Selection (Pin Particular Model)",
        shortBadge = "Manual Pin",
        subtitle = "Manually pick any of the 6 Gemini models; auto-skips if server reports quota exhaustion"
    )
}

/**
 * Supplied Gemini model rate-limit configuration (dated 31 October 2026):
 *
 * A. Economy models (15 RPM, 250,000 TPM, 500 RPD):
 *    Priority 1: Gemini 3.5 Flash Lite (`gemini-3.5-flash-lite`)
 *    Priority 2: Gemini 3.1 Flash Lite (`gemini-3.1-flash-lite`)
 *
 * B. Premium models (5 RPM, 250,000 TPM, 20 RPD):
 *    Priority 1: Gemini 3.8 Flash (`gemini-3.8-flash`) — auto-skips when API reports quota exhaustion
 *    Priority 2: Gemini 3.6 Flash (`gemini-3.6-flash`)
 *    Priority 3: Gemini 3 Flash (`gemini-3-flash`)
 *    Priority 4: Gemini 2.5 Flash (`gemini-2.5-flash`)
 */
enum class GeminiVisionModel(
    val modelId: String,
    val displayName: String,
    val tier: GeminiModelTier,
    val priority: Int,
    val rpmLimit: Int,
    val tpmLimit: Int,
    val rpdLimit: Int,
    val subtitle: String
) {
    // A. Economy models (Priority 1..2)
    GEMINI_3_5_FLASH_LITE(
        modelId = "gemini-3.5-flash-lite",
        displayName = "Gemini 3.5 Flash Lite",
        tier = GeminiModelTier.ECONOMY,
        priority = 1,
        rpmLimit = 15,
        tpmLimit = 250_000,
        rpdLimit = 500,
        subtitle = "Economy Priority #1 • 15 RPM • 250,000 TPM • 500 RPD (Everyday & MCQ)"
    ),
    GEMINI_3_1_FLASH_LITE(
        modelId = "gemini-3.1-flash-lite",
        displayName = "Gemini 3.1 Flash Lite",
        tier = GeminiModelTier.ECONOMY,
        priority = 2,
        rpmLimit = 15,
        tpmLimit = 250_000,
        rpdLimit = 500,
        subtitle = "Economy Priority #2 • 15 RPM • 250,000 TPM • 500 RPD (Everyday & MCQ)"
    ),

    // B. Premium models (Priority 1..4)
    GEMINI_3_8_FLASH(
        modelId = "gemini-3.8-flash",
        displayName = "Gemini 3.8 Flash",
        tier = GeminiModelTier.PREMIUM,
        priority = 1,
        rpmLimit = 5,
        tpmLimit = 250_000,
        rpdLimit = 20,
        subtitle = "Premium Priority #1 • 5 RPM • 250,000 TPM • 20 RPD (Auto-skips on quota exhaustion)"
    ),
    GEMINI_3_6_FLASH(
        modelId = "gemini-3.6-flash",
        displayName = "Gemini 3.6 Flash",
        tier = GeminiModelTier.PREMIUM,
        priority = 2,
        rpmLimit = 5,
        tpmLimit = 250_000,
        rpdLimit = 20,
        subtitle = "Premium Priority #2 • 5 RPM • 250,000 TPM • 20 RPD (Coding, DSA & Math)"
    ),
    GEMINI_3_FLASH(
        modelId = "gemini-3-flash",
        displayName = "Gemini 3 Flash",
        tier = GeminiModelTier.PREMIUM,
        priority = 3,
        rpmLimit = 5,
        tpmLimit = 250_000,
        rpdLimit = 20,
        subtitle = "Premium Priority #3 • 5 RPM • 250,000 TPM • 20 RPD (Multi-step reasoning)"
    ),
    GEMINI_2_5_FLASH(
        modelId = "gemini-2.5-flash",
        displayName = "Gemini 2.5 Flash",
        tier = GeminiModelTier.PREMIUM,
        priority = 4,
        rpmLimit = 5,
        tpmLimit = 250_000,
        rpdLimit = 20,
        subtitle = "Premium Priority #4 • 5 RPM • 250,000 TPM • 20 RPD (Deep analysis backup)"
    );

    companion object {
        const val DEFAULT_MODEL_ID = "gemini-3.8-flash"
        const val DEFAULT_ECONOMY_MODEL_ID = "gemini-3.5-flash-lite"

        val supportedModelIds: Set<String> = entries.map { it.modelId }.toSet()

        val economyPipelineOrder: List<GeminiVisionModel> = entries
            .filter { it.tier == GeminiModelTier.ECONOMY }
            .sortedBy { it.priority }

        val premiumPipelineOrder: List<GeminiVisionModel> = entries
            .filter { it.tier == GeminiModelTier.PREMIUM }
            .sortedBy { it.priority }

        fun isSupportedModel(modelId: String): Boolean {
            return modelId.trim() in supportedModelIds
        }

        fun fromModelIdOrNull(modelId: String): GeminiVisionModel? {
            val clean = modelId.trim()
            return entries.find { it.modelId == clean }
        }
    }
}

/**
 * Real-time & server-synced rate limit tracking status for a single Gemini model.
 * Note: [rpmLimit], [tpmLimit], and [rpdLimit] are the supplied LIMITS;
 * [rpmUsed], [tpmUsed], and [rpdUsed] track usage synced with server responses (200 usage + 429 exhaustion).
 */
data class GeminiModelQuotaStatus(
    val model: GeminiVisionModel,
    val rpmUsed: Int = 0,
    val tpmUsed: Int = 0,
    val rpdUsed: Int = 0,
    val isServerDailyExhausted: Boolean = false,
    val isServerMinuteCooldown: Boolean = false,
    val cooldownRemainingSeconds: Int = 0,
    val lastServerSyncEpochMs: Long = 0L,
    val lastServerNote: String = "Ready"
) {
    val rpmLimit: Int get() = model.rpmLimit
    val tpmLimit: Int get() = model.tpmLimit
    val rpdLimit: Int get() = model.rpdLimit

    val rpdRemaining: Int get() = (rpdLimit - rpdUsed).coerceAtLeast(0)
    val rpmRemaining: Int get() = (rpmLimit - rpmUsed).coerceAtLeast(0)

    val isExhaustedOrCoolingDown: Boolean
        get() = isServerDailyExhausted || rpdUsed >= rpdLimit || isServerMinuteCooldown || rpmUsed >= rpmLimit || tpmUsed >= tpmLimit
}

/**
 * Documented Groq models that support vision/image inputs for Blink's workflow.
 */
enum class GroqVisionModel(
    val modelId: String,
    val displayName: String,
    val maxImagesPerRequest: Int = 3
) {
    QWEN_3_8_27B(
        modelId = "qwen/qwen3.8-27b",
        displayName = "Qwen 3.8 27B Vision (qwen/qwen3.8-27b)",
        maxImagesPerRequest = 3
    ),
    LLAMA_4_SCOUT(
        modelId = "meta-llama/llama-4-scout-17b-16e-instruct",
        displayName = "Llama 4 Scout 17B Vision",
        maxImagesPerRequest = 3
    ),
    LLAMA_4_MAVERICK(
        modelId = "meta-llama/llama-4-maverick-17b-128e-instruct",
        displayName = "Llama 4 Maverick 17B Vision",
        maxImagesPerRequest = 3
    );

    companion object {
        const val DEFAULT_MODEL_ID = "qwen/qwen3.8-27b"
        const val FAST_BACKUP_MODEL_ID = "meta-llama/llama-4-scout-17b-16e-instruct"
        const val STRONG_BACKUP_MODEL_ID = "qwen/qwen3.8-27b"
        const val MAX_IMAGES_PER_REQUEST = 3

        val supportedModelIds: Set<String> = entries.map { it.modelId }.toSet()

        fun isSupportedVisionModel(modelId: String): Boolean {
            return modelId.trim() in supportedModelIds
        }

        fun fromModelIdOrNull(modelId: String): GroqVisionModel? {
            val clean = modelId.trim()
            return entries.find { it.modelId == clean }
        }
    }
}

enum class AnswerStyle(val displayName: String, val promptDirective: String) {
    CONCISE(
        "Concise",
        "Keep explanations extremely brief and direct. Prefer bullet points or single-line results."
    ),
    BALANCED(
        "Balanced",
        "Provide a clear direct answer first, followed by a clean, structured explanation if helpful."
    ),
    DETAILED(
        "Detailed Step-by-Step",
        "Provide a comprehensive breakdown with step-by-step derivation, edge cases, and examples."
    )
}

/**
 * Smart Camera Auto-Click Sensitivity:
 * Triggers auto-capture as soon as readable text is seen on a sharp, steady frame
 * (no shape or screen boundary detection required).
 */
enum class CaptureSensitivity(
    val displayName: String,
    val minSharpness: Float,
    val minStability: Float,
    val requiredHoldTicks: Int,
    val minOcrChars: Int
) {
    FAST("Instant Auto-Click", minSharpness = 12f, minStability = 0.70f, requiredHoldTicks = 1, minOcrChars = 8),
    BALANCED("Smart Balanced", minSharpness = 15f, minStability = 0.76f, requiredHoldTicks = 1, minOcrChars = 12),
    STRICT("High Clarity Text", minSharpness = 20f, minStability = 0.84f, requiredHoldTicks = 2, minOcrChars = 18)
}

enum class ImageResolutionPref(val displayName: String, val maxDimensionPx: Int, val jpegQuality: Int) {
    STANDARD_720P("Standard 720p (Power Saver)", maxDimensionPx = 960, jpegQuality = 82),
    HIGH_1080P("High 1080p (Fine Code & Math)", maxDimensionPx = 1280, jpegQuality = 90)
}

enum class VerificationBadge(val label: String) {
    FAST_INITIAL("Direct Answer"),
    VERIFYING("Processing..."),
    MODEL_VERIFIED("Solved"),
    WEB_GROUNDED("Solved")
}

enum class ScanPhase {
    SCANNING,
    CONTEXT_CONFIRMATION,
    PROCESSING_FAST,
    ANSWER_DISPLAY
}

/**
 * Result of a real live connection test against Gemini or Groq.
 */
data class ProviderConnectionStatus(
    val provider: AiProvider,
    val isTesting: Boolean = false,
    val isSuccess: Boolean? = null,
    val modelTested: String = "",
    val latencyMs: Long = 0L,
    val message: String = ""
)

data class NormalizedQuad(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val surfaceLabel: String = "Readable Text"
)

data class CapturedFrame(
    val id: String,
    val bitmap: Bitmap,
    val timestamp: Long,
    val sharpnessScore: Float,
    val dHash: Long,
    val sourceLabel: String = "Smart Camera",
    val ocrText: String = "",
    val detectedSurfaceType: String = "Readable Text"
)

data class BlinkAnswer(
    val shortSummary: String,
    val detailedAnswer: String,
    val isShortAnswer: Boolean,
    val detectedCategory: String,
    val extractedQuestion: String,
    val fastModelUsed: String,
    val fastLatencyMs: Long,
    val verificationBadge: VerificationBadge = VerificationBadge.FAST_INITIAL,
    val verificationNote: String = "",
    val verifiedModelUsed: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

data class FrameTelemetry(
    val sharpnessScore: Float = 0f,
    val stabilityScore: Float = 1f,
    val luminance: Float = 128f,
    val lockProgress: Float = 0f,
    val isDuplicateFrame: Boolean = false,
    val hasReadableContent: Boolean = false,
    val isDisplayScreenDetected: Boolean = false,
    val screenConfidence: Float = 0f,
    val detectedQuad: NormalizedQuad? = null,
    val ocrSnippet: String = "",
    val ocrCharCount: Int = 0,
    val ocrBlockCount: Int = 0,
    val statusHint: String = "Point at readable text to auto-click"
)

/**
 * Quick-select default coding language for coding problems in "Solve Accurately" mode.
 * Defaults to C++ and Python (90% of competitive/interview coding use cases), plus SQL, Java, and JS.
 * Note: SQL queries are also automatically generated whenever the problem is an SQL question.
 */
enum class CodingLanguage(
    val displayName: String,
    val shortBadge: String,
    val promptName: String
) {
    CPP("C++", "C++", "C++ (modern C++17/20)"),
    PYTHON("Python", "Python", "Python 3"),
    SQL("SQL", "SQL", "SQL"),
    JAVA("Java", "Java", "Java"),
    JAVASCRIPT("JavaScript", "JS", "JavaScript");

    fun nextPrimaryToggle(): CodingLanguage {
        return when (this) {
            CPP -> PYTHON
            PYTHON -> CPP
            else -> CPP
        }
    }

    fun nextInCycle(): CodingLanguage {
        val all = entries
        return all[(ordinal + 1) % all.size]
    }
}

data class BlinkSettings(
    val dispatchMode: DispatchMode = DispatchMode.SMART_DISPATCH,
    val targetAiApp: TargetAiApp = TargetAiApp.SYSTEM_CHOOSER,
    val aiProvider: AiProvider = AiProvider.GEMINI_CLOUD,
    val geminiRoutingMode: GeminiRoutingMode = GeminiRoutingMode.AUTO_SMART,
    val fastModel: String = DEFAULT_GEMINI_MODEL,
    val groqModel: String = GroqVisionModel.DEFAULT_MODEL_ID,
    val codingLanguage: CodingLanguage = CodingLanguage.CPP,
    val answerStyle: AnswerStyle = AnswerStyle.BALANCED,
    val captureSensitivity: CaptureSensitivity = CaptureSensitivity.FAST,
    val imageResolution: ImageResolutionPref = ImageResolutionPref.HIGH_1080P,
    val maxImagesPerSession: Int = 5,
    val autoCaptureEnabled: Boolean = true,
    val laptopScreenMode: Boolean = true,
    val startInBlackScreenMode: Boolean = false,
    val allowBrightnessControl: Boolean = false,
    val customBrightnessLevel: Float = 0.25f,
    val saveSessionHistory: Boolean = true,
    val defaultPromptId: Int = 1,
    val geminiApiKey: String = "",
    val groqApiKey: String = ""
) {
    companion object {
        const val DEFAULT_GEMINI_MODEL = "gemini-3.8-flash"
    }
}
