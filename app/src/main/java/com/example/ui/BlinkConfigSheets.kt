package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ai.AiHandoffDispatcher
import com.example.data.AiProvider
import com.example.data.AnswerStyle
import com.example.data.BlinkSettings
import com.example.data.CaptureSensitivity
import com.example.data.CodingLanguage
import com.example.data.DispatchMode
import com.example.data.GeminiModelQuotaStatus
import com.example.data.GeminiModelTier
import com.example.data.GeminiRoutingMode
import com.example.data.GeminiVisionModel
import com.example.data.GroqVisionModel
import com.example.data.ImageResolutionPref
import com.example.data.ProviderConnectionStatus
import com.example.data.SessionHistoryEntity
import com.example.data.TargetAiApp
import com.example.ui.theme.AlertCoral
import com.example.ui.theme.AlertCoralContainer
import com.example.ui.theme.AmoledBlack
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LockMint
import com.example.ui.theme.LockMintContainer
import com.example.ui.theme.ObsidianCard
import com.example.ui.theme.ObsidianElevated
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.OpticalCyan
import com.example.ui.theme.SubtleBorder
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VerifiedAmber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * AI Dispatcher & App Handoff Sheet:
 * Lets the user select how Blink dispatches captured context (AI App Handoff vs Smart Auto vs Cloud API),
 * pick their preferred installed AI application (Gemini, ChatGPT, Claude, Perplexity, Copilot, DeepSeek),
 * and launch immediately.
 */
