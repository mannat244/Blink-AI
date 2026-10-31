package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PauseCircleOutline
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.CodingLanguage
import com.example.data.PromptPresetEntity
import com.example.data.ScanPhase
import com.example.ui.theme.AmoledBlack
import com.example.ui.theme.LockMint
import com.example.ui.theme.ObsidianCard
import com.example.ui.theme.ObsidianElevated
import com.example.ui.theme.OpticalCyan
import com.example.ui.theme.StealthDimText
import com.example.ui.theme.SubtleBorder
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VerifiedAmber
import com.example.vision.TargetLensGenerator
import kotlinx.coroutines.delay

@Composable
fun BlinkMainScreen(
    viewModel: BlinkViewModel,
    onBrightnessStateChanged: (isBlackScreenMode: Boolean, allowBrightnessControl: Boolean, customBrightnessLevel: Float) -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()
    val prompts by viewModel.promptsState.collectAsStateWithLifecycle()
    val history by viewModel.historyState.collectAsStateWithLifecycle()

    // Automatically resume scanning when the user returns from an external AI app (Gemini, ChatGPT, Claude, etc.)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onActivityResumed()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Sync window brightness state with Activity — NEVER reduces brightness unless allowBrightnessControl is true
    LaunchedEffect(
        uiState.isBlackScreenMode,
        settings.allowBrightnessControl,
        settings.customBrightnessLevel
    ) {
        onBrightnessStateChanged(
            uiState.isBlackScreenMode,
            settings.allowBrightnessControl,
            settings.customBrightnessLevel
        )
    }

    // Trigger subtle haptic feedback on capture and answer arrival
    LaunchedEffect(uiState.hapticTriggerCount) {
        if (uiState.hapticTriggerCount > 0) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    // Camera permission state
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.onPhotoPickerUriSelected(uri)
        }
    }

    var burnInShiftTick by remember { mutableIntStateOf(0) }
    var manualCameraSnapTrigger by remember { mutableStateOf<(() -> Boolean)?>(null) }

    // Persistent Top-to-Bottom drag offsets for the Answer Popup, MCQ Popup, Processing Pill, and Context Card
    var rootWidthPx by remember { mutableIntStateOf(0) }
    var rootHeightPx by remember { mutableIntStateOf(0) }
    var popupOffsetX by rememberSaveable { mutableFloatStateOf(0f) }
    var popupOffsetY by rememberSaveable { mutableFloatStateOf(0f) }

    val onDragPopup: (Float, Float, Int, Int) -> Unit = { dx, dy, cardW, cardH ->
        val maxOffsetX = if (rootWidthPx > cardW && cardW > 0) {
            ((rootWidthPx - cardW) / 2f).coerceAtLeast(0f)
        } else {
            480f
        }
        val minOffsetY = if (rootHeightPx > 0 && cardH > 0) {
            -((rootHeightPx - cardH).coerceAtLeast(0)).toFloat()
        } else {
            -2400f
        }
        popupOffsetX = (popupOffsetX + dx).coerceIn(-maxOffsetX, maxOffsetX)
        popupOffsetY = (popupOffsetY + dy).coerceIn(minOffsetY, 24f)
    }

    val onCyclePopupVerticalPosition: (Int) -> Unit = { cardH ->
        val minOffsetY = if (rootHeightPx > 0 && cardH > 0) {
            -((rootHeightPx - cardH).coerceAtLeast(240)).toFloat()
        } else {
            -900f
        }
        popupOffsetY = when {
            popupOffsetY > minOffsetY * 0.35f -> minOffsetY        // From Bottom -> Jump to Top
            popupOffsetY <= minOffsetY * 0.70f -> minOffsetY / 2f  // From Top -> Jump to Center
            else -> 0f                                             // From Center -> Jump to Bottom
        }
    }

    // Keep the top Android system status bar (battery, clock, network icons) hidden at all times
    val view = LocalView.current
    SideEffect {
        val window = (context as? Activity)?.window ?: return@SideEffect
        runCatching {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.statusBars())
        }
    }

    LaunchedEffect(uiState.isBlackScreenMode) {
        while (uiState.isBlackScreenMode) {
            delay(20_000L)
            burnInShiftTick = (burnInShiftTick + 1) % 4
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                rootWidthPx = size.width
                rootHeightPx = size.height
            }
            .background(AmoledBlack)
            .testTag("blink_main_root")
    ) {
        // 1. Live CameraX Smart Auto-Click on Readable Text Layer (no shape/screen detection)
        CameraScannerSurface(
            hasCameraPermission = hasCameraPermission,
            scanPhase = uiState.scanPhase,
            isScannerActiveNow = viewModel::isScannerActiveNow,
            isBlackScreenMode = uiState.isBlackScreenMode,
            settings = settings,
            telemetry = uiState.telemetry,
            activeTargetIndex = uiState.activeTargetIndex,
            existingHashes = uiState.capturedFrames.map { it.dHash },
            onTelemetryUpdated = viewModel::onTelemetryUpdated,
            onFrameCaptured = { bmp, sharpness, dHash, surfaceType, ocrText, bypassDup ->
                viewModel.onFrameCaptured(
                    bitmap = bmp,
                    sharpnessScore = sharpness,
                    dHash = dHash,
                    sourceLabel = surfaceType,
                    ocrText = ocrText,
                    bypassDuplicateCheck = bypassDup
                )
            },
            onForceCaptureTarget = viewModel::captureCurrentOpticalTargetNow,
            onRegisterManualCameraSnap = { trigger ->
                manualCameraSnapTrigger = trigger
            },
            onCameraFrameUnavailable = {
                viewModel.showTransientToast("Camera initializing — hold steady on readable text")
            },
            modifier = Modifier.fillMaxSize()
        )

        // 2. Power-Saving Black Screen Mode Stealth Layer (AMOLED 0% pixel power)
        if (uiState.isBlackScreenMode) {
            BlackScreenPowerOverlay(
                scanPhase = uiState.scanPhase,
                capturedCount = uiState.capturedFrames.size,
                burnInShiftTick = burnInShiftTick,
                onStealthCaptureTap = {
                    if (uiState.activeTargetIndex != null) {
                        viewModel.captureCurrentOpticalTargetNow()
                    } else {
                        val triggered = manualCameraSnapTrigger?.invoke() ?: false
                        if (!triggered) {
                            viewModel.showTransientToast("Camera frame not ready — check camera permission")
                        }
                    }
                },
                onExitBlackMode = viewModel::toggleBlackScreenMode,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 3. Subtle Capture Flash Border Pulse
        AnimatedVisibility(
            visible = uiState.captureFlashPulse,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(
                        width = 3.dp,
                        color = LockMint.copy(alpha = if (uiState.isBlackScreenMode) 0.35f else 0.75f)
                    )
            )
        }

        // 4. Camera Permission Prompt (only shown if permission denied AND no target card active)
        if (!hasCameraPermission && uiState.activeTargetIndex == null && !uiState.isBlackScreenMode && uiState.scanPhase == ScanPhase.SCANNING) {
            CameraPermissionFallbackCard(
                onRequestPermission = {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                },
                onUseSampleTarget = {
                    viewModel.selectOpticalTarget(0)
                },
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // 5. Unobstructed Context Confirmation & Gesture Shortcuts Layer
        // (Double-tap center = More Context, Single-tap bottom = Solve / Handoff, Draggable Top-to-Bottom)
        if (uiState.scanPhase == ScanPhase.CONTEXT_CONFIRMATION) {
            ContextConfirmationCard(
                capturedFrames = uiState.capturedFrames,
                maxImages = settings.maxImagesPerSession,
                activePrompt = uiState.activePrompt,
                allPrompts = prompts,
                codingLanguage = settings.codingLanguage,
                dispatchMode = settings.dispatchMode,
                targetAiApp = settings.targetAiApp,
                errorMessage = uiState.errorMessage,
                isBlackScreenMode = uiState.isBlackScreenMode,
                popupOffsetX = popupOffsetX,
                popupOffsetY = popupOffsetY,
                onDragPopup = onDragPopup,
                onCyclePopupVerticalPosition = onCyclePopupVerticalPosition,
                onSelectPrompt = viewModel::selectPromptPreset,
                onSelectCodingLanguage = viewModel::updateCodingLanguage,
                onRemoveFrame = viewModel::removeCapturedFrame,
                onYesMoreContext = viewModel::onRequestMoreContext,
                onDoneSolve = viewModel::onFinishContextAndSolve,
                onDispatchHandoffNow = { viewModel.dispatchToExternalAiApp(settings.targetAiApp) },
                onOpenHandoffSheet = { viewModel.openSheet(OverlaySheet.HANDOFF_DISPATCHER) },
                onCancelSession = viewModel::startNewScanSession,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 6. Contextual Workflow Area (Scanning Bar -> Draggable Processing Pill -> Draggable Answer / MCQ Popup)
        if (uiState.scanPhase != ScanPhase.CONTEXT_CONFIRMATION) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.navigationBars),
                contentAlignment = Alignment.BottomCenter
            ) {
                when (uiState.scanPhase) {
                    ScanPhase.SCANNING -> {
                        if (!uiState.isBlackScreenMode) {
                            MinimalScanningBottomBar(
                                autoCaptureEnabled = settings.autoCaptureEnabled,
                                activePrompt = uiState.activePrompt,
                                allPrompts = prompts,
                                codingLanguage = settings.codingLanguage,
                                targetAiAppName = settings.targetAiApp.displayName,
                                activeTargetIndex = uiState.activeTargetIndex,
                                onToggleAutoCapture = viewModel::toggleAutoCaptureMode,
                                onSelectPrompt = viewModel::selectPromptPreset,
                                onSelectCodingLanguage = viewModel::updateCodingLanguage,
                                onOpenHandoffDispatcher = { viewModel.openSheet(OverlaySheet.HANDOFF_DISPATCHER) },
                                onSelectTarget = viewModel::selectOpticalTarget,
                                onLaunchPhotoPicker = {
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                }
                            )
                        }
                    }

                    ScanPhase.PROCESSING_FAST -> {
                        ProcessingStatusPill(
                            frameCount = uiState.capturedFrames.size,
                            promptTitle = uiState.activePrompt.title,
                            isBlackScreenMode = uiState.isBlackScreenMode,
                            popupOffsetX = popupOffsetX,
                            popupOffsetY = popupOffsetY,
                            onDragPopup = onDragPopup,
                            onHandoffInstead = {
                                viewModel.cancelActiveSession()
                                viewModel.dispatchToExternalAiApp(settings.targetAiApp)
                            },
                            onCancel = viewModel::cancelActiveSession
                        )
                    }

                    ScanPhase.ANSWER_DISPLAY -> {
                        val answer = uiState.activeAnswer
                        if (answer != null) {
                            MinimalAnswerOverlay(
                                answer = answer,
                                isExpanded = uiState.isAnswerExpanded,
                                isBlackScreenMode = uiState.isBlackScreenMode,
                                popupOffsetX = popupOffsetX,
                                popupOffsetY = popupOffsetY,
                                onDragPopup = onDragPopup,
                                onCyclePopupVerticalPosition = onCyclePopupVerticalPosition,
                                onToggleExpand = viewModel::toggleAnswerExpanded,
                                onHandoffToAiApp = { viewModel.dispatchToExternalAiApp(settings.targetAiApp) },
                                onNewScan = viewModel::startNewScanSession,
                                onCopiedToast = viewModel::showTransientToast
                            )
                        }
                    }

                    ScanPhase.CONTEXT_CONFIRMATION -> Unit
                }
            }
        }

        // 7. ALWAYS-ACCESSIBLE Top HUD Bar (Tapping the mode badge switches between MCQ Answer & Solve Accurately)
        if (!uiState.isBlackScreenMode) {
            MinimalTopHudBar(
                activePromptTitle = uiState.activePrompt.shortBadge,
                codingLanguage = settings.codingLanguage,
                autoCaptureEnabled = settings.autoCaptureEnabled,
                capturedCount = uiState.capturedFrames.size,
                maxImages = settings.maxImagesPerSession,
                onToggleMode = viewModel::togglePreconfiguredMode,
                onCycleCodingLanguage = viewModel::cycleCodingLanguage,
                onToggleAutoCapture = viewModel::toggleAutoCaptureMode,
                onOpenHandoffSheet = { viewModel.openSheet(OverlaySheet.HANDOFF_DISPATCHER) },
                onToggleBlackMode = viewModel::toggleBlackScreenMode,
                onOpenHistory = { viewModel.openSheet(OverlaySheet.HISTORY) },
                onOpenSettings = { viewModel.openSheet(OverlaySheet.SETTINGS) },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
            )
        }

        // 8. Transient Toast Notification Pill
        AnimatedVisibility(
            visible = uiState.statusToastMessage != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(top = 62.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = Color.Black.copy(alpha = 0.72f),
                modifier = Modifier.border(0.8.dp, OpticalCyan.copy(alpha = 0.45f), RoundedCornerShape(50))
            ) {
                Text(
                    text = uiState.statusToastMessage.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    color = TextPrimary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }

        // 9. Secondary Configuration Sheets (AI Handoff Dispatcher, Settings, History)
        when (uiState.activeSheet) {
            OverlaySheet.HANDOFF_DISPATCHER -> {
                AiHandoffDispatcherSheet(
                    dispatchMode = settings.dispatchMode,
                    selectedApp = settings.targetAiApp,
                    capturedCount = uiState.capturedFrames.size,
                    synthesizedPromptPreview = uiState.synthesizedPromptPreview,
                    detectedDomain = uiState.detectedDomainSummary,
                    onSelectDispatchMode = viewModel::updateDispatchMode,
                    onSelectTargetApp = viewModel::updateTargetAiApp,
                    onLaunchHandoffNow = { app ->
                        viewModel.updateTargetAiApp(app)
                        viewModel.dispatchToExternalAiApp(app)
                    },
                    onClose = viewModel::closeSheet
                )
            }

            OverlaySheet.SETTINGS -> {
                BlinkSettingsSheet(
                    settings = settings,
                    geminiConnectionStatus = uiState.geminiConnectionStatus,
                    groqConnectionStatus = uiState.groqConnectionStatus,
                    geminiQuotaStatuses = uiState.geminiQuotaStatuses,
                    onUpdateDispatchMode = viewModel::updateDispatchMode,
                    onUpdateTargetAiApp = viewModel::updateTargetAiApp,
                    onUpdateAiProvider = viewModel::updateAiProvider,
                    onUpdateGeminiRoutingMode = viewModel::updateGeminiRoutingMode,
                    onUpdateGeminiModel = viewModel::updateGeminiModel,
                    onResetGeminiQuota = viewModel::resetGeminiQuotaTracker,
                    onUpdateGroqModel = viewModel::updateGroqModel,
                    onSaveGeminiApiKey = viewModel::saveGeminiApiKey,
                    onSaveGroqApiKey = viewModel::saveGroqApiKey,
                    onTestProviderConnection = viewModel::testProviderConnection,
                    onUpdateCodingLanguage = viewModel::updateCodingLanguage,
                    onUpdateAnswerStyle = viewModel::updateAnswerStyle,
                    onUpdateSensitivity = viewModel::updateCaptureSensitivity,
                    onUpdateResolution = viewModel::updateImageResolution,
                    onUpdateMaxImages = viewModel::updateMaxImages,
                    onUpdateAutoCapture = viewModel::updateAutoCapture,
                    onUpdateStartBlackMode = viewModel::updateStartInBlackMode,
                    onUpdateAllowBrightnessControl = viewModel::updateAllowBrightnessControl,
                    onUpdateCustomBrightnessLevel = viewModel::updateCustomBrightnessLevel,
                    onUpdateSaveHistory = viewModel::updateSaveHistory,
                    onClearAllData = viewModel::clearAllSessionData,
                    onClose = viewModel::closeSheet
                )
            }

            OverlaySheet.HISTORY -> {
                SessionHistorySheet(
                    history = history,
                    onSelectSession = viewModel::loadHistorySessionToView,
                    onDeleteSession = viewModel::deleteHistoryEntry,
                    onClearAll = viewModel::clearAllSessionData,
                    onClose = viewModel::closeSheet
                )
            }

            OverlaySheet.NONE -> Unit
        }
    }
}

@Composable
private fun MinimalTopHudBar(
    activePromptTitle: String,
    codingLanguage: CodingLanguage,
    autoCaptureEnabled: Boolean,
    capturedCount: Int,
    maxImages: Int,
    onToggleMode: () -> Unit,
    onCycleCodingLanguage: () -> Unit,
    onToggleAutoCapture: () -> Unit,
    onOpenHandoffSheet: () -> Unit,
    onToggleBlackMode: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: Blink AI status + 1-tap toggle between MCQ / Solve + 1-tap Default Coding Language (C++ / Python / SQL)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.50f))
                    .border(0.8.dp, SubtleBorder.copy(alpha = 0.7f), RoundedCornerShape(50))
                    .clickable(onClick = onToggleMode)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("active_prompt_selector_pill"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (autoCaptureEnabled) LockMint else VerifiedAmber)
                )
                Text(
                    text = "Blink",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "• $activePromptTitle",
                    style = MaterialTheme.typography.labelMedium,
                    color = OpticalCyan,
                    fontWeight = FontWeight.Bold
                )
                if (capturedCount > 0) {
                    Text(
                        text = "($capturedCount/$maxImages)",
                        style = MaterialTheme.typography.labelSmall,
                        color = LockMint
                    )
                }
            }

            // Quick 1-Tap Default Coding Language Pill (cycles C++ -> Python -> SQL -> Java -> JS)
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.50f))
                    .border(0.8.dp, LockMint.copy(alpha = 0.65f), RoundedCornerShape(50))
                    .clickable(onClick = onCycleCodingLanguage)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .testTag("top_coding_language_pill"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = codingLanguage.shortBadge,
                    style = MaterialTheme.typography.labelMedium,
                    color = LockMint,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Right: Always-reachable utility icons (Auto-Click Pause/Resume, AI Handoff, Black Mode, History, Settings)
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.50f))
                .border(0.8.dp, SubtleBorder.copy(alpha = 0.7f), RoundedCornerShape(50))
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onToggleAutoCapture,
                modifier = Modifier
                    .size(44.dp)
                    .testTag("top_auto_capture_toggle")
            ) {
                Icon(
                    imageVector = if (autoCaptureEnabled) {
                        Icons.Default.CenterFocusStrong
                    } else {
                        Icons.Default.PauseCircleOutline
                    },
                    contentDescription = "Toggle Auto-Click",
                    tint = if (autoCaptureEnabled) LockMint else VerifiedAmber,
                    modifier = Modifier.size(19.dp)
                )
            }

            IconButton(
                onClick = onOpenHandoffSheet,
                modifier = Modifier
                    .size(44.dp)
                    .testTag("top_handoff_button")
            ) {
                Icon(
                    imageVector = Icons.Default.OpenInNew,
                    contentDescription = "AI App Handoff Dispatcher",
                    tint = LockMint,
                    modifier = Modifier.size(18.dp)
                )
            }

            IconButton(
                onClick = onToggleBlackMode,
                modifier = Modifier
                    .size(44.dp)
                    .testTag("black_screen_mode_button")
            ) {
                Icon(
                    imageVector = Icons.Default.DarkMode,
                    contentDescription = stringResource(R.string.black_mode_label),
                    tint = OpticalCyan,
                    modifier = Modifier.size(19.dp)
                )
            }

            IconButton(
                onClick = onOpenHistory,
                modifier = Modifier
                    .size(44.dp)
                    .testTag("open_history_button")
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = stringResource(R.string.history_title),
                    tint = TextSecondary,
                    modifier = Modifier.size(19.dp)
                )
            }

            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .size(44.dp)
                    .testTag("open_settings_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = stringResource(R.string.settings_title),
                    tint = TextPrimary,
                    modifier = Modifier.size(19.dp)
                )
            }
        }
    }
}

