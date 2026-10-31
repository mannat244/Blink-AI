package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.BlinkAnswer
import com.example.data.CapturedFrame
import com.example.data.CodingLanguage
import com.example.data.DispatchMode
import com.example.data.PromptPresetEntity
import com.example.data.TargetAiApp
import com.example.data.VerificationBadge
import com.example.ui.theme.AlertCoral
import com.example.ui.theme.AlertCoralContainer
import com.example.ui.theme.AmoledBlack
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LockMint
import com.example.ui.theme.LockMintContainer
import com.example.ui.theme.ObsidianCard
import com.example.ui.theme.ObsidianElevated
import com.example.ui.theme.OpticalCyan
import com.example.ui.theme.SubtleBorder
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VerifiedAmber
import com.example.ui.theme.VerifiedAmberContainer

/**
 * Step 3 UI: Unobstructed Translucent HUD with Invisible Gesture Shortcuts, Two Preconfigured Modes,
 * Quick 1-Tap Default Coding Language Selector (C++ / Python / SQL / etc.), and Top-to-Bottom Drag Repositioning:
 * - Center camera view stays completely clear so the user can aim at the next text.
 * - Double-tapping anywhere in the unobstructed camera view triggers `onYesMoreContext()`.
 * - The floating control card (with MCQ Answer / Solve Accurately) can be dragged anywhere from top to bottom.
 */