@Composable
fun AiHandoffDispatcherSheet(
    dispatchMode: DispatchMode,
    selectedApp: TargetAiApp,
    capturedCount: Int,
    synthesizedPromptPreview: String,
    detectedDomain: String,
    onSelectDispatchMode: (DispatchMode) -> Unit,
    onSelectTargetApp: (TargetAiApp) -> Unit,
    onLaunchHandoffNow: (TargetAiApp) -> Unit,
    onClose: () -> Unit
) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onClose),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxWidth()
                .clickable(enabled = false, onClick = {})
                .windowInsetsPadding(WindowInsets.navigationBars)
                .testTag("ai_handoff_dispatcher_sheet"),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = ObsidianSurface.copy(alpha = 0.94f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "AI Dispatcher & App Handoff",
                            style = MaterialTheme.typography.titleLarge,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Transfers captured image(s) to your installed AI apps",
                            style = MaterialTheme.typography.bodySmall,
                            color = LockMint
                        )
                    }

                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                    }
                }

                // 1. Dispatch Strategy
                Text(
                    text = "DISPATCH MODE",
                    style = MaterialTheme.typography.labelMedium,
                    color = OpticalCyan
                )
                DispatchMode.entries.forEach { mode ->
                    SelectableOptionRow(
                        title = mode.displayName,
                        subtitle = mode.subtitle,
                        selected = dispatchMode == mode,
                        onClick = { onSelectDispatchMode(mode) }
                    )
                }

                // 2. Target Installed AI Application
                Text(
                    text = "SELECT TARGET AI APPLICATION",
                    style = MaterialTheme.typography.labelMedium,
                    color = OpticalCyan
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TargetAiApp.entries.forEach { app ->
                        val isInstalled = remember(app) {
                            AiHandoffDispatcher.isAiAppInstalled(context, app)
                        }
                        val isSelected = selectedApp == app
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (isSelected) ObsidianElevated else ObsidianCard)
                                .border(
                                    width = 1.dp,
                                    color = if (isSelected) LockMint else SubtleBorder,
                                    shape = RoundedCornerShape(14.dp)
                                )
                                .clickable { onSelectTargetApp(app) }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = app.displayName,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = if (isSelected) LockMint else TextPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (app != TargetAiApp.SYSTEM_CHOOSER && isInstalled) {
                                        Text(
                                            text = "✓ INSTALLED",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = LockMint
                                        )
                                    }
                                }
                                Text(
                                    text = if (app == TargetAiApp.SYSTEM_CHOOSER) {
                                        "Opens Android Share Sheet with image(s) attached"
                                    } else if (isInstalled) {
                                        "Directly launches ${app.displayName} with image(s)"
                                    } else {
                                        "Uses Android Share Chooser if ${app.displayName} is not installed"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }

                            if (capturedCount > 0) {
                                Button(
                                    onClick = { onLaunchHandoffNow(app) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = LockMint,
                                        contentColor = AmoledBlack
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.height(38.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.OpenInNew,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Launch", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // 3. Preconfigured Mode Preview (MCQ Answer / Solve Accurately)
                if (synthesizedPromptPreview.isNotBlank()) {
                    Text(
                        text = "PRECONFIGURED MODE ($detectedDomain)",
                        style = MaterialTheme.typography.labelMedium,
                        color = OpticalCyan
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 120.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF060910))
                            .border(1.dp, SubtleBorder, RoundedCornerShape(12.dp))
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = synthesizedPromptPreview,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }
}

/**
 * Simplified Settings Sheet:
 * - AI Provider Section:
 *   - Select Gemini or Groq
 *   - Gemini: Existing API key configuration (paste, save, edit, remove), Model: Gemini 3.8 Flash (`gemini-3.8-flash`), Connection Test
 *   - Groq: API key input (paste, save, edit, remove), Vision Model selector (`qwen/qwen3.8-27b`, etc.), Connection Test
 * - Smart Auto-Click Camera (Readable Text) — no shape or screen detection needed.
 */
@Composable
fun BlinkSettingsSheet(
    settings: BlinkSettings,
    geminiConnectionStatus: ProviderConnectionStatus,
    groqConnectionStatus: ProviderConnectionStatus,
    geminiQuotaStatuses: List<GeminiModelQuotaStatus> = GeminiVisionModel.entries.map { GeminiModelQuotaStatus(it) },
    onUpdateDispatchMode: (DispatchMode) -> Unit,
    onUpdateTargetAiApp: (TargetAiApp) -> Unit,
    onUpdateAiProvider: (AiProvider) -> Unit,
    onUpdateGeminiRoutingMode: (GeminiRoutingMode) -> Unit = {},
    onUpdateGeminiModel: (String) -> Unit = {},
    onResetGeminiQuota: () -> Unit = {},
    onUpdateGroqModel: (String) -> Unit,
    onSaveGeminiApiKey: (String) -> Unit,
    onSaveGroqApiKey: (String) -> Unit,
    onTestProviderConnection: (AiProvider, String?, String?, String?) -> Unit,
    onUpdateCodingLanguage: (CodingLanguage) -> Unit = {},
    onUpdateAnswerStyle: (AnswerStyle) -> Unit,
    onUpdateSensitivity: (CaptureSensitivity) -> Unit,
    onUpdateResolution: (ImageResolutionPref) -> Unit,
    onUpdateMaxImages: (Int) -> Unit,
    onUpdateAutoCapture: (Boolean) -> Unit,
    onUpdateStartBlackMode: (Boolean) -> Unit,
    onUpdateAllowBrightnessControl: (Boolean) -> Unit,
    onUpdateCustomBrightnessLevel: (Float) -> Unit,
    onUpdateSaveHistory: (Boolean) -> Unit,
    onClearAllData: () -> Unit,
    onClose: () -> Unit
) {
    BackHandler(onBack = onClose)
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current

    var geminiKeyInput by remember(settings.geminiApiKey) { mutableStateOf(settings.geminiApiKey) }
    var groqKeyInput by remember(settings.groqApiKey) { mutableStateOf(settings.groqApiKey) }
    var showGeminiKeyPlain by remember { mutableStateOf(false) }
    var showGroqKeyPlain by remember { mutableStateOf(false) }

    val quotaByModel = remember(geminiQuotaStatuses) {
        geminiQuotaStatuses.associateBy { it.model }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .clickable(onClick = onClose),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxWidth()
                .clickable(enabled = false, onClick = {})
                .windowInsetsPadding(WindowInsets.navigationBars)
                .testTag("settings_sheet"),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = ObsidianSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(640.dp)
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.settings_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = TextPrimary
                        )
                        Text(
                            text = "Google Gemini • Groq Vision • Server-Synced Rate Limits",
                            style = MaterialTheme.typography.bodySmall,
                            color = LockMint
                        )
                    }
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("close_settings_button")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close settings", tint = TextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    // 1. Minimal AI Provider Configuration (ONLY Google Gemini & Groq)
                    SettingSectionHeader("1. AI PROVIDER")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        AiProvider.entries.forEach { provider ->
                            val selected = settings.aiProvider == provider
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (selected) ObsidianElevated else ObsidianCard)
                                    .border(
                                        width = 1.2.dp,
                                        color = if (selected) LockMint else SubtleBorder,
                                        shape = RoundedCornerShape(14.dp)
                                    )
                                    .clickable { onUpdateAiProvider(provider) }
                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                                    .testTag("select_provider_${provider.name.lowercase()}")
                            ) {
                                Column {
                                    Text(
                                        text = provider.displayName,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = if (selected) LockMint else TextPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = provider.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    // Provider A: Google Gemini Configuration Card
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(ObsidianCard)
                            .border(
                                width = 1.dp,
                                color = if (settings.aiProvider == AiProvider.GEMINI_CLOUD) {
                                    OpticalCyan.copy(alpha = 0.65f)
                                } else {
                                    SubtleBorder
                                },
                                shape = RoundedCornerShape(16.dp)
                            )
                            .padding(14.dp)
                            .testTag("gemini_provider_card"),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Google Gemini",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Routing: ${settings.geminiRoutingMode.shortBadge} • Pinned/Default: ${settings.fastModel}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = OpticalCyan
                                )
                            }
                            if (settings.aiProvider == AiProvider.GEMINI_CLOUD) {
                                Text(
                                    text = "DEFAULT ACTIVE",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = LockMint,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        OutlinedTextField(
                            value = geminiKeyInput,
                            onValueChange = { geminiKeyInput = it },
                            label = { Text("Gemini API Key") },
                            placeholder = { Text("Paste your Gemini API key") },
                            singleLine = true,
                            visualTransformation = if (showGeminiKeyPlain) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = {
                                            val clip = clipboardManager.getText()?.text?.trim().orEmpty()
                                            if (clip.isNotEmpty()) {
                                                geminiKeyInput = clip
                                            }
                                        },
                                        modifier = Modifier
                                            .size(40.dp)
                                            .testTag("paste_gemini_key_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ContentPaste,
                                            contentDescription = "Paste Gemini API key",
                                            tint = OpticalCyan,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = { showGeminiKeyPlain = !showGeminiKeyPlain },
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (showGeminiKeyPlain) {
                                                Icons.Default.VisibilityOff
                                            } else {
                                                Icons.Default.Visibility
                                            },
                                            contentDescription = "Toggle Gemini key visibility",
                                            tint = TextSecondary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_gemini_api_key_input")
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = { onSaveGeminiApiKey(geminiKeyInput) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = LockMint,
                                    contentColor = AmoledBlack
                                ),
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("save_gemini_key_inline_button")
                            ) {
                                Text("Save Key", fontWeight = FontWeight.Bold, maxLines = 1)
                            }

                            if (geminiKeyInput.isNotBlank() || settings.geminiApiKey.isNotBlank()) {
                                OutlinedButton(
                                    onClick = {
                                        geminiKeyInput = ""
                                        onSaveGeminiApiKey("")
                                    },
                                    modifier = Modifier
                                        .height(48.dp)
                                        .testTag("remove_gemini_key_button")
                                ) {
                                    Text("Remove", color = AlertCoral, maxLines = 1)
                                }
                            }

                            Spacer(modifier = Modifier.weight(1f))

                            OutlinedButton(
                                onClick = {
                                    onTestProviderConnection(
                                        AiProvider.GEMINI_CLOUD,
                                        geminiKeyInput,
                                        null,
                                        null
                                    )
                                },
                                enabled = !geminiConnectionStatus.isTesting,
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("test_gemini_connection_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.NetworkCheck,
                                    contentDescription = null,
                                    tint = OpticalCyan,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Test API", color = OpticalCyan, maxLines = 1)
                            }
                        }

                        ConnectionStatusBanner(status = geminiConnectionStatus)

                        HorizontalDivider(color = SubtleBorder.copy(alpha = 0.5f))

                        // Quality vs. Quantity Routing Strategy
                        Text(
                            text = "ROUTING STRATEGY (QUALITY VS. QUANTITY TRADE-OFF)",
                            style = MaterialTheme.typography.labelLarge,
                            color = OpticalCyan,
                            fontWeight = FontWeight.Bold
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            GeminiRoutingMode.entries.forEach { mode ->
                                SelectableOptionRow(
                                    title = mode.displayName,
                                    subtitle = mode.subtitle,
                                    selected = settings.geminiRoutingMode == mode,
                                    onClick = { onUpdateGeminiRoutingMode(mode) }
                                )
                            }
                        }

                        HorizontalDivider(color = SubtleBorder.copy(alpha = 0.5f))

                        // Manual Model Selection & Server-Synced Rate Limits
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "MANUAL MODEL SELECTOR & SERVER-SYNCED RATE LIMITS",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = LockMint,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Tap any model to pin it manually • Automatically skips on 429 quota exhaustion",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                            OutlinedButton(
                                onClick = onResetGeminiQuota,
                                modifier = Modifier
                                    .height(36.dp)
                                    .testTag("reset_gemini_quota_button")
                            ) {
                                Text(
                                    text = "Reset Quota",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = OpticalCyan
                                )
                            }
                        }

                        // A. Economy Models (500 RPD, 15 RPM, 250,000 TPM)
                        Text(
                            text = "A. Economy Pipeline (Quantity • 15 RPM | 250,000 TPM | 500 RPD)",
                            style = MaterialTheme.typography.labelMedium,
                            color = LockMint,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Everyday questions, MCQs, definitions, factual, text extraction & summarisation. Backup #3: Groq fast model (${GroqVisionModel.FAST_BACKUP_MODEL_ID}).",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            GeminiVisionModel.economyPipelineOrder.forEach { model ->
                                val status = quotaByModel[model] ?: GeminiModelQuotaStatus(model)
                                val isManuallyPinned = settings.fastModel == model.modelId &&
                                    settings.geminiRoutingMode == GeminiRoutingMode.MANUAL_MODEL
                                val isDefaultInTier = settings.fastModel == model.modelId
                                GeminiModelQuotaRow(
                                    status = status,
                                    isSelected = isManuallyPinned || isDefaultInTier,
                                    isManualMode = settings.geminiRoutingMode == GeminiRoutingMode.MANUAL_MODEL,
                                    onSelectModel = { onUpdateGeminiModel(model.modelId) }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // B. Premium Models (20 RPD, 5 RPM, 250,000 TPM)
                        Text(
                            text = "B. Premium Pipeline (Quality • 5 RPM | 250,000 TPM | 20 RPD)",
                            style = MaterialTheme.typography.labelMedium,
                            color = OpticalCyan,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Complex math, DSA & programming, debugging, multi-step reasoning & architecture. Backup #5: Groq strong model (${settings.groqModel}).",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            GeminiVisionModel.premiumPipelineOrder.forEach { model ->
                                val status = quotaByModel[model] ?: GeminiModelQuotaStatus(model)
                                val isManuallyPinned = settings.fastModel == model.modelId &&
                                    settings.geminiRoutingMode == GeminiRoutingMode.MANUAL_MODEL
                                val isDefaultInTier = settings.fastModel == model.modelId
                                GeminiModelQuotaRow(
                                    status = status,
                                    isSelected = isManuallyPinned || isDefaultInTier,
                                    isManualMode = settings.geminiRoutingMode == GeminiRoutingMode.MANUAL_MODEL,
                                    onSelectModel = { onUpdateGeminiModel(model.modelId) }
                                )
                            }
                        }
                    }

                    // Provider B: Groq Configuration Card
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(ObsidianCard)
                            .border(
                                width = 1.dp,
                                color = if (settings.aiProvider == AiProvider.GROQ) {
                                    OpticalCyan.copy(alpha = 0.65f)
                                } else {
                                    SubtleBorder
                                },
                                shape = RoundedCornerShape(16.dp)
                            )
                            .padding(14.dp)
                            .testTag("groq_provider_card"),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Groq",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Endpoint: api.groq.com/openai/v1/chat/completions • Up to 3 images/req (auto-batched)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                            if (settings.aiProvider == AiProvider.GROQ) {
                                Text(
                                    text = "ACTIVE",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = LockMint,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        OutlinedTextField(
                            value = groqKeyInput,
                            onValueChange = { groqKeyInput = it },
                            label = { Text("Groq API Key (gsk_...)") },
                            placeholder = { Text("Paste your Groq API key") },
                            singleLine = true,
                            visualTransformation = if (showGroqKeyPlain) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = {
                                            val clip = clipboardManager.getText()?.text?.trim().orEmpty()
                                            if (clip.isNotEmpty()) {
                                                groqKeyInput = clip
                                            }
                                        },
                                        modifier = Modifier
                                            .size(40.dp)
                                            .testTag("paste_groq_key_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ContentPaste,
                                            contentDescription = "Paste Groq API key",
                                            tint = OpticalCyan,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = { showGroqKeyPlain = !showGroqKeyPlain },
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (showGroqKeyPlain) {
                                                Icons.Default.VisibilityOff
                                            } else {
                                                Icons.Default.Visibility
                                            },
                                            contentDescription = "Toggle Groq key visibility",
                                            tint = TextSecondary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_groq_api_key_input")
                        )

                        Text(
                            text = "Vision Model Selector",
                            style = MaterialTheme.typography.labelLarge,
                            color = OpticalCyan
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            GroqVisionModel.entries.forEach { visionModel ->
                                val isSelected = settings.groqModel == visionModel.modelId
                                SelectableOptionRow(
                                    title = visionModel.displayName,
                                    subtitle = "Supports multimodal vision + text (up to ${visionModel.maxImagesPerRequest} images per request)",
                                    selected = isSelected,
                                    onClick = { onUpdateGroqModel(visionModel.modelId) }
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = { onSaveGroqApiKey(groqKeyInput) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = LockMint,
                                    contentColor = AmoledBlack
                                ),
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("save_groq_key_button")
                            ) {
                                Text("Save Key", fontWeight = FontWeight.Bold, maxLines = 1)
                            }

                            if (groqKeyInput.isNotBlank() || settings.groqApiKey.isNotBlank()) {
                                OutlinedButton(
                                    onClick = {
                                        groqKeyInput = ""
                                        onSaveGroqApiKey("")
                                    },
                                    modifier = Modifier
                                        .height(48.dp)
                                        .testTag("remove_groq_key_button")
                                ) {
                                    Text("Remove", color = AlertCoral, maxLines = 1)
                                }
                            }

                            Spacer(modifier = Modifier.weight(1f))

                            OutlinedButton(
                                onClick = {
                                    onTestProviderConnection(
                                        AiProvider.GROQ,
                                        null,
                                        groqKeyInput,
                                        settings.groqModel
                                    )
                                },
                                enabled = !groqConnectionStatus.isTesting,
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("test_groq_connection_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.NetworkCheck,
                                    contentDescription = null,
                                    tint = OpticalCyan,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Test API", color = OpticalCyan, maxLines = 1)
                            }
                        }

                        ConnectionStatusBanner(status = groqConnectionStatus)
                    }

                    // 2. AI Dispatcher & App Handoff Mode
                    SettingSectionHeader("2. AI DISPATCHER & APP HANDOFF MODE")
                    DispatchMode.entries.forEach { mode ->
                        SelectableOptionRow(
                            title = mode.displayName,
                            subtitle = mode.subtitle,
                            selected = settings.dispatchMode == mode,
                            onClick = { onUpdateDispatchMode(mode) }
                        )
                    }

                    Text(
                        text = "Preferred Installed AI App for Handoff",
                        style = MaterialTheme.typography.labelLarge,
                        color = TextSecondary
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TargetAiApp.entries.forEach { app ->
                            ChoiceChipPill(
                                label = app.displayName,
                                selected = settings.targetAiApp == app,
                                onClick = { onUpdateTargetAiApp(app) }
                            )
                        }
                    }

                    // 3. Smart Auto-Click Camera (Readable Text)
                    SettingSectionHeader("3. SMART AUTO-CLICK CAMERA (READABLE TEXT)")
                    ToggleSettingRow(
                        title = "Auto-Click When Readable Text Is Seen",
                        subtitle = "Automatically clicks a picture as soon as readable text is in focus — no shape/screen detection needed",
                        checked = settings.autoCaptureEnabled,
                        onCheckedChange = onUpdateAutoCapture
                    )

                    Text(
                        text = "Auto-Click Speed & Sensitivity",
                        style = MaterialTheme.typography.labelLarge,
                        color = TextSecondary
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CaptureSensitivity.entries.forEach { sens ->
                            ChoiceChipPill(
                                label = sens.displayName,
                                selected = settings.captureSensitivity == sens,
                                onClick = { onUpdateSensitivity(sens) }
                            )
                        }
                    }

                    Text(
                        text = "Default Coding Language (For Solve Accurately Mode)",
                        style = MaterialTheme.typography.labelLarge,
                        color = OpticalCyan
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CodingLanguage.entries.forEach { lang ->
                            ChoiceChipPill(
                                label = lang.displayName,
                                selected = settings.codingLanguage == lang,
                                onClick = { onUpdateCodingLanguage(lang) }
                            )
                        }
                    }

                    Text(
                        text = "Answer Detail Style",
                        style = MaterialTheme.typography.labelLarge,
                        color = TextSecondary
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AnswerStyle.entries.forEach { style ->
                            ChoiceChipPill(
                                label = style.displayName,
                                selected = settings.answerStyle == style,
                                onClick = { onUpdateAnswerStyle(style) }
                            )
                        }
                    }

                    Text(
                        text = "Capture Resolution",
                        style = MaterialTheme.typography.labelLarge,
                        color = TextSecondary
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ImageResolutionPref.entries.forEach { res ->
                            ChoiceChipPill(
                                label = res.displayName,
                                selected = settings.imageResolution == res,
                                onClick = { onUpdateResolution(res) }
                            )
                        }
                    }

                    // 4. Display Brightness Permission & Control
                    SettingSectionHeader("4. BRIGHTNESS PERMISSION & POWER CONTROL")
                    ToggleSettingRow(
                        title = "Allow Brightness Reduction in Black-Screen Mode",
                        subtitle = "Blink NEVER reduces display brightness without your explicit permission",
                        checked = settings.allowBrightnessControl,
                        onCheckedChange = onUpdateAllowBrightnessControl
                    )

                    if (settings.allowBrightnessControl) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(ObsidianCard)
                                .padding(14.dp)
                        ) {
                            val pct = (settings.customBrightnessLevel * 100f).roundToInt()
                            Text(
                                text = "Preferred Black-Mode Brightness Level: $pct%",
                                style = MaterialTheme.typography.labelLarge,
                                color = LockMint
                            )
                            Slider(
                                value = settings.customBrightnessLevel,
                                onValueChange = onUpdateCustomBrightnessLevel,
                                valueRange = 0.05f..1.0f
                            )
                        }
                    }

                    ToggleSettingRow(
                        title = "Always Open in Black-Screen Stealth Mode",
                        subtitle = "Start sessions with AMOLED black background overlay",
                        checked = settings.startInBlackScreenMode,
                        onCheckedChange = onUpdateStartBlackMode
                    )

                    // 5. Session Limits & Privacy
                    SettingSectionHeader("5. SESSION LIMITS & PRIVACY")
                    Column {
                        Text(
                            text = "Max Images Per Session: ${settings.maxImagesPerSession}",
                            style = MaterialTheme.typography.labelLarge,
                            color = TextPrimary
                        )
                        Slider(
                            value = settings.maxImagesPerSession.toFloat(),
                            onValueChange = { onUpdateMaxImages(it.toInt()) },
                            valueRange = 1f..8f,
                            steps = 6
                        )
                    }

                    ToggleSettingRow(
                        title = "Save Solved & Dispatched Sessions to History",
                        subtitle = "Captured images are cleared after processing or handoff",
                        checked = settings.saveSessionHistory,
                        onCheckedChange = onUpdateSaveHistory
                    )

                    OutlinedButton(
                        onClick = onClearAllData,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("clear_all_privacy_data_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteForever,
                            contentDescription = null,
                            tint = AlertCoral
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Delete All Session History & Active Captures",
                            color = AlertCoral,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionStatusBanner(status: ProviderConnectionStatus) {
    if (!status.isTesting && status.message.isBlank()) return

    val bgColor = when {
        status.isTesting -> ObsidianElevated
        status.isSuccess == true -> LockMintContainer.copy(alpha = 0.55f)
        status.isSuccess == false -> AlertCoralContainer.copy(alpha = 0.65f)
        else -> ObsidianElevated
    }
    val contentColor = when {
        status.isTesting -> OpticalCyan
        status.isSuccess == true -> LockMint
        status.isSuccess == false -> AlertCoral
        else -> TextSecondary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("connection_status_${status.provider.name.lowercase()}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when {
            status.isTesting -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = contentColor,
                    strokeWidth = 2.dp
                )
            }
            status.isSuccess == true -> {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(16.dp)
                )
            }
            status.isSuccess == false -> {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        Text(
            text = status.message,
            style = MaterialTheme.typography.bodySmall,
            color = contentColor
        )
    }
}

@Composable
fun SessionHistorySheet(
    history: List<SessionHistoryEntity>,
    onSelectSession: (SessionHistoryEntity) -> Unit,
    onDeleteSession: (Int) -> Unit,
    onClearAll: () -> Unit,
    onClose: () -> Unit
) {
    BackHandler(onBack = onClose)
    val dateFormat = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .clickable(onClick = onClose),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxWidth()
                .clickable(enabled = false, onClick = {})
                .windowInsetsPadding(WindowInsets.navigationBars)
                .testTag("history_sheet"),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = ObsidianSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(500.dp)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.history_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = TextPrimary
                        )
                        Text(
                            text = "${history.size} saved session(s)",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (history.isNotEmpty()) {
                            OutlinedButton(
                                onClick = onClearAll,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.height(48.dp)
                            ) {
                                Text("Clear All", color = AlertCoral)
                            }
                        }
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("close_history_button")
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close history", tint = TextSecondary)
                        }
                    }
                }

                if (history.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "No solved sessions yet",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Point your camera at readable text to auto-click.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(history, key = { it.id }) { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(ObsidianCard)
                                    .border(1.dp, SubtleBorder, RoundedCornerShape(16.dp))
                                    .clickable { onSelectSession(item) }
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = item.category,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = OpticalCyan
                                        )
                                        Text(
                                            text = "• ${item.promptTitle}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = LockMint
                                        )
                                        Text(
                                            text = "• ${dateFormat.format(Date(item.timestamp))}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextMuted
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = item.shortSummary,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = TextPrimary,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = item.extractedQuestion,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(
                                    onClick = { onDeleteSession(item.id) },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete session",
                                        tint = TextMuted
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingSectionHeader(title: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HorizontalDivider(color = SubtleBorder.copy(alpha = 0.5f))
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = OpticalCyan
        )
    }
}

@Composable
private fun SelectableOptionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) ObsidianElevated else ObsidianCard)
            .border(
                width = 1.dp,
                color = if (selected) LockMint else SubtleBorder,
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) LockMint else TextPrimary,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun ChoiceChipPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) OpticalCyan.copy(alpha = 0.2f) else ObsidianCard)
            .border(
                width = 1.dp,
                color = if (selected) OpticalCyan else SubtleBorder,
                shape = RoundedCornerShape(50)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) OpticalCyan else TextSecondary
        )
    }
}