/**
 * Pure AMOLED Black Screen Saver / Stealth Overlay:
 * - Zero visible text on screen so the display looks genuinely off/black.
 * - Single-tap anywhere snaps a picture; double-tap anywhere wakes the display.
 * - Includes a tiny, nearly-invisible 5dp status dot at the top that also wakes the display when tapped.
 */
@Composable
private fun BlackScreenPowerOverlay(
    scanPhase: ScanPhase,
    capturedCount: Int,
    burnInShiftTick: Int,
    onStealthCaptureTap: () -> Unit,
    onExitBlackMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shiftX = when (burnInShiftTick % 4) {
        0 -> 0
        1 -> 6
        2 -> -6
        else -> 3
    }
    val shiftY = when (burnInShiftTick % 4) {
        0 -> 0
        1 -> 4
        2 -> -4
        else -> -3
    }

    Box(
        modifier = modifier
            .background(AmoledBlack)
            .pointerInput(scanPhase) {
                detectTapGestures(
                    onDoubleTap = { onExitBlackMode() },
                    onTap = {
                        if (scanPhase == ScanPhase.SCANNING) {
                            onStealthCaptureTap()
                        }
                    }
                )
            }
            .testTag("black_screen_power_overlay")
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(top = 8.dp)
                .offset { IntOffset(shiftX.dp.roundToPx(), shiftY.dp.roundToPx()) }
                .size(48.dp)
                .clip(CircleShape)
                .clickable(onClick = onExitBlackMode)
                .testTag("exit_black_mode_indicator"),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(
                        if (capturedCount > 0) LockMint.copy(alpha = 0.28f)
                        else StealthDimText.copy(alpha = 0.22f)
                    )
            )
        }
    }
}

