package com.example.ai

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.example.data.AiProvider
import com.example.data.BlinkAnswer
import com.example.data.BlinkRepository
import com.example.data.BlinkSettings
import com.example.data.CapturedFrame
import com.example.data.CodingLanguage
import com.example.data.GeminiModelTier
import com.example.data.GeminiRoutingMode
import com.example.data.GeminiVisionModel
import com.example.data.GroqVisionModel
import com.example.data.PromptPresetEntity
import com.example.data.ProviderConnectionStatus
import com.example.data.VerificationBadge
import com.example.vision.BitmapIntelligence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

private const val TAG = "BlinkAiPipeline"

class BlinkAiPipeline(
    val quotaTracker: GeminiServerQuotaTracker = GeminiServerQuotaTracker()
) {

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    companion object {
        const val GEMINI_MODEL_ID = GeminiVisionModel.DEFAULT_MODEL_ID
        const val GEMINI_INTERACTIONS_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/interactions"
        const val GEMINI_BASE_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models"
        const val VERTEX_EXPRESS_BASE_ENDPOINT = "https://aiplatform.googleapis.com/v1/publishers/google/models"
        const val GROQ_CHAT_COMPLETIONS_ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
        const val MAX_GROQ_IMAGES_PER_REQUEST = 3
        const val MAX_SESSION_IMAGES = 8

        private const val JSON_SCHEMA_INSTRUCTION =
            "\nRespond strictly with a valid JSON object containing keys: " +
                "\"extractedQuestion\" (string), " +
                "\"detectedCategory\" (one of: MATH, CODE, MCQ, CONCEPT, DOCUMENT, FACTUAL), " +
                "\"isShortAnswer\" (boolean), " +
                "\"shortSummary\" (string), " +
                "\"detailedAnswer\" (string)."

        /**
         * Resolves the active Gemini 3.8 model identifier (`gemini-3.8-flash` by default).
         * Rejects any deprecated 2.0/1.5 model identifiers.
         */
        fun resolveGeminiModelId(configuredModel: String = GEMINI_MODEL_ID): String {
            val clean = configuredModel.trim()
            return if (GeminiVisionModel.isSupportedModel(clean)) {
                clean
            } else {
                GEMINI_MODEL_ID
            }
        }

        /**
         * Validates that a selected Groq model is in the documented vision-capable model set.
         * Throws an IllegalArgumentException with a clear user-facing message if unsupported.
         */
        fun validateGroqVisionModel(modelId: String): String {
            val clean = modelId.trim()
            if (clean.isEmpty()) {
                return GroqVisionModel.DEFAULT_MODEL_ID
            }
            if (!GroqVisionModel.isSupportedVisionModel(clean)) {
                val supportedList = GroqVisionModel.entries.joinToString(", ") { it.modelId }
                throw IllegalArgumentException(
                    "Unsupported Groq vision model '$clean'. Only vision-capable models are supported ($supportedList)."
                )
            }
            return clean
        }

        /**
         * Splits a list of bitmaps into batches of at most [maxPerRequest] (default 3 for Groq)
         * without ever dropping or truncating any image.
         */
        fun <T> chunkImagesForGroq(
            items: List<T>,
            maxPerRequest: Int = MAX_GROQ_IMAGES_PER_REQUEST
        ): List<List<T>> {
            val safeBatchSize = maxPerRequest.coerceAtLeast(1)
            if (items.isEmpty()) return emptyList()
            return items.chunked(safeBatchSize)
        }

        /**
         * Builds the official Gemini `/v1beta/interactions` JSON request body:
         * - Text-only requests:
         *   `{"model": "gemini-3.8-flash", "input": "Explain how AI works in a few words"}`
         * - Multimodal image + text requests:
         *   `{"model": "gemini-3.8-flash", "input": [{"type": "text", "text": "..."}, {"type": "image", "data": "<b64>", "mime_type": "image/jpeg"}]}`
         */
        fun buildGeminiInteractionsPayload(
            modelId: String = GEMINI_MODEL_ID,
            promptText: String,
            base64JpegImages: List<String> = emptyList()
        ): JSONObject {
            val resolvedModel = resolveGeminiModelId(modelId)
            val cleanText = promptText.trim()
            if (cleanText.isEmpty() && base64JpegImages.isEmpty()) {
                throw IllegalArgumentException("Request must include text, at least one image, or both.")
            }

            if (base64JpegImages.isEmpty()) {
                return JSONObject().apply {
                    put("model", resolvedModel)
                    put("input", cleanText)
                }
            }

            val inputArray = JSONArray()
            val effectiveText = cleanText.ifEmpty { "Analyze the provided image(s)." }
            inputArray.put(
                JSONObject()
                    .put("type", "text")
                    .put("text", effectiveText)
            )
            for (b64 in base64JpegImages) {
                inputArray.put(
                    JSONObject()
                        .put("type", "image")
                        .put("data", b64)
                        .put("mime_type", "image/jpeg")
                )
            }

            return JSONObject().apply {
                put("model", resolvedModel)
                put("input", inputArray)
            }
        }

        /**
         * Builds the official Gemini `generateContent` JSON request body supporting:
         * - Text-only requests (`base64JpegImages` empty, `promptText` non-empty)
         * - Image-only requests (`base64JpegImages` non-empty, `promptText` empty)
         * - Combined text + multiple image requests
         */
        fun buildGeminiRequestPayload(
            promptText: String,
            base64JpegImages: List<String>,
            requireJsonResponse: Boolean = true
        ): JSONObject {
            val cleanText = promptText.trim()
            if (cleanText.isEmpty() && base64JpegImages.isEmpty()) {
                throw IllegalArgumentException("Request must include text, at least one image, or both.")
            }

            val partsArray = JSONArray()
            for (b64 in base64JpegImages) {
                partsArray.put(
                    JSONObject().put(
                        "inline_data",
                        JSONObject()
                            .put("mime_type", "image/jpeg")
                            .put("data", b64)
                    )
                )
            }

            val effectiveText = when {
                cleanText.isNotEmpty() && requireJsonResponse -> cleanText + JSON_SCHEMA_INSTRUCTION
                cleanText.isNotEmpty() -> cleanText
                requireJsonResponse -> "Analyze the provided image(s) and solve or explain the content." + JSON_SCHEMA_INSTRUCTION
                else -> "Analyze the provided image(s)."
            }
            partsArray.put(JSONObject().put("text", effectiveText))

            return JSONObject().apply {
                put(
                    "contents",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put("parts", partsArray)
                    )
                )
                if (requireJsonResponse) {
                    put(
                        "generationConfig",
                        JSONObject().put("responseMimeType", "application/json")
                    )
                }
            }
        }

        /**
         * Builds the official Groq OpenAI-compatible multimodal `chat/completions` JSON request body supporting:
         * - Text-only requests (`base64JpegImages` empty, `promptText` non-empty)
         * - Image-only requests (`base64JpegImages` non-empty, `promptText` empty)
         * - Combined text + up to 3 images per request
         */
        fun buildGroqRequestPayload(
            modelId: String,
            promptText: String,
            base64JpegImages: List<String>,
            requireJsonResponse: Boolean = true
        ): JSONObject {
            val validatedModel = validateGroqVisionModel(modelId)
            if (base64JpegImages.size > MAX_GROQ_IMAGES_PER_REQUEST) {
                throw IllegalArgumentException(
                    "Groq model $validatedModel accepts at most $MAX_GROQ_IMAGES_PER_REQUEST images per single request (got ${base64JpegImages.size})."
                )
            }
            val cleanText = promptText.trim()
            if (cleanText.isEmpty() && base64JpegImages.isEmpty()) {
                throw IllegalArgumentException("Request must include text, at least one image, or both.")
            }

            val contentArray = JSONArray()
            val effectiveText = when {
                cleanText.isNotEmpty() && requireJsonResponse -> cleanText + JSON_SCHEMA_INSTRUCTION
                cleanText.isNotEmpty() -> cleanText
                requireJsonResponse -> "Analyze the provided image(s) and solve or explain the content inside JSON." + JSON_SCHEMA_INSTRUCTION
                else -> ""
            }

            if (effectiveText.isNotEmpty()) {
                contentArray.put(
                    JSONObject().apply {
                        put("type", "text")
                        put("text", effectiveText)
                    }
                )
            }

            for (b64 in base64JpegImages) {
                contentArray.put(
                    JSONObject().apply {
                        put("type", "image_url")
                        put(
                            "image_url",
                            JSONObject().put("url", "data:image/jpeg;base64,$b64")
                        )
                    }
                )
            }

            return JSONObject().apply {
                put("model", validatedModel)
                if (requireJsonResponse) {
                    put("response_format", JSONObject().put("type", "json_object"))
                }
                put(
                    "messages",
                    JSONArray().put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", contentArray)
                        }
                    )
                )
            }
        }

        /**
         * Builds the vision instruction using strictly the selected preconfigured mode:
         * 1. MCQ Answer -> "Solve this MCQ and choose the correct option accurately without giving any explanation..."
         * 2. Solve Accurately -> Direct optimal code implementation in the selected CodingLanguage (without comments/fluff)
         *    or direct SQL query / accurate answer.
         * Never injects on-device OCR text.
         */
        fun buildCloudVisionPrompt(
            frames: List<CapturedFrame>,
            promptPreset: PromptPresetEntity,
            customTypedPrompt: String = "",
            settings: BlinkSettings = BlinkSettings()
        ): String {
            val coreInstruction = BlinkRepository.buildPromptForPreset(
                preset = promptPreset,
                codingLanguage = settings.codingLanguage
            )
            return buildString {
                appendLine(coreInstruction)
                if (frames.size > 1) {
                    appendLine("The user captured ${frames.size} sequential images in order. Analyze all images together as one unified problem.")
                }
            }.trim()
        }

        /**
         * Parses the correct MCQ option cleanly from an in-app AI response (plain text or JSON),
         * removing explanations or trailing commentary so the user sees the exact option immediately.
         */
        fun extractMcqOption(rawText: String): String {
            val trimmed = rawText.trim()
            if (trimmed.isEmpty()) return ""

            // If the model happened to return JSON, extract shortSummary or detailedAnswer first
            val unwrapped = runCatching {
                val json = parseCleanJsonObject(trimmed)
                json.optString("shortSummary", "").ifBlank {
                    json.optString("detailedAnswer", "")
                }
            }.getOrDefault(trimmed).trim()

            val lines = unwrapped
                .replace("**", "")
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("```") }

            if (lines.isEmpty()) return unwrapped

            // Look for explicit option patterns like "Option B: ...", "(C) ...", "B) ...", "Correct Option: A"
            val explicitOptionRegex = Regex(
                """^(?:correct\s*(?:option|answer)\s*(?:is)?\s*[:\-]?\s*)?(?:option\s*)?[\(\[]?([A-Ea-e1-5])[\)\]\.\:\-]\s*(.*)$""",
                RegexOption.IGNORE_CASE
            )
            for (line in lines) {
                val match = explicitOptionRegex.find(line)
                if (match != null) {
                    val letter = match.groupValues[1].uppercase()
                    val rest = match.groupValues[2].trim()
                        .substringBefore(" because ")
                        .substringBefore(" Since ")
                        .substringBefore(" Explanation:")
                        .trim()
                    return if (rest.isNotEmpty()) {
                        "Option $letter: $rest"
                    } else {
                        "Option $letter"
                    }
                }
            }

            // Look for standalone letter/option match anywhere in the first 2 lines
            val inlineOptionRegex = Regex(
                """(?:correct\s+(?:option|answer)\s+is\s+|option\s+)([A-E1-5])\b(?:\s*[:\-\(\[]\s*([^\n\.]+))?""",
                RegexOption.IGNORE_CASE
            )
            val firstTwoLines = lines.take(2).joinToString(" ")
            val inlineMatch = inlineOptionRegex.find(firstTwoLines)
            if (inlineMatch != null) {
                val letter = inlineMatch.groupValues[1].uppercase()
                val rest = inlineMatch.groupValues[2].trim().trimEnd(')', ']')
                return if (rest.isNotEmpty()) {
                    "Option $letter: $rest"
                } else {
                    "Option $letter"
                }
            }

            // Otherwise return the first concise line stripped of "Explanation:"
            return lines.first()
                .substringBefore("Explanation:")
                .substringBefore("Reasoning:")
                .trim()
        }

        /**
         * Strips unnecessary inline code comments (`// ...` and `# ...` comments that aren't preprocessor directives)
         * to keep code human-readable yet non-verbose as requested.
         */
        fun stripUnnecessaryCodeComments(codeText: String): String {
            return codeText.lines().mapNotNull { line ->
                val trimmed = line.trim()
                // Remove pure comment lines (except C/C++ preprocessor directives like #include, #define, #pragma, or shebang)
                if (trimmed.startsWith("//")) {
                    null
                } else if (trimmed.startsWith("#") &&
                    !trimmed.startsWith("#include") &&
                    !trimmed.startsWith("#define") &&
                    !trimmed.startsWith("#pragma") &&
                    !trimmed.startsWith("#ifndef") &&
                    !trimmed.startsWith("#ifdef") &&
                    !trimmed.startsWith("#endif") &&
                    !trimmed.startsWith("#!")
                ) {
                    null
                } else {
                    line
                }
            }.joinToString("\n").trim()
        }

        /**
         * Resiliently parses any AI provider response (JSON, Markdown code blocks, or direct plain text)
         * into a structured [BlinkAnswer] without failing on unescaped code blocks or non-JSON replies.
         */
        fun parseFlexibleAiResponse(
            rawText: String,
            isMcqMode: Boolean,
            codingLanguage: CodingLanguage = CodingLanguage.CPP,
            modelUsed: String = GEMINI_MODEL_ID,
            latencyMs: Long = 120L,
            verificationNote: String = ""
        ): BlinkAnswer {
            val trimmed = rawText.trim()
            if (trimmed.isEmpty()) {
                throw IllegalStateException("AI provider returned an empty response.")
            }

            // 1. Try parsing as JSON if the response looks like a JSON object
            val jsonCandidate = runCatching { parseCleanJsonObject(trimmed) }.getOrNull()
            if (jsonCandidate != null && (jsonCandidate.has("shortSummary") || jsonCandidate.has("detailedAnswer"))) {
                val rawShort = jsonCandidate.optString("shortSummary", "").trim()
                val rawDetail = jsonCandidate.optString("detailedAnswer", rawShort).trim().ifBlank { rawShort }
                val extractedQ = jsonCandidate.optString("extractedQuestion", "").trim()
                val category = jsonCandidate.optString("detectedCategory", if (isMcqMode) "MCQ" else "CODE").ifBlank {
                    if (isMcqMode) "MCQ" else "CODE"
                }

                if (isMcqMode) {
                    val cleanOption = extractMcqOption(rawShort.ifBlank { rawDetail })
                    return BlinkAnswer(
                        shortSummary = cleanOption,
                        detailedAnswer = cleanOption,
                        isShortAnswer = true,
                        detectedCategory = "MCQ",
                        extractedQuestion = extractedQ,
                        fastModelUsed = modelUsed,
                        fastLatencyMs = latencyMs,
                        verificationBadge = VerificationBadge.FAST_INITIAL,
                        verificationNote = verificationNote
                    )
                }

                val cleanedDetail = stripUnnecessaryCodeComments(rawDetail)
                return BlinkAnswer(
                    shortSummary = rawShort.ifBlank {
                        cleanedDetail.lines().firstOrNull { it.isNotBlank() && !it.startsWith("```") }?.take(120)
                            ?: "Optimal Solution (${codingLanguage.shortBadge})"
                    },
                    detailedAnswer = cleanedDetail,
                    isShortAnswer = jsonCandidate.optBoolean("isShortAnswer", false),
                    detectedCategory = category,
                    extractedQuestion = extractedQ,
                    fastModelUsed = modelUsed,
                    fastLatencyMs = latencyMs,
                    verificationBadge = VerificationBadge.FAST_INITIAL,
                    verificationNote = verificationNote
                )
            }

            // 2. Direct Plain-Text / Markdown Response Path (Zero JSON Fragility)
            if (isMcqMode) {
                val parsedOption = extractMcqOption(trimmed)
                return BlinkAnswer(
                    shortSummary = parsedOption,
                    detailedAnswer = parsedOption,
                    isShortAnswer = true,
                    detectedCategory = "MCQ",
                    extractedQuestion = "",
                    fastModelUsed = modelUsed,
                    fastLatencyMs = latencyMs,
                    verificationBadge = VerificationBadge.FAST_INITIAL,
                    verificationNote = verificationNote
                )
            }

            // 3. Solve Accurately Mode: Detect code vs SQL vs direct non-coding/MCQ answer
            val hasCodeFence = trimmed.contains("```")
            val looksLikeSql = Regex("""\b(SELECT|INSERT|UPDATE|DELETE|WITH|CREATE\s+TABLE|FROM|WHERE|GROUP\s+BY|JOIN)\b""", RegexOption.IGNORE_CASE)
                .containsMatchIn(trimmed)
            val looksLikeCode = hasCodeFence ||
                trimmed.contains("#include") ||
                trimmed.contains("def ") ||
                trimmed.contains("class ") ||
                trimmed.contains("public static") ||
                trimmed.contains("function ") ||
                trimmed.contains("int main") ||
                trimmed.contains("vector<") ||
                looksLikeSql

            if (looksLikeCode) {
                // Extract code block if fenced, otherwise clean the entire code output
                val extractedCode = if (hasCodeFence) {
                    val parts = trimmed.split("```")
                    val codeBlocks = parts.filterIndexed { index, _ -> index % 2 == 1 }
                    if (codeBlocks.isNotEmpty()) {
                        codeBlocks.joinToString("\n\n") { block ->
                            val lines = block.trim().lines()
                            val body = if (lines.firstOrNull()?.all { it.isLetterOrDigit() || it == '+' || it == '#' } == true && lines.size > 1) {
                                lines.drop(1).joinToString("\n")
                            } else {
                                block.trim()
                            }
                            stripUnnecessaryCodeComments(body)
                        }
                    } else {
                        stripUnnecessaryCodeComments(trimmed)
                    }
                } else {
                    stripUnnecessaryCodeComments(trimmed)
                }

                val langTag = if (looksLikeSql && !trimmed.contains("#include") && !trimmed.contains("def ")) {
                    "SQL"
                } else {
                    codingLanguage.shortBadge
                }

                val formattedCodeBlock = "```\n$extractedCode\n```"
                return BlinkAnswer(
                    shortSummary = "Optimal $langTag Implementation",
                    detailedAnswer = formattedCodeBlock,
                    isShortAnswer = false,
                    detectedCategory = if (langTag == "SQL") "SQL" else "CODE ($langTag)",
                    extractedQuestion = "",
                    fastModelUsed = modelUsed,
                    fastLatencyMs = latencyMs,
                    verificationBadge = VerificationBadge.FAST_INITIAL,
                    verificationNote = verificationNote
                )
            }

            // Non-coding or MCQ question inside Solve Accurately mode
            val nonEmptyLines = trimmed.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val headline = nonEmptyLines.firstOrNull()?.take(140) ?: trimmed.take(140)
            val isShortDirect = nonEmptyLines.size <= 2 && trimmed.length <= 180

            return BlinkAnswer(
                shortSummary = headline,
                detailedAnswer = trimmed,
                isShortAnswer = isShortDirect,
                detectedCategory = "SOLVE",
                extractedQuestion = "",
                fastModelUsed = modelUsed,
                fastLatencyMs = latencyMs,
                verificationBadge = VerificationBadge.FAST_INITIAL,
                verificationNote = verificationNote
            )
        }

        internal fun parseCleanJsonObject(raw: String): JSONObject {
            val trimmed = raw.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            val startIdx = trimmed.indexOf('{')
            val endIdx = trimmed.lastIndexOf('}')
            val jsonSubstring = if (startIdx >= 0 && endIdx > startIdx) {
                trimmed.substring(startIdx, endIdx + 1)
            } else {
                trimmed
            }
            try {
                return JSONObject(jsonSubstring)
            } catch (e: Exception) {
                throw IllegalStateException("Malformed JSON response from AI provider.")
            }
        }

        /**
         * Scrubs any occurrence of an API key from error strings so secrets are never logged or shown.
         */
        fun sanitizeErrorMessage(rawMessage: String, vararg secrets: String): String {
            var safe = rawMessage
            for (secret in secrets) {
                val trimmed = secret.trim()
                if (trimmed.length >= 6) {
                    safe = safe.replace(trimmed, "[REDACTED_KEY]")
                }
            }
            return safe
        }
    }

    /**
     * Primary Single-Pass AI Execution supporting both Google Gemini and Groq Vision,
     * with automatic model fallback and flexible plain-text/JSON response parsing.
     */
    suspend fun runAnswerPipeline(
        frames: List<CapturedFrame>,
        promptPreset: PromptPresetEntity,
        customTypedPrompt: String,
        settings: BlinkSettings
    ): BlinkAnswer {
        if (frames.size > MAX_SESSION_IMAGES) {
            throw IllegalArgumentException(
                "Too many images (${frames.size}). Maximum supported per session is $MAX_SESSION_IMAGES."
            )
        }
        if (frames.isEmpty() && customTypedPrompt.isBlank()) {
            throw IllegalArgumentException("Please capture at least one image or enter a prompt.")
        }

        val startTime = System.currentTimeMillis()
        // Use 840px bound for fast, low-token vision inference that avoids Groq 429 TPM limits
        val maxDim = settings.imageResolution.maxDimensionPx.coerceIn(640, 960)
        val preparedBitmaps = frames.map { frame ->
            BitmapIntelligence.scaleDownIfNeeded(frame.bitmap, maxDim)
        }

        val isMcqMode = (promptPreset.id == BlinkRepository.PROMPT_ID_MCQ || promptPreset.forceShortOutput) &&
            customTypedPrompt.isBlank()

        val fullPrompt = if (frames.isEmpty()) {
            customTypedPrompt.trim()
        } else {
            buildCloudVisionPrompt(
                frames = frames,
                promptPreset = promptPreset,
                customTypedPrompt = customTypedPrompt,
                settings = settings
            )
        }

        try {
            val (rawResponseText, actualModelLabel, batchVerificationNote) = withTimeout(65_000L) {
                when (settings.aiProvider) {
                    AiProvider.GEMINI_CLOUD -> {
                        val geminiKey = settings.geminiApiKey.trim()
                        if (geminiKey.isBlank()) {
                            throw IllegalStateException(
                                "Gemini API key is missing. Add your Gemini API key in Settings."
                            )
                        }
                        val classifiedTier = GeminiServerQuotaTracker.classifyQuestionTier(
                            promptPreset = promptPreset,
                            customTypedPrompt = customTypedPrompt
                        )
                        executeGeminiTieredPipeline(
                            geminiApiKey = geminiKey,
                            settings = settings,
                            classifiedTier = classifiedTier,
                            bitmaps = preparedBitmaps,
                            prompt = fullPrompt
                        )
                    }

                    AiProvider.GROQ -> {
                        val groqKey = settings.groqApiKey.trim()
                        if (groqKey.isBlank()) {
                            throw IllegalStateException(
                                "Groq API key is missing. Add your Groq API key in Settings."
                            )
                        }
                        val validatedModel = validateGroqVisionModel(settings.groqModel)
                        val (responseText, modelUsedLabel, batchNote) = callGroqMultimodalWithBatching(
                            apiKey = groqKey,
                            modelName = validatedModel,
                            bitmaps = preparedBitmaps,
                            prompt = fullPrompt
                        )
                        Triple(responseText, "Groq ($modelUsedLabel)", batchNote)
                    }
                }
            }

            val latency = (System.currentTimeMillis() - startTime).coerceAtLeast(80L)
            return parseFlexibleAiResponse(
                rawText = rawResponseText,
                isMcqMode = isMcqMode,
                codingLanguage = settings.codingLanguage,
                modelUsed = actualModelLabel,
                latencyMs = latency,
                verificationNote = batchVerificationNote
            )
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "AI request timed out")
            throw IllegalStateException("Request timed out after 65 seconds. Check your network connection and try again.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val safeMsg = sanitizeErrorMessage(
                e.localizedMessage ?: "AI request failed.",
                settings.geminiApiKey,
                settings.groqApiKey
            )
            Log.e(TAG, "AI pipeline error: $safeMsg")
            throw IllegalStateException(safeMsg)
        }
    }

    /**
     * Performs a real lightweight HTTP REST request to verify the user's Gemini or Groq configuration
     * and sync server rate-limit status.
     */
    suspend fun testProviderConnection(
        provider: AiProvider,
        settings: BlinkSettings
    ): ProviderConnectionStatus = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        try {
            when (provider) {
                AiProvider.GEMINI_CLOUD -> {
                    val apiKey = settings.geminiApiKey.trim()
                    val preferredModelId = when (settings.geminiRoutingMode) {
                        GeminiRoutingMode.ECONOMY_PIPELINE -> GeminiVisionModel.DEFAULT_ECONOMY_MODEL_ID
                        GeminiRoutingMode.PREMIUM_PIPELINE -> GeminiVisionModel.DEFAULT_MODEL_ID
                        GeminiRoutingMode.MANUAL_MODEL -> resolveGeminiModelId(settings.fastModel)
                        GeminiRoutingMode.AUTO_SMART -> resolveGeminiModelId(settings.fastModel)
                    }
                    if (apiKey.isBlank()) {
                        return@withContext ProviderConnectionStatus(
                            provider = provider,
                            isTesting = false,
                            isSuccess = false,
                            modelTested = preferredModelId,
                            latencyMs = 0L,
                            message = "Gemini API key is missing. Paste and save your Gemini API key first."
                        )
                    }

                    val (text, actualModel) = callGeminiRestApi(
                        apiKey = apiKey,
                        modelName = preferredModelId,
                        bitmaps = emptyList(),
                        prompt = "Explain how AI works in a few words",
                        routingMode = settings.geminiRoutingMode
                    )
                    val latency = System.currentTimeMillis() - startMs
                    if (text.isBlank()) {
                        return@withContext ProviderConnectionStatus(
                            provider = provider,
                            isTesting = false,
                            isSuccess = false,
                            modelTested = actualModel,
                            latencyMs = latency,
                            message = "Google Gemini ($actualModel) returned an empty response."
                        )
                    }
                    val previewSnippet = text.lines().firstOrNull { it.isNotBlank() }?.take(72).orEmpty()
                    val quotaNote = GeminiVisionModel.fromModelIdOrNull(actualModel)?.let { m ->
                        val status = quotaTracker.getQuotaStatusForModel(m)
                        " [${status.rpdUsed}/${status.rpdLimit} RPD • ${m.tier.shortBadge}]"
                    }.orEmpty()
                    val routeDetail = if (actualModel != preferredModelId) {
                        "Connected to Google Gemini ($actualModel, auto-routed from $preferredModelId)$quotaNote in ${latency}ms"
                    } else {
                        "Connected to Google Gemini ($actualModel)$quotaNote in ${latency}ms"
                    }
                    return@withContext ProviderConnectionStatus(
                        provider = provider,
                        isTesting = false,
                        isSuccess = true,
                        modelTested = actualModel,
                        latencyMs = latency,
                        message = if (previewSnippet.isNotBlank()) "$routeDetail — \"$previewSnippet\"" else "$routeDetail."
                    )
                }

                AiProvider.GROQ -> {
                    val apiKey = settings.groqApiKey.trim()
                    val modelToTest = validateGroqVisionModel(settings.groqModel)
                    if (apiKey.isBlank()) {
                        return@withContext ProviderConnectionStatus(
                            provider = provider,
                            isTesting = false,
                            isSuccess = false,
                            modelTested = modelToTest,
                            latencyMs = 0L,
                            message = "Groq API key is missing. Paste and save your Groq API key first."
                        )
                    }

                    val (content, actualModel) = executeSingleGroqRequestWithFallback(
                        apiKey = apiKey,
                        preferredModelId = modelToTest,
                        promptText = "Reply with ONLY the word OK.",
                        base64JpegImages = emptyList()
                    )
                    val latency = System.currentTimeMillis() - startMs
                    if (content.isBlank()) {
                        return@withContext ProviderConnectionStatus(
                            provider = provider,
                            isTesting = false,
                            isSuccess = false,
                            modelTested = actualModel,
                            latencyMs = latency,
                            message = "Groq ($actualModel) returned an empty response."
                        )
                    }

                    return@withContext ProviderConnectionStatus(
                        provider = provider,
                        isTesting = false,
                        isSuccess = true,
                        modelTested = actualModel,
                        latencyMs = latency,
                        message = "Connected to Groq ($actualModel) in ${latency}ms."
                    )
                }
            }
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - startMs
            val friendly = mapExceptionToUserMessage(
                providerName = provider.displayName,
                e = e,
                geminiKey = settings.geminiApiKey,
                groqKey = settings.groqApiKey
            )
            ProviderConnectionStatus(
                provider = provider,
                isTesting = false,
                isSuccess = false,
                modelTested = if (provider == AiProvider.GEMINI_CLOUD) resolveGeminiModelId(settings.fastModel) else settings.groqModel,
                latencyMs = latency,
                message = friendly
            )
        }
    }

    /**
     * Executes the tiered Gemini pipeline (Economy vs. Premium vs. Manual) with automatic skipping
     * of quota-exhausted models (such as Gemini 3.8 Flash when its 20 RPD limit is exhausted) and
     * automatic fallback to Groq backup (when configured).
     */
    private suspend fun executeGeminiTieredPipeline(
        geminiApiKey: String,
        settings: BlinkSettings,
        classifiedTier: GeminiModelTier,
        bitmaps: List<Bitmap>,
        prompt: String
    ): Triple<String, String, String> = withContext(Dispatchers.IO) {
        val encodedImages = bitmaps.map { bmp ->
            val compactBmp = BitmapIntelligence.scaleDownIfNeeded(bmp, 840)
            bitmapToBase64Jpeg(compactBmp, 80)
        }

        val executionPlan = GeminiServerQuotaTracker.buildExecutionPlan(
            settings = settings,
            classifiedTier = classifiedTier,
            quotaTracker = quotaTracker
        )

        val skippedModels = mutableListOf<String>()
        var lastException: Exception? = null

        for (step in executionPlan) {
            when (step) {
                is PipelineExecutionCandidate.GeminiStep -> {
                    val model = step.model
                    try {
                        val text = executeSingleGeminiModelRequest(
                            apiKey = geminiApiKey,
                            modelId = model.modelId,
                            prompt = prompt,
                            encodedImages = encodedImages
                        )
                        val quotaStatus = quotaTracker.getQuotaStatusForModel(model)
                        val routingLabel = "${model.displayName} (${model.tier.shortBadge})"
                        val note = buildString {
                            append("Quota: ${quotaStatus.rpdUsed}/${quotaStatus.rpdLimit} RPD • ${quotaStatus.rpmUsed}/${quotaStatus.rpmLimit} RPM")
                            if (skippedModels.isNotEmpty()) {
                                append(" • Auto-skipped ${skippedModels.joinToString(", ")} (quota exhausted)")
                            }
                        }
                        return@withContext Triple(text, routingLabel, note)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        lastException = e
                        val msg = e.message.orEmpty()
                        if (msg.contains("401") || msg.contains("403")) {
                            throw e
                        }
                        skippedModels.add(model.modelId)
                        Log.w(TAG, "Gemini pipeline model ${model.modelId} failed ($msg), advancing to next step...")
                    }
                }

                is PipelineExecutionCandidate.GroqBackupStep -> {
                    val groqKey = settings.groqApiKey.trim()
                    if (groqKey.isNotBlank()) {
                        try {
                            val (groqText, usedGroqModel, batchNote) = callGroqMultimodalWithBatching(
                                apiKey = groqKey,
                                modelName = step.modelId,
                                bitmaps = bitmaps,
                                prompt = prompt
                            )
                            val backupNote = buildString {
                                append("Backup provider (Groq) used after ${step.tierReason.displayName} quota reached")
                                if (batchNote.isNotBlank()) append(" • $batchNote")
                            }
                            return@withContext Triple(
                                groqText,
                                "Groq Backup ($usedGroqModel)",
                                backupNote
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            lastException = e
                            Log.w(TAG, "Groq backup step ${step.modelId} failed: ${e.message}")
                        }
                    }
                }
            }
        }

        throw lastException ?: IllegalStateException("All configured Gemini and backup models reached their rate limits.")
    }

    /**
     * Calls the official Google Gemini Interactions HTTP REST API (`https://generativelanguage.googleapis.com/v1beta/interactions`)
     * with `-H "Content-Type: application/json"` and `-H "x-goog-api-key: YOUR_API_KEY"`.
     * Automatically skips models marked exhausted by the server and falls back across the active pipeline.
     */
    private suspend fun callGeminiRestApi(
        apiKey: String,
        modelName: String,
        bitmaps: List<Bitmap>,
        prompt: String,
        routingMode: GeminiRoutingMode = GeminiRoutingMode.AUTO_SMART
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        val primaryModel = GeminiVisionModel.fromModelIdOrNull(resolveGeminiModelId(modelName))
            ?: GeminiVisionModel.GEMINI_3_8_FLASH
        val encodedImages = bitmaps.map { bmp ->
            val compactBmp = BitmapIntelligence.scaleDownIfNeeded(bmp, 840)
            bitmapToBase64Jpeg(compactBmp, 80)
        }

        val orderedModels = buildList {
            if (!quotaTracker.shouldSkipModel(primaryModel)) {
                add(primaryModel)
            }
            val tierList = if (routingMode == GeminiRoutingMode.ECONOMY_PIPELINE ||
                (routingMode == GeminiRoutingMode.MANUAL_MODEL && primaryModel.tier == GeminiModelTier.ECONOMY)
            ) {
                GeminiVisionModel.economyPipelineOrder + GeminiVisionModel.premiumPipelineOrder
            } else {
                GeminiVisionModel.premiumPipelineOrder + GeminiVisionModel.economyPipelineOrder
            }
            tierList.forEach { entry ->
                if (!contains(entry) && !quotaTracker.shouldSkipModel(entry)) {
                    add(entry)
                }
            }
            // Ensure primaryModel and all entries are still tried as a fallback if all were locally marked skipped
            if (!contains(primaryModel)) add(primaryModel)
            GeminiVisionModel.entries.forEach { entry ->
                if (!contains(entry)) add(entry)
            }
        }

        var lastException: Exception? = null
        for (candidateModel in orderedModels) {
            try {
                val text = executeSingleGeminiModelRequest(
                    apiKey = apiKey,
                    modelId = candidateModel.modelId,
                    prompt = prompt,
                    encodedImages = encodedImages
                )
                return@withContext text to candidateModel.modelId
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastException = e
                val msg = e.message.orEmpty()
                if (msg.contains("401") || msg.contains("403")) {
                    throw e
                }
                Log.w(TAG, "Gemini model ${candidateModel.modelId} failed (${e.message}), trying next pipeline model...")
            }
        }

        throw lastException ?: IllegalStateException("Google Gemini request failed across all Flash models.")
    }

    private fun executeSingleGeminiModelRequest(
        apiKey: String,
        modelId: String,
        prompt: String,
        encodedImages: List<String>
    ): String {
        val interactionsPayloadStr = buildGeminiInteractionsPayload(
            modelId = modelId,
            promptText = prompt,
            base64JpegImages = encodedImages
        ).toString()

        try {
            return executeGeminiHttpPost(
                url = GEMINI_INTERACTIONS_ENDPOINT,
                apiKey = apiKey,
                modelName = modelId,
                payloadStr = interactionsPayloadStr,
                promptCharCount = prompt.length,
                imageCount = encodedImages.size
            )
        } catch (interactionsErr: Exception) {
            val errMsg = interactionsErr.message.orEmpty()
            // If /v1beta/interactions returned 429 quota exceeded for this model, immediately let the outer loop try the next model
            if (errMsg.contains("429")) {
                throw interactionsErr
            }

            val generateContentPayloadStr = buildGeminiRequestPayload(
                promptText = prompt,
                base64JpegImages = encodedImages,
                requireJsonResponse = false
            ).toString()

            val generateContentUrl = "$GEMINI_BASE_ENDPOINT/$modelId:generateContent"
            runCatching {
                return executeGeminiHttpPost(
                    url = generateContentUrl,
                    apiKey = apiKey,
                    modelName = modelId,
                    payloadStr = generateContentPayloadStr,
                    promptCharCount = prompt.length,
                    imageCount = encodedImages.size
                )
            }

            if (apiKey.startsWith("AQ.")) {
                val expressUrl = "$VERTEX_EXPRESS_BASE_ENDPOINT/$modelId:generateContent"
                runCatching {
                    return executeGeminiHttpPost(
                        url = expressUrl,
                        apiKey = apiKey,
                        modelName = modelId,
                        payloadStr = generateContentPayloadStr,
                        promptCharCount = prompt.length,
                        imageCount = encodedImages.size
                    )
                }
            }
            throw interactionsErr
        }
    }

    private fun executeGeminiHttpPost(
        url: String,
        apiKey: String,
        modelName: String,
        payloadStr: String,
        promptCharCount: Int = 120,
        imageCount: Int = 0
    ): String {
        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("x-goog-api-key", apiKey)
            .post(payloadStr.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            httpClient.newCall(request).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    if (resp.code == 429) {
                        val retryAfterHeader = resp.header("Retry-After")?.toIntOrNull()
                        quotaTracker.recordServerQuotaExhausted(
                            modelId = modelName,
                            responseBody = bodyStr,
                            retryAfterSeconds = retryAfterHeader
                        )
                    }
                    throw IllegalStateException(
                        mapHttpErrorToUserMessage(
                            providerName = "Google Gemini",
                            modelName = modelName,
                            httpCode = resp.code,
                            responseBody = bodyStr,
                            secretKey = apiKey
                        )
                    )
                }
                val candidateText = extractGeminiCandidateText(bodyStr, modelName)
                val tokensUsed = GeminiServerQuotaTracker.extractServerTokenUsage(
                    responseBody = bodyStr,
                    promptCharCount = promptCharCount,
                    imageCount = imageCount
                )
                quotaTracker.recordServerSuccess(
                    modelId = modelName,
                    tokensUsed = tokensUsed
                )
                return candidateText
            }
        } catch (e: IOException) {
            throw IllegalStateException(
                mapExceptionToUserMessage("Google Gemini", e, apiKey, "")
            )
        }
    }

    /**
     * Calls the official Groq OpenAI-compatible multimodal endpoint (`https://api.groq.com/openai/v1/chat/completions`).
     * Uses compact JPEG encoding (76% quality) and plain-text response mode to prevent Groq 400 `json_validate_failed`
     * and 429 rate-limit errors, plus automatic fallback across Groq vision models.
     */
    private suspend fun callGroqMultimodalWithBatching(
        apiKey: String,
        modelName: String,
        bitmaps: List<Bitmap>,
        prompt: String
    ): Triple<String, String, String> = withContext(Dispatchers.IO) {
        val validatedModel = validateGroqVisionModel(modelName)
        val encodedImages = bitmaps.map { bmp ->
            val compactBmp = BitmapIntelligence.scaleDownIfNeeded(bmp, 800)
            bitmapToBase64Jpeg(compactBmp, 76)
        }

        // Case 1: 0..3 images -> Single Groq request with automatic vision model fallback
        if (encodedImages.size <= MAX_GROQ_IMAGES_PER_REQUEST) {
            val (singleResponse, actualModel) = executeSingleGroqRequestWithFallback(
                apiKey = apiKey,
                preferredModelId = validatedModel,
                promptText = prompt,
                base64JpegImages = encodedImages
            )
            return@withContext Triple(singleResponse, actualModel, "")
        }

        // Case 2: > 3 images -> Multi-request batching (up to 3 images per Groq request)
        val imageBatches = chunkImagesForGroq(encodedImages, MAX_GROQ_IMAGES_PER_REQUEST)
        val batchSummaries = mutableListOf<String>()
        var lastUsedModel = validatedModel

        imageBatches.forEachIndexed { batchIdx, batchImages ->
            val startImg = batchIdx * MAX_GROQ_IMAGES_PER_REQUEST + 1
            val endImg = startImg + batchImages.size - 1
            val batchPrompt = buildString {
                appendLine(prompt)
                appendLine()
                appendLine(
                    "Batch ${batchIdx + 1} of ${imageBatches.size} (images #$startImg..#$endImg out of ${encodedImages.size} total). " +
                        "Extract all relevant question details, code, equations, and solution findings from these frames."
                )
            }

            val (batchText, usedModel) = executeSingleGroqRequestWithFallback(
                apiKey = apiKey,
                preferredModelId = lastUsedModel,
                promptText = batchPrompt,
                base64JpegImages = batchImages
            )
            lastUsedModel = usedModel
            batchSummaries.add("Batch ${batchIdx + 1} (Images #$startImg..#$endImg):\n$batchText")
        }

        val synthesisPrompt = buildString {
            appendLine(prompt)
            appendLine()
            appendLine("Findings from all ${imageBatches.size} image batches (${encodedImages.size} images total):")
            batchSummaries.forEach { summary ->
                appendLine(summary)
                appendLine()
            }
            appendLine("Provide the final direct answer or optimal code implementation now.")
        }

        val (finalSynthesizedText, finalModel) = executeSingleGroqRequestWithFallback(
            apiKey = apiKey,
            preferredModelId = lastUsedModel,
            promptText = synthesisPrompt,
            base64JpegImages = emptyList()
        )

        val batchNote = "Processed all ${encodedImages.size} images across ${imageBatches.size} Groq requests"
        Triple(finalSynthesizedText, finalModel, batchNote)
    }

    /**
     * Executes a Groq request with automatic retry on 429 and fallback across Groq's vision models
     * if a specific model is rate-limited (429) or unavailable (400/404/503).
     */
    private suspend fun executeSingleGroqRequestWithFallback(
        apiKey: String,
        preferredModelId: String,
        promptText: String,
        base64JpegImages: List<String>
    ): Pair<String, String> {
        val candidateModels = buildList {
            add(preferredModelId)
            GroqVisionModel.entries.forEach { entry ->
                if (!contains(entry.modelId)) add(entry.modelId)
            }
        }

        var lastException: Exception? = null
        for ((idx, candidateModel) in candidateModels.withIndex()) {
            try {
                val result = executeSingleGroqRequest(
                    apiKey = apiKey,
                    modelId = candidateModel,
                    promptText = promptText,
                    base64JpegImages = base64JpegImages
                )
                return result to candidateModel
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastException = e
                val msg = e.message.orEmpty()
                // If authentication failed (401/403), do not retry other models
                if (msg.contains("401") || msg.contains("403")) {
                    throw e
                }
                if (msg.contains("429") && idx < candidateModels.lastIndex) {
                    delay(650L)
                }
                Log.w(TAG, "Groq model $candidateModel failed (${e.message}), trying fallback model...")
            }
        }
        throw lastException ?: IllegalStateException("Groq request failed across all vision models.")
    }

    private fun executeSingleGroqRequest(
        apiKey: String,
        modelId: String,
        promptText: String,
        base64JpegImages: List<String>
    ): String {
        // Do NOT force response_format=json_object on vision/code requests so Groq never fails with 400 json_validate_failed
        val payload = buildGroqRequestPayload(
            modelId = modelId,
            promptText = promptText,
            base64JpegImages = base64JpegImages,
            requireJsonResponse = false
        )

        val request = Request.Builder()
            .url(GROQ_CHAT_COMPLETIONS_ENDPOINT)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            httpClient.newCall(request).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException(
                        mapHttpErrorToUserMessage(
                            providerName = "Groq",
                            modelName = modelId,
                            httpCode = resp.code,
                            responseBody = bodyStr,
                            secretKey = apiKey
                        )
                    )
                }
                val content = extractGroqMessageContent(bodyStr)
                if (content.isBlank()) {
                    throw IllegalStateException("Groq ($modelId) returned an empty response.")
                }
                return content
            }
        } catch (e: IOException) {
            throw IllegalStateException(
                mapExceptionToUserMessage("Groq", e, "", apiKey)
            )
        }
    }

    internal fun extractGeminiCandidateText(bodyStr: String, modelName: String): String {
        val root = try {
            JSONObject(bodyStr)
        } catch (e: Exception) {
            throw IllegalStateException("Google Gemini ($modelName) returned malformed JSON response.")
        }

        // 1. Check `/v1beta/interactions` response structures (`outputs` or `steps` arrays)
        // Supports both direct `{"type":"text","text":"..."}` and nested `{"type":"model_output","content":[{"type":"text","text":"..."}]}`
        val interactionArrays = listOfNotNull(
            root.optJSONArray("outputs"),
            root.optJSONArray("steps")
        )
        for (arr in interactionArrays) {
            if (arr.length() > 0) {
                val nonThoughtSb = StringBuilder()
                val allTextSb = StringBuilder()
                for (i in 0 until arr.length()) {
                    val itemObj = arr.optJSONObject(i) ?: continue
                    val itemType = itemObj.optString("type").lowercase()
                    val isItemThought = itemType == "thought" || itemObj.optBoolean("thought", false)

                    val directItemText = itemObj.optString("text").orEmpty()
                    if (directItemText.isNotEmpty()) {
                        allTextSb.append(directItemText)
                        if (!isItemThought) {
                            nonThoughtSb.append(directItemText)
                        }
                    }

                    val contentArr = itemObj.optJSONArray("content")
                    if (contentArr != null) {
                        for (j in 0 until contentArr.length()) {
                            val contentObj = contentArr.optJSONObject(j) ?: continue
                            val contentType = contentObj.optString("type").lowercase()
                            val isContentThought = isItemThought ||
                                contentType == "thought" ||
                                contentObj.optBoolean("thought", false)
                            val contentText = contentObj.optString("text").orEmpty()
                            if (contentText.isNotEmpty()) {
                                allTextSb.append(contentText)
                                if (!isContentThought) {
                                    nonThoughtSb.append(contentText)
                                }
                            }
                        }
                    }
                }
                val finalAnswer = nonThoughtSb.toString().trim().ifEmpty { allTextSb.toString().trim() }
                if (finalAnswer.isNotEmpty()) return finalAnswer
            }
        }

        // 2. Check top-level `output_text` or `text` field
        val directText = root.optString("output_text").trim().ifEmpty { root.optString("text").trim() }
        if (directText.isNotEmpty()) return directText

        // 3. Check `/v1beta/models/{model}:generateContent` response structure (`candidates`)
        val parts = root.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")

        if (parts != null && parts.length() > 0) {
            // Prefer non-thought parts so Gemini 3.x thinking models return only the final user answer
            val nonThoughtSb = StringBuilder()
            val allTextSb = StringBuilder()
            for (i in 0 until parts.length()) {
                val partObj = parts.optJSONObject(i) ?: continue
                val partText = partObj.optString("text").orEmpty()
                val isThought = partObj.optBoolean("thought", false)
                if (partText.isNotEmpty()) {
                    allTextSb.append(partText)
                    if (!isThought) {
                        nonThoughtSb.append(partText)
                    }
                }
            }
            val finalAnswer = nonThoughtSb.toString().trim().ifEmpty { allTextSb.toString().trim() }
            if (finalAnswer.isNotEmpty()) return finalAnswer
        }

        throw IllegalStateException("Google Gemini ($modelName) returned an empty response.")
    }

    private fun extractGroqMessageContent(bodyStr: String): String {
        val root = try {
            JSONObject(bodyStr)
        } catch (e: Exception) {
            throw IllegalStateException("Groq returned a malformed JSON response.")
        }
        return root.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            .orEmpty()
    }

    private fun mapHttpErrorToUserMessage(
        providerName: String,
        modelName: String,
        httpCode: Int,
        responseBody: String,
        secretKey: String
    ): String {
        val remoteErrorDetail = runCatching {
            val json = JSONObject(responseBody)
            json.optJSONObject("error")?.optString("message").orEmpty()
        }.getOrDefault("").let { sanitizeErrorMessage(it, secretKey) }.take(180)

        return when (httpCode) {
            401, 403 -> "$providerName authentication failed ($httpCode): Invalid or unauthorized API key."
            404 -> "$providerName model '$modelName' was not found or is unsupported ($httpCode).${if (remoteErrorDetail.isNotBlank()) " $remoteErrorDetail" else ""}"
            413 -> "$providerName rejected the request ($httpCode): Image payload too large."
            429 -> "$providerName rate limit or quota exceeded ($httpCode). Please wait a moment or check your billing/quota."
            400 -> {
                if (remoteErrorDetail.contains("image", ignoreCase = true) ||
                    remoteErrorDetail.contains("vision", ignoreCase = true) ||
                    remoteErrorDetail.contains("model", ignoreCase = true)
                ) {
                    "$providerName rejected request for '$modelName' ($httpCode): $remoteErrorDetail"
                } else {
                    "$providerName bad request ($httpCode): ${remoteErrorDetail.ifBlank { "Invalid request parameters." }}"
                }
            }
            in 500..599 -> "$providerName server error ($httpCode). The provider is temporarily unavailable."
            else -> "$providerName error ($httpCode): ${remoteErrorDetail.ifBlank { "Unexpected API failure." }}"
        }
    }

    private fun mapExceptionToUserMessage(
        providerName: String,
        e: Exception,
        geminiKey: String,
        groqKey: String
    ): String {
        return when (e) {
            is UnknownHostException -> "$providerName network error: Unable to resolve host. Check your internet connection."
            is SocketTimeoutException, is TimeoutCancellationException -> "$providerName request timed out. Check your internet connection and try again."
            is IOException -> "$providerName network error: ${sanitizeErrorMessage(e.localizedMessage ?: "Connection failed", geminiKey, groqKey)}"
            else -> sanitizeErrorMessage(e.localizedMessage ?: "$providerName request failed.", geminiKey, groqKey)
        }
    }

    private fun bitmapToBase64Jpeg(bitmap: Bitmap, quality: Int): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