@Composable
private fun ToggleSettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ObsidianCard)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun GeminiModelQuotaRow(
    status: GeminiModelQuotaStatus,
    isSelected: Boolean,
    isManualMode: Boolean,
    onSelectModel: () -> Unit
) {
    val isExhausted = status.isExhaustedOrCoolingDown
    val borderColor = when {
        isExhausted -> AlertCoral.copy(alpha = 0.70f)
        isSelected && isManualMode -> LockMint
        isSelected -> OpticalCyan
        else -> SubtleBorder
    }
    val containerColor = when {
        isExhausted -> AlertCoral.copy(alpha = 0.08f)
        isSelected && isManualMode -> LockMint.copy(alpha = 0.14f)
        isSelected -> OpticalCyan.copy(alpha = 0.12f)
        else -> AmoledBlack.copy(alpha = 0.65f)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(containerColor)
            .border(width = 1.dp, color = borderColor, shape = RoundedCornerShape(14.dp))
            .clickable(onClick = onSelectModel)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("gemini_model_row_${status.model.modelId}"),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (status.model.tier == GeminiModelTier.ECONOMY) {
                                LockMint.copy(alpha = 0.18f)
                            } else {
                                OpticalCyan.copy(alpha = 0.18f)
                            }
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "#${status.model.priority}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (status.model.tier == GeminiModelTier.ECONOMY) LockMint else OpticalCyan,
                        fontWeight = FontWeight.Bold
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = status.model.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (isExhausted) AlertCoral else TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = status.model.modelId,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }

            val badgeText = when {
                isExhausted -> "EXHAUSTED • AUTO-SKIP"
                isSelected && isManualMode -> "PINNED MANUAL"
                isSelected -> "SELECTED"
                else -> "TAP TO PIN"
            }
            val badgeColor = when {
                isExhausted -> AlertCoral
                isSelected && isManualMode -> LockMint
                isSelected -> OpticalCyan
                else -> TextSecondary
            }

            Text(
                text = badgeText,
                style = MaterialTheme.typography.labelSmall,
                color = badgeColor,
                fontWeight = FontWeight.Bold
            )
        }

        // Exact supplied rate limits & live server-synced counters
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "RPM: ${status.rpmUsed}/${status.rpmLimit}",
                style = MaterialTheme.typography.labelSmall,
                color = if (status.isServerMinuteCooldown || status.rpmUsed >= status.rpmLimit) AlertCoral else TextPrimary,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "•",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
            Text(
                text = "TPM: ${"%,d".format(status.tpmUsed)}/${"%,d".format(status.tpmLimit)}",
                style = MaterialTheme.typography.labelSmall,
                color = if (status.tpmUsed >= status.tpmLimit) AlertCoral else TextPrimary,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "•",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
            Text(
                text = "RPD: ${status.rpdUsed}/${status.rpdLimit}",
                style = MaterialTheme.typography.labelSmall,
                color = if (status.isServerDailyExhausted || status.rpdUsed >= status.rpdLimit) AlertCoral else LockMint,
                fontWeight = FontWeight.Bold
            )
        }

        if (status.lastServerNote.isNotBlank()) {
            Text(
                text = status.lastServerNote,
                style = MaterialTheme.typography.bodySmall,
                color = if (isExhausted) AlertCoral else TextSecondary
            )
        }
    }
}

