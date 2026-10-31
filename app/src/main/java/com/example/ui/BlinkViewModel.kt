package com.example.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.AiHandoffDispatcher
import com.example.ai.BlinkAiPipeline
import com.example.ai.GeminiServerQuotaTracker
import com.example.data.AiProvider
import com.example.data.AnswerStyle
import com.example.data.BlinkAnswer
import com.example.data.BlinkDatabase
import com.example.data.BlinkPreferencesRepository
import com.example.data.BlinkRepository
import com.example.data.BlinkSettings
import com.example.data.CaptureSensitivity
import com.example.data.CapturedFrame
import com.example.data.CodingLanguage
import com.example.data.DispatchMode
import com.example.data.FrameTelemetry
import com.example.data.GeminiModelQuotaStatus
import com.example.data.GeminiRoutingMode
import com.example.data.GeminiVisionModel
import com.example.data.GroqVisionModel
import com.example.data.ImageResolutionPref
import com.example.data.PromptPresetEntity
import com.example.data.ProviderConnectionStatus
import com.example.data.ScanPhase
import com.example.data.SessionHistoryEntity
import com.example.data.TargetAiApp
import com.example.data.VerificationBadge
import com.example.vision.BitmapIntelligence
import com.example.vision.OpticalTargetCard
import com.example.vision.TargetLensGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private const val TAG = "BlinkViewModel"

enum class OverlaySheet {
    NONE,
    SETTINGS,
    HISTORY,
    HANDOFF_DISPATCHER
}

data class BlinkUiState(
    val scanPhase: ScanPhase = ScanPhase.SCANNING,
    val capturedFrames: List<CapturedFrame> = emptyList(),
    val telemetry: FrameTelemetry = FrameTelemetry(),
    val activePrompt: PromptPresetEntity = BlinkRepository.defaultPromptPresets().first(),
    val synthesizedPromptPreview: String = "",
    val detectedDomainSummary: String = "MCQ Answer",
    val activeAnswer: BlinkAnswer? = null,
    val isAnswerExpanded: Boolean = false,
    val errorMessage: String? = null,
    val isBlackScreenMode: Boolean = false,
    val activeSheet: OverlaySheet = OverlaySheet.NONE,
    val activeTargetIndex: Int? = null,
    val targetAutoLockProgress: Float = 0f,
    val captureFlashPulse: Boolean = false,
    val hapticTriggerCount: Int = 0,
    val statusToastMessage: String? = null,
    val scanCooldownUntilMs: Long = 0L,
    val geminiQuotaStatuses: List<GeminiModelQuotaStatus> = GeminiVisionModel.entries.map {
        GeminiModelQuotaStatus(model = it)
    },
    val geminiConnectionStatus: ProviderConnectionStatus = ProviderConnectionStatus(
        provider = AiProvider.GEMINI_CLOUD,
        modelTested = BlinkSettings.DEFAULT_GEMINI_MODEL
    ),
    val groqConnectionStatus: ProviderConnectionStatus = ProviderConnectionStatus(
        provider = AiProvider.GROQ,
        modelTested = GroqVisionModel.DEFAULT_MODEL_ID
    )
)

class BlinkViewModel(application: Application) : AndroidViewModel(application) {

    private val database = BlinkDatabase.getInstance(application)
    private val repository = BlinkRepository(database.blinkDao())
    private val prefsRepo = BlinkPreferencesRepository(application)

    private val quotaTracker = GeminiServerQuotaTracker { updatedStatuses, exportedJson ->
        _uiState.update { it.copy(geminiQuotaStatuses = updatedStatuses) }
        viewModelScope.launch {
            prefsRepo.saveGeminiQuotaStateJson(exportedJson)
        }
    }

    private val aiPipeline = BlinkAiPipeline(quotaTracker = quotaTracker)

