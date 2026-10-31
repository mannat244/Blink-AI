package com.example.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.data.AnswerStyle
import com.example.data.BlinkRepository
import com.example.data.CapturedFrame
import com.example.data.CodingLanguage
import com.example.data.PromptPresetEntity
import com.example.data.TargetAiApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class HandoffPackage(
    val synthesizedPrompt: String,
    val detectedDomain: String,
    val imageUris: List<Uri>,
    val targetApp: TargetAiApp,
    val targetAppInstalled: Boolean
)

/**
 * Blink AI Dispatcher & App Handoff Engine.
 *
 * Responsibilities:
 * 1. Generate the exact preconfigured prompt (MCQ Answer or Solve Accurately with Default Coding Language).
 * 2. Package captured images via FileProvider + attach the synthesized prompt.
 * 3. Copy the prompt to Clipboard automatically and launch the user-selected installed AI application.
 */
object AiHandoffDispatcher {

    fun isAiAppInstalled(context: Context, app: TargetAiApp): Boolean {
        if (app == TargetAiApp.SYSTEM_CHOOSER) return true
        val pm = context.packageManager
        return isPackagePresent(pm, app.packageName) ||
            (app.fallbackPackageName.isNotBlank() && isPackagePresent(pm, app.fallbackPackageName))
    }

    private fun resolveTargetPackage(context: Context, app: TargetAiApp): String? {
        if (app == TargetAiApp.SYSTEM_CHOOSER) return null
        val pm = context.packageManager
        if (app.packageName.isNotBlank() && isPackagePresent(pm, app.packageName)) {
            return app.packageName
        }
        if (app.fallbackPackageName.isNotBlank() && isPackagePresent(pm, app.fallbackPackageName)) {
            return app.fallbackPackageName
        }
        return null
    }