@Composable
fun ContextConfirmationCard(
    capturedFrames: List<CapturedFrame>,
    maxImages: Int,
    activePrompt: PromptPresetEntity,
    allPrompts: List<PromptPresetEntity>,
    codingLanguage: CodingLanguage = CodingLanguage.CPP,
    dispatchMode: DispatchMode,
    targetAiApp: TargetAiApp,
    errorMessage: String?,
    isBlackScreenMode: Boolean,
    popupOffsetX: Float = 0f,
    popupOffsetY: Float = 0f,
    onDragPopup: (dx: Float, dy: Float, cardWidthPx: Int, cardHeightPx: Int) -> Unit = { _, _, _, _ -> },
    onCyclePopupVerticalPosition: (cardHeightPx: Int) -> Unit = {},
    onSelectPrompt: (PromptPresetEntity) -> Unit,
    onSelectCodingLanguage: (CodingLanguage) -> Unit = {},
    onRemoveFrame: (String) -> Unit,
    onYesMoreContext: () -> Unit,
    onDoneSolve: () -> Unit,
    onDispatchHandoffNow: () -> Unit,
    onOpenHandoffSheet: () -> Unit,
    onCancelSession: () -> Unit,
    modifier: Modifier = Modifier
) {
    val canAddMoreImages = capturedFrames.size < maxImages
    val translucentBg = if (isBlackScreenMode) {
        AmoledBlack.copy(alpha = 0.88f)
    } else {
        Color.Black.copy(alpha = 0.50f)
    }

    var cardWidthPx by remember { mutableIntStateOf(0) }
    var cardHeightPx by remember { mutableIntStateOf(0) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .testTag("context_confirmation_card")
    ) {
        // 1. UNOBSTRUCTED, COMPLETELY CLEAR BACKGROUND CAMERA SPACE
        // Double-tap anywhere here = "Need More Context" (Add another image).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(canAddMoreImages) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (canAddMoreImages) {
                                onYesMoreContext()
                            }
                        }
                    )
                }
                .testTag("context_yes_button")
        )

        // 2. DRAGGABLE TRANSLUCENT CONTROL CARD (Can be dragged & repositioned from Top to Bottom)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset {
                    IntOffset(
                        popupOffsetX.toInt(),
                        popupOffsetY.toInt()
                    )
                }
                .widthIn(max = 580.dp)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .onSizeChanged { size ->
                    cardWidthPx = size.width
                    cardHeightPx = size.height
                }
                .clip(RoundedCornerShape(22.dp))
                .background(translucentBg)
                .border(
                    width = 1.dp,
                    color = SubtleBorder.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(22.dp)
                )
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onDragPopup(dragAmount.x, dragAmount.y, cardWidthPx, cardHeightPx)
                    }
                }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Top Drag Handle Pill (Drag anywhere top-to-bottom or tap to cycle Top / Mid / Bottom)
            PopupDragHandleRow(
                label = "Drag to move • Tap to jump Top/Bottom",
                onCyclePosition = { onCyclePopupVerticalPosition(cardHeightPx) },
                tag = "context_card_drag_handle"
            )

            // Row 1: Compact frame thumbnails + Handoff App Picker + Cancel
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    itemsIndexed(capturedFrames, key = { _, item -> item.id }) { idx, frame ->
                        Box(
                            modifier = Modifier
                                .size(width = 50.dp, height = 38.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, OpticalCyan.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        ) {
                            if (!isBlackScreenMode) {
                                Image(
                                    bitmap = frame.bitmap.asImageBitmap(),
                                    contentDescription = "Frame ${idx + 1}",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            Text(
                                text = "#${idx + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = LockMint,
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .background(Color.Black.copy(alpha = 0.65f))
                                    .padding(horizontal = 3.dp)
                            )
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(16.dp)
                                    .background(Color.Black.copy(alpha = 0.7f), CircleShape)
                                    .clickable { onRemoveFrame(frame.id) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Remove frame ${idx + 1}",
                                    tint = TextPrimary,
                                    modifier = Modifier.size(10.dp)
                                )
                            }
                        }
                    }

                    item {
                        Text(
                            text = "${capturedFrames.size}/$maxImages",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }

                // Right actions: AI Handoff App Picker + Cancel
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(LockMint.copy(alpha = 0.18f))
                            .border(1.dp, LockMint.copy(alpha = 0.55f), RoundedCornerShape(50))
                            .clickable(onClick = onOpenHandoffSheet)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .testTag("open_handoff_dispatcher_button")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.OpenInNew,
                                contentDescription = "AI Handoff",
                                tint = LockMint,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = targetAiApp.displayName.take(10),
                                style = MaterialTheme.typography.labelSmall,
                                color = LockMint
                            )
                        }
                    }

                    IconButton(
                        onClick = onCancelSession,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("cancel_context_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.action_cancel),
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Row 2: Two Preconfigured Modes Only (1. MCQ Answer, 2. Solve Accurately) + Quick Coding Language Selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                allPrompts.forEach { preset ->
                    val isSelected = preset.id == activePrompt.id
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isSelected) OpticalCyan.copy(alpha = 0.24f)
                                else Color.Black.copy(alpha = 0.36f)
                            )
                            .border(
                                width = 1.dp,
                                color = if (isSelected) OpticalCyan else SubtleBorder.copy(alpha = 0.55f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { onSelectPrompt(preset) }
                            .padding(horizontal = 8.dp)
                            .testTag("preset_chip_${preset.id}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = preset.title,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isSelected) OpticalCyan else TextSecondary,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                }

                // Quick 1-Tap Coding Language chips (C++, Python, SQL)
                listOf(CodingLanguage.CPP, CodingLanguage.PYTHON, CodingLanguage.SQL).forEach { lang ->
                    val isLangSelected = codingLanguage == lang
                    Box(
                        modifier = Modifier
                            .height(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isLangSelected) LockMint.copy(alpha = 0.22f)
                                else Color.Black.copy(alpha = 0.36f)
                            )
                            .border(
                                width = 1.dp,
                                color = if (isLangSelected) LockMint else SubtleBorder.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickable { onSelectCodingLanguage(lang) }
                            .padding(horizontal = 10.dp)
                            .testTag("context_lang_chip_${lang.name.lowercase()}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = lang.shortBadge,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isLangSelected) LockMint else TextSecondary,
                            fontWeight = if (isLangSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }

            // Optional compact error banner with 1-tap Handoff fallback
            if (!errorMessage.isNullOrBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(AlertCoralContainer.copy(alpha = 0.75f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.labelSmall,
                        color = AlertCoral,
                        maxLines = 2,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onDispatchHandoffNow,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LockMint,
                            contentColor = AmoledBlack
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text("Handoff App", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Row 3: Quick-Send Bar (Tapping solves/dispatches immediately)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(LockMint.copy(alpha = 0.88f))
                    .clickable { onDoneSolve() }
                    .padding(horizontal = 14.dp)
                    .testTag("context_done_button"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (dispatchMode == DispatchMode.AI_APP_HANDOFF) {
                            Icons.Default.OpenInNew
                        } else {
                            Icons.AutoMirrored.Filled.Send
                        },
                        contentDescription = null,
                        tint = AmoledBlack,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = when (dispatchMode) {
                            DispatchMode.AI_APP_HANDOFF -> "Send to ${targetAiApp.displayName}"
                            DispatchMode.SMART_DISPATCH -> "${activePrompt.title} • Send"
                            DispatchMode.CLOUD_API_ONLY -> activePrompt.title
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = AmoledBlack,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (canAddMoreImages) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(AmoledBlack.copy(alpha = 0.16f))
                                .clickable { onYesMoreContext() }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AddAPhoto,
                                    contentDescription = "More context",
                                    tint = AmoledBlack,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = "+More (2x Tap Center)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AmoledBlack,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Step 4 UI: Draggable compact AI processing indicator with session cancellation and 1-tap Handoff switch.
 */
@Composable
fun ProcessingStatusPill(
    frameCount: Int,
    promptTitle: String,
    isBlackScreenMode: Boolean,
    popupOffsetX: Float = 0f,
    popupOffsetY: Float = 0f,
    onDragPopup: (dx: Float, dy: Float, cardWidthPx: Int, cardHeightPx: Int) -> Unit = { _, _, _, _ -> },
    onHandoffInstead: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var pillWidthPx by remember { mutableIntStateOf(0) }
    var pillHeightPx by remember { mutableIntStateOf(0) }

    Surface(
        modifier = modifier
            .offset {
                IntOffset(
                    popupOffsetX.toInt(),
                    popupOffsetY.toInt()
                )
            }
            .widthIn(max = 500.dp)
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .onSizeChanged { size ->
                pillWidthPx = size.width
                pillHeightPx = size.height
            }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDragPopup(dragAmount.x, dragAmount.y, pillWidthPx, pillHeightPx)
                }
            }
            .border(
                1.dp,
                OpticalCyan.copy(alpha = if (isBlackScreenMode) 0.35f else 0.6f),
                RoundedCornerShape(20.dp)
            )
            .testTag("processing_status_pill"),
        shape = RoundedCornerShape(20.dp),
        color = if (isBlackScreenMode) AmoledBlack else Color.Black.copy(alpha = 0.68f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = OpticalCyan,
                    strokeWidth = 2.2.dp
                )
                Column {
                    Text(
                        text = "$promptTitle...",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "$frameCount image(s) • Drag to move or tap Handoff",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = onHandoffInstead,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(40.dp)
                ) {
                    Text(
                        text = "Handoff",
                        style = MaterialTheme.typography.labelMedium,
                        color = LockMint
                    )
                }

                IconButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("cancel_processing_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.action_cancel),
                        tint = AlertCoral
                    )
                }
            }
        }
    }
}

/**
 * Step 5 UI: Minimal Answer Delivery with prominent MCQ Answer / Solution display.
 * Supports free dragging & repositioning anywhere from top to bottom of the screen
 * (both for the Code/Solution popup and the MCQ popup), plus a 1-tap Top/Mid/Bottom position switch.
 */
@Composable
fun MinimalAnswerOverlay(
    answer: BlinkAnswer,
    isExpanded: Boolean,
    isBlackScreenMode: Boolean,
    popupOffsetX: Float = 0f,
    popupOffsetY: Float = 0f,
    onDragPopup: (dx: Float, dy: Float, cardWidthPx: Int, cardHeightPx: Int) -> Unit = { _, _, _, _ -> },
    onCyclePopupVerticalPosition: (cardHeightPx: Int) -> Unit = {},
    onToggleExpand: () -> Unit,
    onHandoffToAiApp: () -> Unit,
    onNewScan: () -> Unit,
    onCopiedToast: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val cardBg = if (isBlackScreenMode) AmoledBlack else ObsidianCard.copy(alpha = 0.90f)
    val borderColor = when (answer.verificationBadge) {
        VerificationBadge.WEB_GROUNDED -> LockMint.copy(alpha = 0.65f)
        VerificationBadge.MODEL_VERIFIED -> OpticalCyan.copy(alpha = 0.6f)
        VerificationBadge.VERIFYING -> VerifiedAmber.copy(alpha = 0.55f)
        VerificationBadge.FAST_INITIAL -> SubtleBorder
    }

    var cardWidthPx by remember { mutableIntStateOf(0) }
    var cardHeightPx by remember { mutableIntStateOf(0) }

    Surface(
        modifier = modifier
            .offset {
                IntOffset(
                    popupOffsetX.toInt(),
                    popupOffsetY.toInt()
                )
            }
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .onSizeChanged { size ->
                cardWidthPx = size.width
                cardHeightPx = size.height
            }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDragPopup(dragAmount.x, dragAmount.y, cardWidthPx, cardHeightPx)
                }
            }
            .border(1.2.dp, borderColor, RoundedCornerShape(24.dp))
            .testTag("minimal_answer_overlay"),
        shape = RoundedCornerShape(24.dp),
        color = cardBg,
        tonalElevation = 10.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Drag Handle Pill: Drag anywhere from top to bottom or tap to jump Top / Mid / Bottom
            PopupDragHandleRow(
                label = "Drag popup or MCQ • Tap to jump Top/Bottom",
                onCyclePosition = { onCyclePopupVerticalPosition(cardHeightPx) },
                tag = "minimal_answer_drag_handle"
            )

            // Top metadata bar: Category + Verification Pipeline Badge + Latency
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(ObsidianElevated)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = answer.detectedCategory,
                            style = MaterialTheme.typography.labelSmall,
                            color = OpticalCyan
                        )
                    }

                    VerificationStatusBadge(badge = answer.verificationBadge)
                }

                Text(
                    text = "${answer.fastLatencyMs}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted
                )
            }

            // Prominent MCQ Answer / Final Answer Box (Also directly draggable top-to-bottom)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (answer.isShortAnswer) LockMintContainer.copy(alpha = 0.55f)
                        else ObsidianElevated
                    )
                    .border(
                        width = 1.dp,
                        color = if (answer.isShortAnswer) LockMint.copy(alpha = 0.7f) else OpticalCyan.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(14.dp)
                    )
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            onDragPopup(dragAmount.x, dragAmount.y, cardWidthPx, cardHeightPx)
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .testTag("mcq_answer_banner")
            ) {
                Text(
                    text = answer.shortSummary,
                    style = if (answer.isShortAnswer) {
                        MaterialTheme.typography.headlineSmall
                    } else {
                        MaterialTheme.typography.titleMedium
                    },
                    color = if (answer.isShortAnswer) LockMint else TextPrimary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("answer_short_summary")
                )
            }

            if (!answer.isShortAnswer && !isExpanded && answer.detailedAnswer != answer.shortSummary) {
                Text(
                    text = answer.detailedAnswer,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Expandable Detailed Section
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (answer.extractedQuestion.isNotBlank()) {
                        Text(
                            text = "Question: ${answer.extractedQuestion}",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary
                        )
                    }

                    FormattedAnswerBody(detailedText = answer.detailedAnswer)

                    if (answer.verificationNote.isNotBlank()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(LockMintContainer.copy(alpha = 0.45f))
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Verified,
                                contentDescription = null,
                                tint = LockMint,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = answer.verificationNote,
                                style = MaterialTheme.typography.bodySmall,
                                color = LockMint
                            )
                        }
                    }
                }
            }

            // Action bar: Expand/Collapse, Copy, Handoff to AI App, New Scan
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = onToggleExpand,
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        modifier = Modifier
                            .height(42.dp)
                            .testTag("toggle_expand_answer_button")
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = OpticalCyan,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isExpanded) "Less" else "Solution",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextPrimary,
                            maxLines = 1
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            copyToClipboard(
                                context = context,
                                text = if (isExpanded) answer.detailedAnswer else answer.shortSummary
                            )
                            onCopiedToast("Answer copied")
                        },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        modifier = Modifier
                            .height(42.dp)
                            .testTag("copy_answer_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.action_copy),
                            tint = TextSecondary,
                            modifier = Modifier.size(15.dp)
                        )
                    }

                    OutlinedButton(
                        onClick = onHandoffToAiApp,
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        modifier = Modifier
                            .height(42.dp)
                            .testTag("answer_handoff_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.OpenInNew,
                            contentDescription = "Handoff to AI App",
                            tint = LockMint,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Handoff",
                            style = MaterialTheme.typography.labelMedium,
                            color = LockMint,
                            maxLines = 1
                        )
                    }
                }

                Button(
                    onClick = onNewScan,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = OpticalCyan,
                        contentColor = AmoledBlack
                    ),
                    modifier = Modifier
                        .height(42.dp)
                        .testTag("new_scan_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Scan",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * Compact pill drag handle row at the top of popups (`MinimalAnswerOverlay` and `ContextConfirmationCard`).
 * Dragging moves the popup anywhere from top to bottom; tapping jumps between Top, Center, and Bottom.
 */
@Composable
private fun PopupDragHandleRow(
    label: String,
    onCyclePosition: () -> Unit,
    tag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onCyclePosition)
            .padding(vertical = 2.dp)
            .testTag(tag),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(42.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(50))
                .background(OpticalCyan.copy(alpha = 0.55f))
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted
        )
    }
}