    val settingsState: StateFlow<BlinkSettings> = prefsRepo.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = BlinkSettings()
    )

    val promptsState: StateFlow<List<PromptPresetEntity>> = repository.allPrompts.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = BlinkRepository.defaultPromptPresets()
    )

    val historyState: StateFlow<List<SessionHistoryEntity>> = repository.allHistory.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    private val _uiState = MutableStateFlow(BlinkUiState())
    val uiState: StateFlow<BlinkUiState> = _uiState.asStateFlow()

    private var activeAiJob: Job? = null
    private var targetLockJob: Job? = null
    private var initializedBlackModePref = false
    private var handoffLaunchedAtMs: Long = 0L

    init {
        viewModelScope.launch {
            repository.ensureDefaultPromptsSeeded()
        }
        viewModelScope.launch {
            val savedQuotaJson = prefsRepo.geminiQuotaStateJsonFlow.firstOrNull().orEmpty()
            if (savedQuotaJson.isNotBlank()) {
                quotaTracker.importFromJson(savedQuotaJson)
            }
            _uiState.update {
                it.copy(geminiQuotaStatuses = quotaTracker.getAllQuotaStatuses())
            }
        }
        viewModelScope.launch {
            settingsState.collect { settings ->
                if (!initializedBlackModePref) {
                    initializedBlackModePref = true
                    if (settings.startInBlackScreenMode) {
                        _uiState.update { it.copy(isBlackScreenMode = true) }
                    }
                }
                val prompts = promptsState.value
                val currentActive = _uiState.value.activePrompt
                if (prompts.isNotEmpty() && prompts.none { it.id == currentActive.id }) {
                    val defaultMatch = prompts.find { it.id == settings.defaultPromptId } ?: prompts.first()
                    _uiState.update { it.copy(activePrompt = defaultMatch) }
                }
            }
        }
        viewModelScope.launch {
            promptsState.collect { prompts ->
                if (prompts.isNotEmpty()) {
                    val defaultId = settingsState.value.defaultPromptId
                    val currentId = _uiState.value.activePrompt.id
                    val match = prompts.find { it.id == currentId }
                        ?: prompts.find { it.id == defaultId }
                        ?: prompts.first()
                    _uiState.update { it.copy(activePrompt = match) }
                }
            }
        }
    }

    /**
     * Returns true only when the smart camera should actively analyze and auto-click on readable text.
     * Pauses automatically when any sheet is open or during post-action cooldown.
     */
    fun isScannerActiveNow(): Boolean {
        val state = _uiState.value
        val now = System.currentTimeMillis()
        return state.scanPhase == ScanPhase.SCANNING &&
            state.activeSheet == OverlaySheet.NONE &&
            state.activeTargetIndex == null &&
            now >= state.scanCooldownUntilMs
    }

    fun onTelemetryUpdated(telemetry: FrameTelemetry) {
        if (_uiState.value.activeTargetIndex == null &&
            _uiState.value.scanPhase == ScanPhase.SCANNING &&
            _uiState.value.activeSheet == OverlaySheet.NONE
        ) {
            _uiState.update { it.copy(telemetry = telemetry) }
        }
    }

    companion object {
        fun hasCustomApiKeyConfigured(settings: BlinkSettings): Boolean {
            return when (settings.aiProvider) {
                AiProvider.GEMINI_CLOUD -> settings.geminiApiKey.isNotBlank()
                AiProvider.GROQ -> settings.groqApiKey.isNotBlank()
            }
        }

        /**
         * Determines whether Blink should immediately hand off to an installed AI app / Share Sheet
         * before attempting a cloud API request.
         */
        fun shouldDispatchDirectlyToExternalApp(settings: BlinkSettings): Boolean {
            return when (settings.dispatchMode) {
                DispatchMode.AI_APP_HANDOFF -> true
                DispatchMode.SMART_DISPATCH -> !hasCustomApiKeyConfigured(settings)
                DispatchMode.CLOUD_API_ONLY -> false
            }
        }
    }

    /**
     * Called when the smart camera auto-clicks as soon as it sees readable text,
     * or when the user manually taps to snap.
     * No shape/screen cropping and no OCR text injected into prompts.
     */
    fun onFrameCaptured(
        bitmap: Bitmap,
        sharpnessScore: Float,
        dHash: Long,
        sourceLabel: String = "Readable Text",
        ocrText: String = "",
        bypassDuplicateCheck: Boolean = false
    ) {
        val currentState = _uiState.value
        if (currentState.activeSheet != OverlaySheet.NONE) return
        if (currentState.scanPhase != ScanPhase.SCANNING && !bypassDuplicateCheck) return

        val maxImages = settingsState.value.maxImagesPerSession
        if (currentState.capturedFrames.size >= maxImages) {
            showTransientToast("Session limit ($maxImages images) reached — tap bottom to send")
            _uiState.update { it.copy(scanPhase = ScanPhase.CONTEXT_CONFIRMATION) }
            return
        }

        if (!bypassDuplicateCheck) {
            val isDuplicate = currentState.capturedFrames.any { existing ->
                BitmapIntelligence.hammingDistance(existing.dHash, dHash) <= 8
            }
            if (isDuplicate) {
                showTransientToast("Same text already captured — point at new text")
                return
            }
        }

        viewModelScope.launch {
            val maxDim = settingsState.value.imageResolution.maxDimensionPx.coerceIn(640, 1280)
            val boundedBitmap = withContext(Dispatchers.Default) {
                BitmapIntelligence.scaleDownIfNeeded(bitmap, maxDim)
            }

            val frame = CapturedFrame(
                id = UUID.randomUUID().toString(),
                bitmap = boundedBitmap,
                timestamp = System.currentTimeMillis(),
                sharpnessScore = sharpnessScore,
                dHash = dHash,
                sourceLabel = sourceLabel,
                ocrText = "",
                detectedSurfaceType = sourceLabel
            )

            targetLockJob?.cancel()
            _uiState.update { state ->
                val updatedFrames = state.capturedFrames + frame
                val (synthesized, domain) = AiHandoffDispatcher.buildSynthesizedMasterPrompt(
                    frames = updatedFrames,
                    promptPreset = state.activePrompt,
                    customTypedPrompt = "",
                    answerStyle = settingsState.value.answerStyle,
                    codingLanguage = settingsState.value.codingLanguage
                )
                state.copy(
                    capturedFrames = updatedFrames,
                    scanPhase = ScanPhase.CONTEXT_CONFIRMATION,
                    synthesizedPromptPreview = synthesized,
                    detectedDomainSummary = domain,
                    captureFlashPulse = true,
                    hapticTriggerCount = state.hapticTriggerCount + 1,
                    targetAutoLockProgress = 0f,
                    errorMessage = null
                )
            }

            delay(200)
            _uiState.update { it.copy(captureFlashPulse = false) }
        }
    }

    /**
     * Gesture Shortcut: Double-Tap Center of Screen = "Need More Context" (Add another image).
     */
    fun onRequestMoreContext() {
        val maxImages = settingsState.value.maxImagesPerSession
        val currentCount = _uiState.value.capturedFrames.size
        if (currentCount >= maxImages) {
            showTransientToast("Maximum $maxImages images collected — dispatching now")
            onFinishContextAndSolve()
            return
        }

        val nextTargetIdx = _uiState.value.activeTargetIndex?.let { idx ->
            (idx + 1) % TargetLensGenerator.targets.size
        }

        val cooldownUntil = System.currentTimeMillis() + 1_200L

        _uiState.update {
            it.copy(
                scanPhase = ScanPhase.SCANNING,
                activeTargetIndex = nextTargetIdx,
                targetAutoLockProgress = 0f,
                scanCooldownUntilMs = cooldownUntil,
                hapticTriggerCount = it.hapticTriggerCount + 1,
                errorMessage = null
            )
        }

        showTransientToast("Point at next readable text (#${currentCount + 1})")

        if (nextTargetIdx != null && settingsState.value.autoCaptureEnabled) {
            startTargetAutoLockCountdown(nextTargetIdx)
        }
    }

    /**
     * Gesture Shortcut: Single-Tap Bottom of Screen = "Done / Solve Now".
     */
    fun onFinishContextAndSolve() {
        val frames = _uiState.value.capturedFrames
        if (frames.isEmpty()) {
            showTransientToast("Point at readable text to capture first")
            return
        }

        val currentSettings = settingsState.value
        val shouldHandoffDirectly = shouldDispatchDirectlyToExternalApp(currentSettings)

        if (shouldHandoffDirectly) {
            dispatchToExternalAiApp(currentSettings.targetAiApp)
            return
        }

        activeAiJob?.cancel()
        val activePrompt = _uiState.value.activePrompt

        activeAiJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    scanPhase = ScanPhase.PROCESSING_FAST,
                    activeAnswer = null,
                    isAnswerExpanded = false,
                    hapticTriggerCount = it.hapticTriggerCount + 1,
                    errorMessage = null
                )
            }

            try {
                if (currentSettings.aiProvider == AiProvider.GROQ &&
                    frames.size > GroqVisionModel.MAX_IMAGES_PER_REQUEST
                ) {
                    showTransientToast(
                        "Groq 3-image limit: processing all ${frames.size} images in batches..."
                    )
                }

                val answer = aiPipeline.runAnswerPipeline(
                    frames = frames,
                    promptPreset = activePrompt,
                    customTypedPrompt = "",
                    settings = currentSettings
                )

                _uiState.update { state ->
                    state.copy(
                        scanPhase = ScanPhase.ANSWER_DISPLAY,
                        activeAnswer = answer,
                        isAnswerExpanded = !answer.isShortAnswer,
                        hapticTriggerCount = state.hapticTriggerCount + 1
                    )
                }

                if (currentSettings.saveSessionHistory) {
                    repository.recordSession(
                        promptTitle = activePrompt.title,
                        answer = answer,
                        imageCount = frames.size
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val safeMsg = BlinkAiPipeline.sanitizeErrorMessage(
                    e.localizedMessage ?: "Cloud API failed.",
                    currentSettings.geminiApiKey,
                    currentSettings.groqApiKey
                )
                Log.e(TAG, "Cloud API pipeline failed: $safeMsg")
                if (currentSettings.dispatchMode == DispatchMode.SMART_DISPATCH) {
                    showTransientToast("Cloud API unavailable — handing off images to AI app")
                    dispatchToExternalAiApp(currentSettings.targetAiApp)
                } else {
                    _uiState.update {
                        it.copy(
                            scanPhase = ScanPhase.CONTEXT_CONFIRMATION,
                            errorMessage = safeMsg
                        )
                    }
                }
            }
        }
    }

    /**
     * Packages the captured images + preconfigured instruction (MCQ Answer or Solve Accurately)
     * and launches the selected installed AI application.
     */
    fun dispatchToExternalAiApp(targetApp: TargetAiApp = settingsState.value.targetAiApp) {
        val frames = _uiState.value.capturedFrames
        if (frames.isEmpty()) {
            showTransientToast("Capture at least one frame to handoff")
            return
        }

        viewModelScope.launch {
            val appContext = getApplication<Application>()
            val currentSettings = settingsState.value
            val activePrompt = _uiState.value.activePrompt

            val pkg = AiHandoffDispatcher.prepareHandoffPackage(
                context = appContext,
                frames = frames,
                promptPreset = activePrompt,
                customTypedPrompt = "",
                answerStyle = currentSettings.answerStyle,
                targetApp = targetApp,
                codingLanguage = currentSettings.codingLanguage,
                jpegQuality = currentSettings.imageResolution.jpegQuality
            )

            if (currentSettings.saveSessionHistory) {
                val handoffSummary = BlinkAnswer(
                    shortSummary = "Dispatched ${frames.size} image(s) to ${targetApp.displayName}",
                    detailedAnswer = "Preconfigured Mode (${activePrompt.title}):\n\n${pkg.synthesizedPrompt}",
                    isShortAnswer = false,
                    detectedCategory = pkg.detectedDomain,
                    extractedQuestion = activePrompt.title,
                    fastModelUsed = "Handoff → ${targetApp.displayName}",
                    fastLatencyMs = 45L,
                    verificationBadge = VerificationBadge.FAST_INITIAL,
                    verificationNote = "Image attachment(s) transferred via AI App Handoff."
                )
                repository.recordSession(
                    promptTitle = activePrompt.title,
                    answer = handoffSummary,
                    imageCount = frames.size
                )
            }

            handoffLaunchedAtMs = System.currentTimeMillis()
            _uiState.update { it.copy(activeSheet = OverlaySheet.NONE) }

            val launched = AiHandoffDispatcher.launchAiAppHandoff(appContext, pkg)
            if (launched) {
                showTransientToast("${frames.size} image(s) sent to ${targetApp.displayName}")
            } else {
                showTransientToast("Copied to clipboard")
            }
        }
    }

    /**
     * Automatically resumes scanning when the user returns to Blink after completing
     * their interaction in an external AI application.
     */
    fun onActivityResumed() {
        val launchedAt = handoffLaunchedAtMs
        if (launchedAt > 0L && (System.currentTimeMillis() - launchedAt) > 700L) {
            handoffLaunchedAtMs = 0L
            startNewScanSession(showResumeToast = true)
        }
    }

    fun cancelActiveSession() {
        activeAiJob?.cancel()
        activeAiJob = null
        targetLockJob?.cancel()
        val cooldown = System.currentTimeMillis() + 1_500L
        _uiState.update {
            it.copy(
                scanPhase = if (it.capturedFrames.isNotEmpty()) {
                    ScanPhase.CONTEXT_CONFIRMATION
                } else {
                    ScanPhase.SCANNING
                },
                scanCooldownUntilMs = cooldown,
                errorMessage = null
            )
        }
        showTransientToast("Cancelled")
    }

    fun startNewScanSession(showResumeToast: Boolean = false) {
        activeAiJob?.cancel()
        targetLockJob?.cancel()
        val cooldown = System.currentTimeMillis() + 1_400L
        _uiState.update {
            it.copy(
                scanPhase = ScanPhase.SCANNING,
                capturedFrames = emptyList(),
                synthesizedPromptPreview = "",
                activeAnswer = null,
                isAnswerExpanded = false,
                errorMessage = null,
                targetAutoLockProgress = 0f,
                scanCooldownUntilMs = cooldown
            )
        }
        if (showResumeToast) {
            showTransientToast("Returned to Blink • Point at readable text")
        }
        val targetIdx = _uiState.value.activeTargetIndex
        if (targetIdx != null && settingsState.value.autoCaptureEnabled) {
            startTargetAutoLockCountdown(targetIdx)
        }
    }

    fun removeCapturedFrame(frameId: String) {
        _uiState.update { state ->
            val remaining = state.capturedFrames.filterNot { it.id == frameId }
            val (synthesized, domain) = AiHandoffDispatcher.buildSynthesizedMasterPrompt(
                frames = remaining,
                promptPreset = state.activePrompt,
                customTypedPrompt = "",
                answerStyle = settingsState.value.answerStyle,
                codingLanguage = settingsState.value.codingLanguage
            )
            state.copy(
                capturedFrames = remaining,
                synthesizedPromptPreview = synthesized,
                detectedDomainSummary = domain,
                scanPhase = if (remaining.isEmpty()) ScanPhase.SCANNING else state.scanPhase,
                scanCooldownUntilMs = System.currentTimeMillis() + 1_400L
            )
        }
    }

    fun toggleAnswerExpanded() {
        _uiState.update { it.copy(isAnswerExpanded = !it.isAnswerExpanded) }
    }

    fun toggleBlackScreenMode() {
        val nextMode = !_uiState.value.isBlackScreenMode
        _uiState.update {
            it.copy(
                isBlackScreenMode = nextMode,
                hapticTriggerCount = it.hapticTriggerCount + 1
            )
        }
    }

    fun toggleAutoCaptureMode() {
        val next = !settingsState.value.autoCaptureEnabled
        viewModelScope.launch {
            prefsRepo.updateAutoCapture(next)
            showTransientToast(
                if (next) "Smart Auto-Click ON (Clicks as soon as readable text is seen)"
                else "Manual Capture Only (Tap center reticle to snap)"
            )
        }
    }

    fun selectPromptPreset(prompt: PromptPresetEntity) {
        viewModelScope.launch {
            prefsRepo.updateDefaultPromptId(prompt.id)
        }
        _uiState.update { state ->
            val (synthesized, domain) = AiHandoffDispatcher.buildSynthesizedMasterPrompt(
                frames = state.capturedFrames,
                promptPreset = prompt,
                customTypedPrompt = "",
                answerStyle = settingsState.value.answerStyle,
                codingLanguage = settingsState.value.codingLanguage
            )
            state.copy(
                activePrompt = prompt,
                synthesizedPromptPreview = synthesized,
                detectedDomainSummary = domain,
                activeSheet = OverlaySheet.NONE
            )
        }
        showTransientToast("Mode: ${prompt.title}")
    }

    /**
     * Switches directly between the two preconfigured modes:
     * 1. MCQ Answer
     * 2. Solve Accurately
     */
    fun togglePreconfiguredMode() {
        val presets = promptsState.value.ifEmpty { BlinkRepository.defaultPromptPresets() }
        val currentId = _uiState.value.activePrompt.id
        val nextPreset = presets.firstOrNull { it.id != currentId } ?: presets.first()
        selectPromptPreset(nextPreset)
    }

    /**
     * Sets the default coding language (e.g. C++, Python, SQL, Java, JS) and updates the live prompt preview.
     */
    fun updateCodingLanguage(language: CodingLanguage) {
        viewModelScope.launch {
            prefsRepo.updateCodingLanguage(language)
        }
        _uiState.update { state ->
            val (synthesized, domain) = AiHandoffDispatcher.buildSynthesizedMasterPrompt(
                frames = state.capturedFrames,
                promptPreset = state.activePrompt,
                customTypedPrompt = "",
                answerStyle = settingsState.value.answerStyle,
                codingLanguage = language
            )
            state.copy(
                synthesizedPromptPreview = synthesized,
                detectedDomainSummary = domain
            )
        }
        showTransientToast("Coding Language: ${language.displayName}")
    }

    /**
     * Quick 1-tap toggle for the default coding language (cycles C++ -> Python -> SQL -> Java -> JS).
     */
    fun cycleCodingLanguage() {
        val nextLang = settingsState.value.codingLanguage.nextInCycle()
        updateCodingLanguage(nextLang)
    }

    fun openSheet(sheet: OverlaySheet) {
        targetLockJob?.cancel()
        _uiState.update {
            it.copy(
                activeSheet = sheet,
                scanCooldownUntilMs = System.currentTimeMillis() + 1_500L
            )
        }
    }

    fun closeSheet() {
        _uiState.update {
            it.copy(
                activeSheet = OverlaySheet.NONE,
                scanCooldownUntilMs = System.currentTimeMillis() + 1_500L
            )
        }
    }

    fun selectOpticalTarget(index: Int?) {
        targetLockJob?.cancel()
        _uiState.update {
            it.copy(
                activeTargetIndex = index,
                targetAutoLockProgress = 0f,
                scanPhase = ScanPhase.SCANNING,
                errorMessage = null,
                scanCooldownUntilMs = System.currentTimeMillis() + 800L
            )
        }
        if (index != null && settingsState.value.autoCaptureEnabled) {
            startTargetAutoLockCountdown(index)
        }
    }

    fun captureCurrentOpticalTargetNow() {
        val index = _uiState.value.activeTargetIndex ?: return
        val target = TargetLensGenerator.targets.getOrNull(index) ?: return
        targetLockJob?.cancel()
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.Default) {
                TargetLensGenerator.renderTargetToBitmap(target)
            }
            val (sharpness, dHash) = withContext(Dispatchers.Default) {
                BitmapIntelligence.computeBitmapMetrics(bmp)
            }
            onFrameCaptured(
                bitmap = bmp,
                sharpnessScore = sharpness,
                dHash = dHash,
                sourceLabel = target.shortTitle,
                ocrText = "",
                bypassDuplicateCheck = true
            )
        }
    }

    private fun startTargetAutoLockCountdown(targetIndex: Int) {
        targetLockJob?.cancel()
        val target: OpticalTargetCard = TargetLensGenerator.targets.getOrNull(targetIndex) ?: return
        targetLockJob = viewModelScope.launch {
            delay(300L)
            val steps = 4
            val tickMs = 80L
            for (step in 1..steps) {
                if (_uiState.value.scanPhase != ScanPhase.SCANNING ||
                    _uiState.value.activeTargetIndex != targetIndex ||
                    _uiState.value.activeSheet != OverlaySheet.NONE ||
                    !settingsState.value.autoCaptureEnabled
                ) {
                    _uiState.update { it.copy(targetAutoLockProgress = 0f) }
                    return@launch
                }
                val progress = step.toFloat() / steps.toFloat()
                _uiState.update {
                    it.copy(
                        targetAutoLockProgress = progress,
                        telemetry = FrameTelemetry(
                            sharpnessScore = 44.0f,
                            stabilityScore = 0.96f,
                            luminance = 150f,
                            lockProgress = progress,
                            isDuplicateFrame = false,
                            hasReadableContent = true,
                            isDisplayScreenDetected = false,
                            screenConfidence = 0f,
                            detectedQuad = null,
                            ocrSnippet = "",
                            ocrCharCount = 42,
                            ocrBlockCount = 2,
                            statusHint = "Readable text detected — Auto-clicking ${target.shortTitle}..."
                        )
                    )
                }
                delay(tickMs)
            }
            captureCurrentOpticalTargetNow()
        }
    }

    fun onPhotoPickerUriSelected(uri: Uri) {
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                val rawBitmap = withContext(Dispatchers.IO) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        val source = ImageDecoder.createSource(context.contentResolver, uri)
                        ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                            decoder.isMutableRequired = true
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                    }
                }
                val maxDim = settingsState.value.imageResolution.maxDimensionPx
                val scaled = withContext(Dispatchers.Default) {
                    BitmapIntelligence.scaleDownIfNeeded(rawBitmap, maxDim)
                }
                val (sharpness, dHash) = withContext(Dispatchers.Default) {
                    BitmapIntelligence.computeBitmapMetrics(scaled)
                }
                onFrameCaptured(
                    bitmap = scaled,
                    sharpnessScore = sharpness,
                    dHash = dHash,
                    sourceLabel = "Imported Image",
                    ocrText = "",
                    bypassDuplicateCheck = true
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decode imported image")
                showTransientToast("Could not load selected image")
            }
        }
    }

    fun loadHistorySessionToView(item: SessionHistoryEntity) {
        _uiState.update {
            it.copy(
                activeAnswer = item.toBlinkAnswer(),
                scanPhase = ScanPhase.ANSWER_DISPLAY,
                isAnswerExpanded = !item.isShortAnswer,
                activeSheet = OverlaySheet.NONE
            )
        }
    }

    fun deleteHistoryEntry(id: Int) {
        viewModelScope.launch {
            repository.deleteHistoryItem(id)
        }
    }

    fun clearAllSessionData() {
        viewModelScope.launch {
            repository.clearAllHistory()
            startNewScanSession()
            showTransientToast("All session history and cached captures cleared")
        }
    }

    // Settings updates
    fun updateDispatchMode(mode: DispatchMode) = viewModelScope.launch {
        prefsRepo.updateDispatchMode(mode)
        showTransientToast("Mode: ${mode.displayName}")
    }

    fun updateTargetAiApp(app: TargetAiApp) = viewModelScope.launch {
        prefsRepo.updateTargetAiApp(app)
        showTransientToast("Target AI App: ${app.displayName}")
    }

    fun updateAiProvider(provider: AiProvider) = viewModelScope.launch {
        prefsRepo.updateAiProvider(provider)
        showTransientToast("AI Provider: ${provider.displayName}")
    }

    fun updateGeminiRoutingMode(mode: GeminiRoutingMode) = viewModelScope.launch {
        prefsRepo.updateGeminiRoutingMode(mode)
        showTransientToast("Gemini Routing: ${mode.displayName}")
    }

    fun cycleGeminiRoutingMode() {
        val entries = GeminiRoutingMode.entries
        val current = settingsState.value.geminiRoutingMode
        val next = entries[(current.ordinal + 1) % entries.size]
        updateGeminiRoutingMode(next)
    }

    fun updateGeminiModel(modelId: String) = viewModelScope.launch {
        if (!GeminiVisionModel.isSupportedModel(modelId)) {
            showTransientToast("Unsupported Gemini model: $modelId")
            return@launch
        }
        val modelObj = GeminiVisionModel.fromModelIdOrNull(modelId)
        prefsRepo.updateGeminiModel(modelId, switchToManualMode = true)
        val badge = modelObj?.let { "${it.displayName} (${it.rpdLimit} RPD)" } ?: modelId
        showTransientToast("Manual Model Pinned: $badge")
    }

    fun resetGeminiQuotaTracker() = viewModelScope.launch {
        quotaTracker.resetAllQuotas()
        showTransientToast("Server rate-limit counters reset")
    }

    fun updateGroqModel(modelId: String) = viewModelScope.launch {
        if (!GroqVisionModel.isSupportedVisionModel(modelId)) {
            showTransientToast("Unsupported vision model: $modelId")
            return@launch
        }
        prefsRepo.updateGroqModel(modelId)
        showTransientToast("Groq Model: $modelId")
    }

    fun updateAnswerStyle(style: AnswerStyle) = viewModelScope.launch {
        prefsRepo.updateAnswerStyle(style)
    }

    fun updateCaptureSensitivity(sensitivity: CaptureSensitivity) = viewModelScope.launch {
        prefsRepo.updateCaptureSensitivity(sensitivity)
    }

    fun updateImageResolution(resolution: ImageResolutionPref) = viewModelScope.launch {
        prefsRepo.updateImageResolution(resolution)
    }

    fun updateMaxImages(max: Int) = viewModelScope.launch {
        prefsRepo.updateMaxImages(max)
    }

    fun updateAutoCapture(enabled: Boolean) = viewModelScope.launch {
        prefsRepo.updateAutoCapture(enabled)
    }

    fun updateStartInBlackMode(enabled: Boolean) = viewModelScope.launch {
        prefsRepo.updateStartInBlackMode(enabled)
    }

    fun updateAllowBrightnessControl(allowed: Boolean) = viewModelScope.launch {
        prefsRepo.updateAllowBrightnessControl(allowed)
    }

    fun updateCustomBrightnessLevel(level: Float) = viewModelScope.launch {
        prefsRepo.updateCustomBrightnessLevel(level)
    }

    fun updateSaveHistory(enabled: Boolean) = viewModelScope.launch {
        prefsRepo.updateSaveHistory(enabled)
    }

    fun saveGeminiApiKey(geminiKey: String) {
        viewModelScope.launch {
            val clean = geminiKey.trim()
            prefsRepo.updateGeminiApiKey(clean)
            _uiState.update {
                it.copy(
                    geminiConnectionStatus = ProviderConnectionStatus(
                        provider = AiProvider.GEMINI_CLOUD,
                        modelTested = BlinkSettings.DEFAULT_GEMINI_MODEL,
                        message = if (clean.isEmpty()) "Gemini API key removed." else "Gemini API key saved."
                    )
                )
            }
            showTransientToast(if (clean.isEmpty()) "Gemini API key removed" else "Gemini API key saved")
        }
    }

    fun saveGroqApiKey(groqKey: String) {
        viewModelScope.launch {
            val clean = groqKey.trim()
            prefsRepo.updateGroqApiKey(clean)
            _uiState.update {
                it.copy(
                    groqConnectionStatus = ProviderConnectionStatus(
                        provider = AiProvider.GROQ,
                        modelTested = settingsState.value.groqModel,
                        message = if (clean.isEmpty()) "Groq API key removed." else "Groq API key saved."
                    )
                )
            }
            showTransientToast(if (clean.isEmpty()) "Groq API key removed" else "Groq API key saved")
        }
    }

    /**
     * Runs a real network connection test against Google Gemini (`gemini-3.8-flash`)
     * or Groq (`qwen/qwen3.8-27b` or selected Groq vision model).
     */
    fun testProviderConnection(
        provider: AiProvider,
        draftGeminiKey: String? = null,
        draftGroqKey: String? = null,
        draftGroqModel: String? = null
    ) {
        viewModelScope.launch {
            val currentSettings = settingsState.value
            val effectiveGeminiKey = draftGeminiKey?.trim() ?: currentSettings.geminiApiKey
            val effectiveGroqKey = draftGroqKey?.trim() ?: currentSettings.groqApiKey
            val effectiveGroqModel = draftGroqModel?.trim()?.takeIf { it.isNotEmpty() } ?: currentSettings.groqModel

            if (provider == AiProvider.GEMINI_CLOUD && draftGeminiKey != null) {
                prefsRepo.updateGeminiApiKey(effectiveGeminiKey)
            }
            if (provider == AiProvider.GROQ) {
                if (draftGroqKey != null) {
                    prefsRepo.updateGroqApiKey(effectiveGroqKey)
                }
                if (draftGroqModel != null && GroqVisionModel.isSupportedVisionModel(effectiveGroqModel)) {
                    prefsRepo.updateGroqModel(effectiveGroqModel)
                }
            }

            val testSettings = currentSettings.copy(
                geminiApiKey = effectiveGeminiKey,
                groqApiKey = effectiveGroqKey,
                groqModel = effectiveGroqModel
            )

            val activeGeminiModel = BlinkAiPipeline.resolveGeminiModelId(currentSettings.fastModel)
            _uiState.update { state ->
                if (provider == AiProvider.GEMINI_CLOUD) {
                    state.copy(
                        geminiConnectionStatus = ProviderConnectionStatus(
                            provider = AiProvider.GEMINI_CLOUD,
                            isTesting = true,
                            modelTested = activeGeminiModel,
                            message = "Testing connection to Google Gemini ($activeGeminiModel)..."
                        )
                    )
                } else {
                    state.copy(
                        groqConnectionStatus = ProviderConnectionStatus(
                            provider = AiProvider.GROQ,
                            isTesting = true,
                            modelTested = effectiveGroqModel,
                            message = "Testing connection to Groq ($effectiveGroqModel)..."
                        )
                    )
                }
            }

            val result = aiPipeline.testProviderConnection(provider, testSettings)
            _uiState.update { state ->
                if (provider == AiProvider.GEMINI_CLOUD) {
                    state.copy(geminiConnectionStatus = result)
                } else {
                    state.copy(groqConnectionStatus = result)
                }
            }
        }
    }

    fun showTransientToast(message: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(statusToastMessage = message) }
            delay(2300)
            _uiState.update { state ->
                if (state.statusToastMessage == message) {
                    state.copy(statusToastMessage = null)
                } else {
                    state
                }
            }
        }
    }
}