/**
 * Translucent, compact bottom bar during SCANNING mode:
 * - Direct 1-tap chips for the 2 preconfigured modes: "MCQ Answer" and "Solve Accurately"
 * - Quick 1-tap Default Coding Language chips: "C++", "Python", "SQL"
 * - Smart Auto-Click toggle
 * - AI App Handoff pill
 * - Photo Picker Import & Target Lenses
 */
@Composable
private fun MinimalScanningBottomBar(
    autoCaptureEnabled: Boolean,
    activePrompt: PromptPresetEntity,
    allPrompts: List<PromptPresetEntity>,
    codingLanguage: CodingLanguage,
    targetAiAppName: String,
    activeTargetIndex: Int?,
    onToggleAutoCapture: () -> Unit,
    onSelectPrompt: (PromptPresetEntity) -> Unit,
    onSelectCodingLanguage: (CodingLanguage) -> Unit,
    onOpenHandoffDispatcher: () -> Unit,
    onSelectTarget: (Int?) -> Unit,
    onLaunchPhotoPicker: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. Two Preconfigured Modes (MCQ Answer / Solve Accurately)
        allPrompts.forEach { preset ->
            val isSelected = preset.id == activePrompt.id
            Row(
                modifier = Modifier
                    .height(42.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (isSelected) OpticalCyan.copy(alpha = 0.24f)
                        else Color.Black.copy(alpha = 0.52f)
                    )
                    .border(
                        width = 1.dp,
                        color = if (isSelected) OpticalCyan else SubtleBorder,
                        shape = RoundedCornerShape(50)
                    )
                    .clickable { onSelectPrompt(preset) }
                    .padding(horizontal = 14.dp)
                    .testTag("bottom_mode_chip_${preset.id}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = preset.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) OpticalCyan else TextPrimary,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                )
            }
        }

        // 1b. Quick 1-Tap Default Coding Language Selector (C++, Python, SQL)
        listOf(CodingLanguage.CPP, CodingLanguage.PYTHON, CodingLanguage.SQL).forEach { lang ->
            val isLangSelected = codingLanguage == lang
            Row(
                modifier = Modifier
                    .height(42.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (isLangSelected) LockMint.copy(alpha = 0.20f)
                        else Color.Black.copy(alpha = 0.52f)
                    )
                    .border(
                        width = 1.dp,
                        color = if (isLangSelected) LockMint else SubtleBorder,
                        shape = RoundedCornerShape(50)
                    )
                    .clickable { onSelectCodingLanguage(lang) }
                    .padding(horizontal = 12.dp)
                    .testTag("bottom_lang_chip_${lang.name.lowercase()}"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = lang.shortBadge,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isLangSelected) LockMint else TextSecondary,
                    fontWeight = if (isLangSelected) FontWeight.Bold else FontWeight.Medium
                )
            }
        }

        // 2. Smart Auto-Click on Readable Text Toggle Pill
        Row(
            modifier = Modifier
                .height(42.dp)
                .clip(RoundedCornerShape(50))
                .background(
                    if (autoCaptureEnabled) LockMint.copy(alpha = 0.18f)
                    else Color.Black.copy(alpha = 0.52f)
                )
                .border(
                    width = 1.dp,
                    color = if (autoCaptureEnabled) LockMint else SubtleBorder,
                    shape = RoundedCornerShape(50)
                )
                .clickable(onClick = onToggleAutoCapture)
                .padding(horizontal = 12.dp)
                .testTag("auto_capture_toggle_chip"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = if (autoCaptureEnabled) {
                    Icons.Default.CenterFocusStrong
                } else {
                    Icons.Default.PauseCircleOutline
                },
                contentDescription = "Toggle Smart Auto-Click",
                tint = if (autoCaptureEnabled) LockMint else VerifiedAmber,
                modifier = Modifier.size(15.dp)
            )
            Text(
                text = if (autoCaptureEnabled) "Auto-Click: ON" else "Manual Tap",
                style = MaterialTheme.typography.labelMedium,
                color = if (autoCaptureEnabled) LockMint else VerifiedAmber
            )
        }

        // 3. AI App Handoff Dispatcher Pill
        Row(
            modifier = Modifier
                .height(42.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.52f))
                .border(1.dp, LockMint.copy(alpha = 0.65f), RoundedCornerShape(50))
                .clickable(onClick = onOpenHandoffDispatcher)
                .padding(horizontal = 12.dp)
                .testTag("bottom_handoff_dispatcher_chip"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.OpenInNew,
                contentDescription = "AI App Handoff",
                tint = LockMint,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = "Handoff: ${targetAiAppName.take(12)}",
                style = MaterialTheme.typography.labelMedium,
                color = LockMint,
                fontWeight = FontWeight.SemiBold
            )
        }

        // 4. Photo Picker Import pill
        Row(
            modifier = Modifier
                .height(42.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.52f))
                .border(1.dp, SubtleBorder, RoundedCornerShape(50))
                .clickable(onClick = onLaunchPhotoPicker)
                .padding(horizontal = 12.dp)
                .testTag("photo_picker_import_chip"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.PhotoLibrary,
                contentDescription = "Import image",
                tint = LockMint,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = "Import",
                style = MaterialTheme.typography.labelMedium,
                color = TextPrimary
            )
        }

        // 5. Live Camera Lens pill (when viewing a sample target card)
        if (activeTargetIndex != null) {
            Row(
                modifier = Modifier
                    .height(42.dp)
                    .clip(RoundedCornerShape(50))
                    .background(OpticalCyan.copy(alpha = 0.22f))
                    .border(1.dp, OpticalCyan, RoundedCornerShape(50))
                    .clickable { onSelectTarget(null) }
                    .padding(horizontal = 12.dp)
                    .testTag("lens_live_camera_chip"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = null,
                    tint = OpticalCyan,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "Live Camera",
                    style = MaterialTheme.typography.labelMedium,
                    color = OpticalCyan
                )
            }
        }

        // 6. Optical Problem Target Projections for instant testing
        TargetLensGenerator.targets.forEachIndexed { idx, target ->
            val selected = activeTargetIndex == idx
            Row(
                modifier = Modifier
                    .height(42.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (selected) LockMint.copy(alpha = 0.22f)
                        else Color.Black.copy(alpha = 0.52f)
                    )
                    .border(
                        width = 1.dp,
                        color = if (selected) LockMint else SubtleBorder,
                        shape = RoundedCornerShape(50)
                    )
                    .clickable { onSelectTarget(idx) }
                    .padding(horizontal = 12.dp)
                    .testTag("target_lens_chip_$idx"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = if (selected) LockMint else TextSecondary,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = target.shortTitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) LockMint else TextSecondary
                )
            }
        }
    }
}

@Composable
private fun CameraPermissionFallbackCard(
    onRequestPermission: () -> Unit,
    onUseSampleTarget: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .padding(24.dp)
            .border(1.dp, OpticalCyan.copy(alpha = 0.45f), RoundedCornerShape(24.dp)),
        shape = RoundedCornerShape(24.dp),
        color = ObsidianCard.copy(alpha = 0.92f)
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.permission_camera_title),
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.permission_camera_rationale),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onRequestPermission,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = OpticalCyan,
                        contentColor = AmoledBlack
                    ),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("grant_camera_permission_button")
                ) {
                    Text(
                        text = stringResource(R.string.permission_grant_button),
                        fontWeight = FontWeight.Bold
                    )
                }
                Button(
                    onClick = onUseSampleTarget,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ObsidianElevated,
                        contentColor = LockMint
                    ),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("use_optical_target_button")
                ) {
                    Text("Scan Target Card")
                }
            }
        }
    }
}