@Composable
private fun VerificationStatusBadge(badge: VerificationBadge) {
    val (bgColor, textColor, icon) = when (badge) {
        VerificationBadge.WEB_GROUNDED -> Triple(LockMintContainer, LockMint, Icons.Default.Verified)
        VerificationBadge.MODEL_VERIFIED -> Triple(
            OpticalCyan.copy(alpha = 0.16f),
            OpticalCyan,
            Icons.Default.Verified
        )
        VerificationBadge.VERIFYING -> Triple(
            VerifiedAmberContainer,
            VerifiedAmber,
            Icons.Default.AutoAwesome
        )
        VerificationBadge.FAST_INITIAL -> Triple(
            ObsidianElevated,
            TextSecondary,
            Icons.Default.AutoAwesome
        )
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (badge == VerificationBadge.VERIFYING) {
            CircularProgressIndicator(
                modifier = Modifier.size(11.dp),
                color = textColor,
                strokeWidth = 1.6.dp
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(12.dp)
            )
        }
        Text(
            text = badge.label,
            style = MaterialTheme.typography.labelSmall,
            color = textColor
        )
    }
}

@Composable
private fun FormattedAnswerBody(detailedText: String) {
    val segments = detailedText.split("```")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        segments.forEachIndexed { index, segment ->
            val trimmed = segment.trim()
            if (trimmed.isNotEmpty()) {
                if (index % 2 == 1) {
                    val codeContent = trimmed.lines().let { lines ->
                        if (lines.firstOrNull()?.all { it.isLetterOrDigit() } == true && lines.size > 1) {
                            lines.drop(1).joinToString("\n")
                        } else {
                            trimmed
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF060910))
                            .border(1.dp, SubtleBorder, RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = codeContent,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = JetBrainsMonoFontFamily
                            ),
                            color = LockMint
                        )
                    }
                } else {
                    Text(
                        text = trimmed,
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextPrimary
                    )
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val clip = ClipData.newPlainText("Blink AI Answer", text)
    clipboard?.setPrimaryClip(clip)
}