    private fun isPackagePresent(pm: PackageManager, pkg: String): Boolean {
        if (pkg.isBlank()) return false
        return try {
            pm.getPackageInfo(pkg, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Synthesizes a clean handoff instruction from the selected preconfigured mode
     * (MCQ Answer or Solve Accurately + Default Coding Language) with NO OCR text injected.
     */
    fun buildSynthesizedMasterPrompt(
        frames: List<CapturedFrame>,
        promptPreset: PromptPresetEntity,
        customTypedPrompt: String = "",
        answerStyle: AnswerStyle = AnswerStyle.BALANCED,
        codingLanguage: CodingLanguage = CodingLanguage.CPP
    ): Pair<String, String> {
        val isMcq = promptPreset.id == BlinkRepository.PROMPT_ID_MCQ || promptPreset.forceShortOutput
        val domain = if (isMcq) {
            "MCQ Answer"
        } else {
            "Solve (${codingLanguage.shortBadge})"
        }

        val coreInstruction = BlinkRepository.buildPromptForPreset(
            preset = promptPreset,
            codingLanguage = codingLanguage
        )

        val sb = StringBuilder()
        sb.appendLine(coreInstruction)

        if (frames.size > 1) {
            sb.appendLine()
            sb.appendLine(
                "Context Note: ${frames.size} sequential images are attached in chronological order (#1 to #${frames.size}). Treat them as one unified problem."
            )
        }

        return sb.toString().trim() to domain
    }

    /**
     * Prepares the FileProvider image URIs and synthesized prompt package on IO dispatcher.
     */
    suspend fun prepareHandoffPackage(
        context: Context,
        frames: List<CapturedFrame>,
        promptPreset: PromptPresetEntity,
        customTypedPrompt: String,
        answerStyle: AnswerStyle,
        targetApp: TargetAiApp,
        codingLanguage: CodingLanguage = CodingLanguage.CPP,
        jpegQuality: Int = 90
    ): HandoffPackage = withContext(Dispatchers.IO) {
        val (prompt, domain) = buildSynthesizedMasterPrompt(
            frames = frames,
            promptPreset = promptPreset,
            customTypedPrompt = customTypedPrompt,
            answerStyle = answerStyle,
            codingLanguage = codingLanguage
        )

        val handoffDir = File(context.cacheDir, "handoff_captures").apply {
            if (!exists()) mkdirs()
            // Clean old cached handoff files
            listFiles()?.forEach { runCatching { it.delete() } }
        }

        val authority = "${context.packageName}.fileprovider"
        val uris = frames.mapIndexedNotNull { idx, frame ->
            runCatching {
                val file = File(handoffDir, "blink_capture_${idx + 1}.jpg")
                FileOutputStream(file).use { out ->
                    frame.bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
                    out.flush()
                }
                FileProvider.getUriForFile(context, authority, file)
            }.getOrNull()
        }

        val installed = isAiAppInstalled(context, targetApp)
        HandoffPackage(
            synthesizedPrompt = prompt,
            detectedDomain = domain,
            imageUris = uris,
            targetApp = targetApp,
            targetAppInstalled = installed
        )
    }

    /**
     * Executes the AI App Handoff:
     * 1. Copies the synthesized prompt to Clipboard automatically (so apps like Google Gemini that
     *    only read EXTRA_STREAM when sharing images still have the prompt on the keyboard clipboard for 1-tap paste).
     * 2. Populates all standard text & stream extras on the share intent.
     * 3. Launches the target AI app (or Android Share Sheet) with the attached image(s) + prompt.
     */
    fun launchAiAppHandoff(
        context: Context,
        pkg: HandoffPackage
    ): Boolean {
        // 1. Always copy the prompt to clipboard so it is immediately available on the keyboard bar
        runCatching {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText("Blink AI Prompt", pkg.synthesizedPrompt)
            clipboard?.setPrimaryClip(clip)
        }

        val resolvedPackage = resolveTargetPackage(context, pkg.targetApp)
        val uris = pkg.imageUris

        val sendIntent = if (uris.size <= 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = if (uris.isNotEmpty()) "image/jpeg" else "text/plain"
                putExtra(Intent.EXTRA_TEXT, pkg.synthesizedPrompt)
                putExtra(Intent.EXTRA_SUBJECT, pkg.synthesizedPrompt)
                putExtra(Intent.EXTRA_TITLE, pkg.synthesizedPrompt)
                putExtra("prompt", pkg.synthesizedPrompt)
                putExtra("query", pkg.synthesizedPrompt)
                putExtra("q", pkg.synthesizedPrompt)
                if (uris.isNotEmpty()) {
                    val singleUri = uris.first()
                    putExtra(Intent.EXTRA_STREAM, singleUri)
                    val clip = ClipData.newUri(context.contentResolver, "Blink AI Capture", singleUri)
                    clip.addItem(ClipData.Item(pkg.synthesizedPrompt))
                    clipData = clip
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_TEXT, pkg.synthesizedPrompt)
                putExtra(Intent.EXTRA_SUBJECT, pkg.synthesizedPrompt)
                putExtra(Intent.EXTRA_TITLE, pkg.synthesizedPrompt)
                putExtra("prompt", pkg.synthesizedPrompt)
                putExtra("query", pkg.synthesizedPrompt)
                putExtra("q", pkg.synthesizedPrompt)
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                val clip = ClipData.newUri(context.contentResolver, "Blink AI Capture 1", uris.first())
                for (i in 1 until uris.size) {
                    clip.addItem(ClipData.Item(uris[i]))
                }
                clip.addItem(ClipData.Item(pkg.synthesizedPrompt))
                clipData = clip
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        // Try launching the user-selected AI app directly if installed
        if (resolvedPackage != null) {
            val directIntent = Intent(sendIntent).apply {
                setPackage(resolvedPackage)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            for (uri in uris) {
                runCatching {
                    context.grantUriPermission(
                        resolvedPackage,
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            val launchedDirect = runCatching {
                context.startActivity(directIntent)
                true
            }.getOrDefault(false)

            if (launchedDirect) {
                runCatching {
                    Toast.makeText(
                        context,
                        "Prompt copied to clipboard — tap Paste if ${pkg.targetApp.displayName} only attaches the image",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return true
            }
        }

        // Fallback: Launch native Android Share Chooser with prompt + images pre-attached
        return runCatching {
            val chooser = Intent.createChooser(
                sendIntent,
                "Dispatch Prompt + ${uris.size} Image(s) to AI App"
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            runCatching {
                Toast.makeText(
                    context,
                    "Prompt copied to clipboard — Paste into AI app if needed",
                    Toast.LENGTH_LONG
                ).show()
            }
            true
        }.getOrDefault(false)
    }
}
