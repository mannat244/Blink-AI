package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.ai.AiHandoffDispatcher
import com.example.ai.BlinkAiPipeline
import com.example.ai.GeminiServerQuotaTracker
import com.example.ai.PipelineExecutionCandidate
import com.example.data.AiProvider
import com.example.data.AnswerStyle
import com.example.data.BlinkRepository
import com.example.data.BlinkSettings
import com.example.data.CapturedFrame
import com.example.data.CodingLanguage
import com.example.data.DispatchMode
import com.example.data.GeminiModelTier
import com.example.data.GeminiRoutingMode
import com.example.data.GeminiVisionModel
import com.example.data.GroqVisionModel
import com.example.data.SecureCredentialCipher
import com.example.data.TargetAiApp
import com.example.ui.BlinkViewModel
import com.example.vision.BitmapIntelligence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read app_name from context matches Blink AI`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Blink AI", appName)
    }

    @Test
    fun `default prompt presets contain strictly two preconfigured modes - MCQ Answer and Solve Accurately`() {
        val presets = BlinkRepository.defaultPromptPresets()
        assertEquals(2, presets.size)
        assertEquals("MCQ Answer", presets[0].title)
        assertEquals("MCQ", presets[0].shortBadge)
        assertTrue(presets[0].forceShortOutput)
        assertTrue(
            presets[0].instruction.contains(
                "solve this MCQ and choose the correct option accurately without giving any explanation",
                ignoreCase = true
            )
        )

        assertEquals("Solve Accurately", presets[1].title)
        assertEquals("Solve", presets[1].shortBadge)
        assertFalse(presets[1].forceShortOutput)
        assertTrue(presets[1].instruction.contains("C++", ignoreCase = true))
        assertTrue(presets[1].instruction.contains("Do NOT include code comments", ignoreCase = true))
    }

    @Test
    fun `perceptual dHash detects identical frames and differentiates distinct patterns`() {
        val gridA = IntArray(32 * 32) { idx -> (idx * 7) % 256 }
        val gridB = IntArray(32 * 32) { idx -> 255 - ((idx * 7) % 256) }

        val hashA1 = BitmapIntelligence.computeDHashFromGrid(gridA, 32, 32)
        val hashA2 = BitmapIntelligence.computeDHashFromGrid(gridA.clone(), 32, 32)
        val hashB = BitmapIntelligence.computeDHashFromGrid(gridB, 32, 32)

        assertEquals(0, BitmapIntelligence.hammingDistance(hashA1, hashA2))
        assertTrue(BitmapIntelligence.hammingDistance(hashA1, hashB) > 20)
    }

    @Test
    fun `extractOcrFromBitmap never fabricates OCR characters when ML Kit is unavailable or fails`() {
        val bmp = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        for (y in 0 until 128) {
            for (x in 0 until 128) {
                bmp.setPixel(x, y, if ((x + y) % 2 == 0) Color.WHITE else Color.BLACK)
            }
        }
        val result = BitmapIntelligence.extractOcrFromBitmap(bmp, fallbackQuad = null, isScreenMode = true)
        assertEquals(0, result.charCount)
        assertEquals(0, result.blockCount)
        assertEquals("", result.fullText)
    }

    @Test
    fun `sampleRotatedLumaGrid applies sensor rotationDegrees correctly`() {
        val width = 64
        val height = 64
        val buffer = ByteBuffer.allocate(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                buffer.put(y * width + x, if (x < 32) 220.toByte() else 20.toByte())
            }
        }

        val grid0 = BitmapIntelligence.sampleRotatedLumaGrid(
            buffer = buffer,
            rowStride = width,
            pixelStride = 1,
            rawWidth = width,
            rawHeight = height,
            rotationDegrees = 0,
            gridW = 16,
            gridH = 16
        )
        val grid90 = BitmapIntelligence.sampleRotatedLumaGrid(
            buffer = buffer,
            rowStride = width,
            pixelStride = 1,
            rawWidth = width,
            rawHeight = height,
            rotationDegrees = 90,
            gridW = 16,
            gridH = 16
        )

        assertEquals(220, grid0[8 * 16 + 1])
        assertEquals(20, grid0[8 * 16 + 14])
        assertEquals(220, grid90[1 * 16 + 8])
        assertEquals(20, grid90[14 * 16 + 8])
    }

    @Test
    fun `cloud vision prompt and AI handoff prompt never include OCR text and adjust for selected coding language`() {
        val dummyBmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val frame = CapturedFrame(
            id = "f1",
            bitmap = dummyBmp,
            timestamp = 1000L,
            sharpnessScore = 30f,
            dHash = 123L,
            sourceLabel = "Readable Text",
            ocrText = "SHOULD_NEVER_APPEAR_IN_PROMPT_12345",
            detectedSurfaceType = "Readable Text"
        )
        val mcqPreset = BlinkRepository.defaultPromptPresets()[0]
        val solvePreset = BlinkRepository.defaultPromptPresets()[1]

        val cloudMcqPrompt = BlinkAiPipeline.buildCloudVisionPrompt(
            frames = listOf(frame),
            promptPreset = mcqPreset,
            customTypedPrompt = "IGNORED_CUSTOM_PROMPT"
        )
        assertFalse(cloudMcqPrompt.contains("SHOULD_NEVER_APPEAR_IN_PROMPT_12345"))
        assertFalse(cloudMcqPrompt.contains("IGNORED_CUSTOM_PROMPT"))
        assertTrue(
            cloudMcqPrompt.contains(
                "solve this MCQ and choose the correct option accurately without giving any explanation",
                ignoreCase = true
            )
        )

        val (cppHandoffPrompt, cppDomain) = AiHandoffDispatcher.buildSynthesizedMasterPrompt(
            frames = listOf(frame),
            promptPreset = solvePreset,
            customTypedPrompt = "IGNORED_CUSTOM_PROMPT",
            answerStyle = AnswerStyle.BALANCED,
            codingLanguage = CodingLanguage.CPP
        )
        assertEquals("Solve (C++)", cppDomain)
        assertFalse(cppHandoffPrompt.contains("SHOULD_NEVER_APPEAR_IN_PROMPT_12345"))
        assertTrue(cppHandoffPrompt.contains("C++", ignoreCase = true))

        val (pyHandoffPrompt, pyDomain) = AiHandoffDispatcher.buildSynthesizedMasterPrompt(
            frames = listOf(frame),
            promptPreset = solvePreset,
            customTypedPrompt = "",
            answerStyle = AnswerStyle.BALANCED,
            codingLanguage = CodingLanguage.PYTHON
        )
        assertEquals("Solve (Python)", pyDomain)
        assertTrue(pyHandoffPrompt.contains("Python 3", ignoreCase = true))
        assertTrue(pyHandoffPrompt.contains("SQL", ignoreCase = true))
    }

    @Test
    fun `extractMcqOption and parseFlexibleAiResponse parse MCQ options and comment-free code without JSON errors`() {
        assertEquals(
            "Option B: O(n log n)",
            BlinkAiPipeline.extractMcqOption("Correct option is (B) O(n log n) because merge sort divides the array.")
        )
        assertEquals(
            "Option C: 42",
            BlinkAiPipeline.extractMcqOption("C) 42\nExplanation: 6 * 7 = 42")
        )

        val mcqAnswer = BlinkAiPipeline.parseFlexibleAiResponse(
            rawText = "Option D: Dijkstra's Algorithm",
            isMcqMode = true,
            codingLanguage = CodingLanguage.CPP
        )
        assertTrue(mcqAnswer.isShortAnswer)
        assertEquals("Option D: Dijkstra's Algorithm", mcqAnswer.shortSummary)

        val rawCodeWithComments = """
            ```cpp
            #include <vector>
            // This comment should be stripped
            int solve(std::vector<int>& nums) {
                # Another comment to strip
                return nums.size();
            }
            ```
        """.trimIndent()

        val solveAnswer = BlinkAiPipeline.parseFlexibleAiResponse(
            rawText = rawCodeWithComments,
            isMcqMode = false,
            codingLanguage = CodingLanguage.CPP
        )
        assertEquals("Optimal C++ Implementation", solveAnswer.shortSummary)
        assertTrue(solveAnswer.detailedAnswer.contains("#include <vector>"))
        assertFalse(solveAnswer.detailedAnswer.contains("This comment should be stripped"))
        assertFalse(solveAnswer.detailedAnswer.contains("Another comment to strip"))
    }

    @Test
    fun `only Gemini and Groq providers exist with default models gemini-3_8-flash and qwen3_8-27b`() {
        assertEquals(2, AiProvider.entries.size)
        assertEquals(listOf(AiProvider.GEMINI_CLOUD, AiProvider.GROQ), AiProvider.entries)

        val defaultSettings = BlinkSettings()
        assertEquals(AiProvider.GEMINI_CLOUD, defaultSettings.aiProvider)
        assertEquals("gemini-3.8-flash", defaultSettings.fastModel)
        assertEquals(GeminiRoutingMode.AUTO_SMART, defaultSettings.geminiRoutingMode)
        assertEquals("qwen/qwen3.8-27b", defaultSettings.groqModel)
        assertEquals(CodingLanguage.CPP, defaultSettings.codingLanguage)
        assertEquals("gemini-3.8-flash", BlinkAiPipeline.resolveGeminiModelId("gemini-2.0-flash"))
        assertEquals("gemini-3.8-flash", BlinkAiPipeline.resolveGeminiModelId("gemini-1.5-flash"))
        assertEquals("gemini-3.8-flash", BlinkAiPipeline.resolveGeminiModelId("gemini-3.8-flash"))
        assertEquals("gemini-3.6-flash", BlinkAiPipeline.resolveGeminiModelId("gemini-3.6-flash"))
        assertEquals("gemini-3-flash", BlinkAiPipeline.resolveGeminiModelId("gemini-3-flash"))
        assertEquals("gemini-2.5-flash", BlinkAiPipeline.resolveGeminiModelId("gemini-2.5-flash"))
        assertEquals("gemini-3.5-flash-lite", BlinkAiPipeline.resolveGeminiModelId("gemini-3.5-flash-lite"))
        assertEquals("gemini-3.1-flash-lite", BlinkAiPipeline.resolveGeminiModelId("gemini-3.1-flash-lite"))
    }

    @Test
    fun `Economy and Premium models match exact supplied RPM TPM and RPD rate limits and pipeline priorities`() {
        val economy = GeminiVisionModel.economyPipelineOrder
        assertEquals(
            listOf(GeminiVisionModel.GEMINI_3_5_FLASH_LITE, GeminiVisionModel.GEMINI_3_1_FLASH_LITE),
            economy
        )
        economy.forEachIndexed { idx, model ->
            assertEquals(GeminiModelTier.ECONOMY, model.tier)
            assertEquals(idx + 1, model.priority)
            assertEquals(15, model.rpmLimit)
            assertEquals(250_000, model.tpmLimit)
            assertEquals(500, model.rpdLimit)
        }

        val premium = GeminiVisionModel.premiumPipelineOrder
        assertEquals(
            listOf(
                GeminiVisionModel.GEMINI_3_8_FLASH,
                GeminiVisionModel.GEMINI_3_6_FLASH,
                GeminiVisionModel.GEMINI_3_FLASH,
                GeminiVisionModel.GEMINI_2_5_FLASH
            ),
            premium
        )
        premium.forEachIndexed { idx, model ->
            assertEquals(GeminiModelTier.PREMIUM, model.tier)
            assertEquals(idx + 1, model.priority)
            assertEquals(5, model.rpmLimit)
            assertEquals(250_000, model.tpmLimit)
            assertEquals(20, model.rpdLimit)
        }
    }

    @Test
    fun `GeminiServerQuotaTracker routes Economy vs Premium questions and skips exhausted models with Groq backup`() {
        val tracker = GeminiServerQuotaTracker()
        val presets = BlinkRepository.defaultPromptPresets()
        val mcqPreset = presets.first { it.id == BlinkRepository.PROMPT_ID_MCQ }
        val solvePreset = presets.first { it.id == BlinkRepository.PROMPT_ID_SOLVE }

        // Everyday / simple factual / MCQ / summary / extraction -> ECONOMY
        assertEquals(
            GeminiModelTier.ECONOMY,
            GeminiServerQuotaTracker.classifyQuestionTier(
                promptPreset = mcqPreset,
                customTypedPrompt = "",
                supplementalContext = "What is the capital of France? A) Berlin B) Paris"
            )
        )
        assertEquals(
            GeminiModelTier.ECONOMY,
            GeminiServerQuotaTracker.classifyQuestionTier(
                promptPreset = solvePreset,
                customTypedPrompt = "Define polymorphism in simple words",
                supplementalContext = "Definition and basic explanation"
            )
        )

        // Complex math / DSA / coding / debugging / architecture -> PREMIUM
        assertEquals(
            GeminiModelTier.PREMIUM,
            GeminiServerQuotaTracker.classifyQuestionTier(
                promptPreset = solvePreset,
                customTypedPrompt = "Solve this question",
                supplementalContext = "Given a directed graph, find the shortest path using Dijkstra dynamic programming algorithm in O(E log V) time complexity."
            )
        )

        // Verify Economy pipeline order includes Groq fast backup when Groq key is present
        val economyPlan = GeminiServerQuotaTracker.buildExecutionPlan(
            settings = BlinkSettings(
                geminiRoutingMode = GeminiRoutingMode.ECONOMY_PIPELINE,
                fastModel = "gemini-3.8-flash",
                groqApiKey = "gsk_TestBackupKey123",
                groqModel = "qwen/qwen3.8-27b"
            ),
            classifiedTier = GeminiModelTier.ECONOMY,
            quotaTracker = tracker
        )
        assertEquals(3, economyPlan.size)
        assertEquals(
            GeminiVisionModel.GEMINI_3_5_FLASH_LITE,
            (economyPlan[0] as PipelineExecutionCandidate.GeminiStep).model
        )
        assertEquals(
            GeminiVisionModel.GEMINI_3_1_FLASH_LITE,
            (economyPlan[1] as PipelineExecutionCandidate.GeminiStep).model
        )
        assertEquals(
            GroqVisionModel.FAST_BACKUP_MODEL_ID,
            (economyPlan[2] as PipelineExecutionCandidate.GroqBackupStep).modelId
        )

        // Verify Premium pipeline skips Gemini 3.8 Flash when server reports 429 daily quota exhaustion (21/20 RPD)
        tracker.recordServerQuotaExhausted(
            modelId = GeminiVisionModel.GEMINI_3_8_FLASH.modelId,
            responseBody = "429 RESOURCE_EXHAUSTED: Quota exceeded for requests per day (RPD limit 20)"
        )
        assertTrue(tracker.shouldSkipModel(GeminiVisionModel.GEMINI_3_8_FLASH))

        val premiumPlanAfter38Exhausted = GeminiServerQuotaTracker.buildExecutionPlan(
            settings = BlinkSettings(
                geminiRoutingMode = GeminiRoutingMode.PREMIUM_PIPELINE,
                fastModel = "gemini-3.8-flash",
                groqApiKey = "gsk_TestBackupKey123",
                groqModel = "qwen/qwen3.8-27b"
            ),
            classifiedTier = GeminiModelTier.PREMIUM,
            quotaTracker = tracker
        )
        // Should automatically skip gemini-3.8-flash and start with gemini-3.6-flash -> gemini-3-flash -> gemini-2.5-flash -> Groq strong backup
        assertEquals(4, premiumPlanAfter38Exhausted.size)
        assertEquals(
            GeminiVisionModel.GEMINI_3_6_FLASH,
            (premiumPlanAfter38Exhausted[0] as PipelineExecutionCandidate.GeminiStep).model
        )
        assertEquals(
            GeminiVisionModel.GEMINI_3_FLASH,
            (premiumPlanAfter38Exhausted[1] as PipelineExecutionCandidate.GeminiStep).model
        )
        assertEquals(
            GeminiVisionModel.GEMINI_2_5_FLASH,
            (premiumPlanAfter38Exhausted[2] as PipelineExecutionCandidate.GeminiStep).model
        )
        assertEquals(
            "qwen/qwen3.8-27b",
            (premiumPlanAfter38Exhausted[3] as PipelineExecutionCandidate.GroqBackupStep).modelId
        )

        // Verify Manual model pinning prioritizes the user's chosen model
        val manualPlan = GeminiServerQuotaTracker.buildExecutionPlan(
            settings = BlinkSettings(
                geminiRoutingMode = GeminiRoutingMode.MANUAL_MODEL,
                fastModel = GeminiVisionModel.GEMINI_3_1_FLASH_LITE.modelId,
                groqApiKey = ""
            ),
            classifiedTier = GeminiModelTier.PREMIUM,
            quotaTracker = tracker
        )
        assertEquals(
            GeminiVisionModel.GEMINI_3_1_FLASH_LITE,
            (manualPlan[0] as PipelineExecutionCandidate.GeminiStep).model
        )
    }

    @Test
    fun `Groq vision model validation accepts documented vision models and rejects text-only models`() {
        assertEquals("qwen/qwen3.8-27b", BlinkAiPipeline.validateGroqVisionModel("qwen/qwen3.8-27b"))
        assertEquals(
            "meta-llama/llama-4-scout-17b-16e-instruct",
            BlinkAiPipeline.validateGroqVisionModel("meta-llama/llama-4-scout-17b-16e-instruct")
        )
        assertEquals(
            "meta-llama/llama-4-maverick-17b-128e-instruct",
            BlinkAiPipeline.validateGroqVisionModel("meta-llama/llama-4-maverick-17b-128e-instruct")
        )

        assertThrows(IllegalArgumentException::class.java) {
            BlinkAiPipeline.validateGroqVisionModel("llama-3.1-8b-instant")
        }
    }

    @Test
    fun `Groq 3-image limit batching splits multi-image sessions without dropping any image`() {
        val fiveImages = listOf("img1", "img2", "img3", "img4", "img5")
        val batches = BlinkAiPipeline.chunkImagesForGroq(fiveImages, GroqVisionModel.MAX_IMAGES_PER_REQUEST)

        assertEquals(2, batches.size)
        assertEquals(listOf("img1", "img2", "img3"), batches[0])
        assertEquals(listOf("img4", "img5"), batches[1])
        assertEquals(fiveImages, batches.flatten())

        assertThrows(IllegalArgumentException::class.java) {
            BlinkAiPipeline.buildGroqRequestPayload(
                modelId = "qwen/qwen3.8-27b",
                promptText = "Solve",
                base64JpegImages = listOf("a", "b", "c", "d")
            )
        }
    }

    @Test
    fun `Gemini Interactions API and Groq payload builders support text-only, image-only, and combined multimodal requests`() {
        val interactionsTextOnly = BlinkAiPipeline.buildGeminiInteractionsPayload(
            modelId = "gemini-3.8-flash",
            promptText = "Explain how AI works in a few words",
            base64JpegImages = emptyList()
        )
        assertEquals("https://generativelanguage.googleapis.com/v1beta/interactions", BlinkAiPipeline.GEMINI_INTERACTIONS_ENDPOINT)
        assertEquals("gemini-3.8-flash", interactionsTextOnly.getString("model"))
        assertEquals("Explain how AI works in a few words", interactionsTextOnly.getString("input"))

        val interactionsMultimodal = BlinkAiPipeline.buildGeminiInteractionsPayload(
            modelId = "gemini-3.8-flash",
            promptText = "Solve this MCQ",
            base64JpegImages = listOf("b64_img_1", "b64_img_2")
        )
        assertEquals("gemini-3.8-flash", interactionsMultimodal.getString("model"))
        val interactionsInputArr = interactionsMultimodal.getJSONArray("input")
        assertEquals(3, interactionsInputArr.length())
        assertEquals("text", interactionsInputArr.getJSONObject(0).getString("type"))
        assertEquals("Solve this MCQ", interactionsInputArr.getJSONObject(0).getString("text"))
        assertEquals("image", interactionsInputArr.getJSONObject(1).getString("type"))
        assertEquals("b64_img_1", interactionsInputArr.getJSONObject(1).getString("data"))
        assertEquals("image/jpeg", interactionsInputArr.getJSONObject(1).getString("mime_type"))

        val pipeline = BlinkAiPipeline()
        val parsedOutputs = pipeline.extractGeminiCandidateText(
            """{"id":"int_123","outputs":[{"type":"thought","text":"thinking..."},{"type":"text","text":"AI learns patterns from data."}]}""",
            "gemini-3.8-flash"
        )
        assertEquals("AI learns patterns from data.", parsedOutputs)

        val parsedSteps = pipeline.extractGeminiCandidateText(
            """{"id":"int_456","steps":[{"signature":"sig","type":"thought"},{"content":[{"text":"Option B: 42","type":"text"}],"type":"model_output"}]}""",
            "gemini-3.8-flash"
        )
        assertEquals("Option B: 42", parsedSteps)

        val geminiTextOnly = BlinkAiPipeline.buildGeminiRequestPayload("What is 2+2?", emptyList())
        val geminiParts1 = geminiTextOnly.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        assertEquals(1, geminiParts1.length())
        assertTrue(geminiParts1.getJSONObject(0).getString("text").contains("What is 2+2?"))

        val geminiImageOnly = BlinkAiPipeline.buildGeminiRequestPayload("", listOf("b64_img_1"))
        val geminiParts2 = geminiImageOnly.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        assertEquals(2, geminiParts2.length())
        assertEquals("b64_img_1", geminiParts2.getJSONObject(0).getJSONObject("inline_data").getString("data"))

        val geminiCombined = BlinkAiPipeline.buildGeminiRequestPayload("Solve both", listOf("b64_1", "b64_2"))
        val geminiParts3 = geminiCombined.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        assertEquals(3, geminiParts3.length())

        val groqTextOnly = BlinkAiPipeline.buildGroqRequestPayload("qwen/qwen3.8-27b", "Ping", emptyList())
        assertEquals("qwen/qwen3.8-27b", groqTextOnly.getString("model"))
        val groqContent1 = groqTextOnly.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals(1, groqContent1.length())
        assertEquals("text", groqContent1.getJSONObject(0).getString("type"))

        val groqImageOnly = BlinkAiPipeline.buildGroqRequestPayload("qwen/qwen3.8-27b", "", listOf("b64_img_a"))
        val groqContent2 = groqImageOnly.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals(2, groqContent2.length())
        assertEquals("image_url", groqContent2.getJSONObject(1).getString("type"))

        val groqCombined = BlinkAiPipeline.buildGroqRequestPayload(
            "qwen/qwen3.8-27b",
            "Compare these 3 screens",
            listOf("img1", "img2", "img3")
        )
        val groqContent3 = groqCombined.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals(4, groqContent3.length())
    }

    @Test
    fun `SecureCredentialCipher encrypts keys out of plain text and sanitizeErrorMessage redacts secrets`() {
        val rawKey = "gsk_LiveSecretKeyValue123456789"
        val encrypted = SecureCredentialCipher.encrypt(rawKey)
        assertNotEquals(rawKey, encrypted)
        assertFalse(encrypted.contains(rawKey))
        assertTrue(SecureCredentialCipher.isEncryptedFormat(encrypted))
        assertEquals(rawKey, SecureCredentialCipher.decrypt(encrypted))
        assertEquals("", SecureCredentialCipher.encrypt("   "))
        assertEquals("", SecureCredentialCipher.decrypt(""))

        val rawError = "Request failed for key gsk_LiveSecretKeyValue123456789 with 401"
        val scrubbed = BlinkAiPipeline.sanitizeErrorMessage(rawError, rawKey)
        assertFalse(scrubbed.contains(rawKey))
        assertTrue(scrubbed.contains("[REDACTED_KEY]"))
    }

    @Test
    fun `SMART_DISPATCH dispatches directly to external app when no custom API key is configured`() {
        val defaultSmartSettings = BlinkSettings(
            dispatchMode = DispatchMode.SMART_DISPATCH,
            targetAiApp = TargetAiApp.SYSTEM_CHOOSER,
            geminiApiKey = ""
        )
        assertTrue(BlinkViewModel.shouldDispatchDirectlyToExternalApp(defaultSmartSettings))

        val keyedSmartSettings = defaultSmartSettings.copy(geminiApiKey = "AIzaSyTestKey123")
        assertFalse(BlinkViewModel.shouldDispatchDirectlyToExternalApp(keyedSmartSettings))
    }

    @Test
    fun `scaleDownIfNeeded bounds large bitmaps to prevent memory exhaustion`() {
        val large = Bitmap.createBitmap(2000, 1500, Bitmap.Config.ARGB_8888)
        val scaled = BitmapIntelligence.scaleDownIfNeeded(large, 1280)
        assertTrue(scaled.width <= 1280)
        assertTrue(scaled.height <= 1280)
    }
}
