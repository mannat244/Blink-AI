package com.example.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.blinkDataStore by preferencesDataStore(name = "blink_ai_prefs")

/**
 * Encrypts and decrypts API keys using Android Keystore AES/GCM/NoPadding so keys are
 * never stored in plain-text preferences. Includes a deterministic obfuscation fallback
 * for local JVM unit test environments where the hardware AndroidKeyStore is unavailable.
 */
internal object SecureCredentialCipher {
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "blink_ai_api_key_master_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val PREFIX_KEYSTORE = "enc:ks:v1:"
    private const val PREFIX_FALLBACK = "enc:fb:v1:"
    private val FALLBACK_MASK = byteArrayOf(
        0x5A, 0x3C, 0x7E, 0x19, 0x6B, 0x4D, 0x2F, 0x0E,
        0x71, 0x38, 0x64, 0x29, 0x55, 0x1C, 0x43, 0x7A
    )

    fun encrypt(plainText: String): String {
        val clean = plainText.trim()
        if (clean.isEmpty()) return ""
        return try {
            val secretKey = getOrCreateSecretKey()
            if (secretKey != null) {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, secretKey)
                val iv = cipher.iv
                val cipherBytes = cipher.doFinal(clean.toByteArray(StandardCharsets.UTF_8))
                val buffer = ByteBuffer.allocate(4 + iv.size + cipherBytes.size)
                buffer.putInt(iv.size)
                buffer.put(iv)
                buffer.put(cipherBytes)
                PREFIX_KEYSTORE + Base64.encodeToString(buffer.array(), Base64.NO_WRAP)
            } else {
                encodeFallback(clean)
            }
        } catch (_: Exception) {
            encodeFallback(clean)
        }
    }

    fun decrypt(storedValue: String): String {
        val trimmed = storedValue.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith(PREFIX_KEYSTORE)) {
            return try {
                val secretKey = getOrCreateSecretKey() ?: return ""
                val raw = Base64.decode(trimmed.removePrefix(PREFIX_KEYSTORE), Base64.NO_WRAP)
                val buffer = ByteBuffer.wrap(raw)
                val ivLength = buffer.int
                if (ivLength <= 0 || ivLength > 32 || buffer.remaining() < ivLength) return ""
                val iv = ByteArray(ivLength)
                buffer.get(iv)
                val cipherBytes = ByteArray(buffer.remaining())
                buffer.get(cipherBytes)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
                String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8)
            } catch (_: Exception) {
                ""
            }
        }
        if (trimmed.startsWith(PREFIX_FALLBACK)) {
            return decodeFallback(trimmed.removePrefix(PREFIX_FALLBACK))
        }
        // Seamless migration support: if an earlier app version stored a plain-text Gemini key,
        // return it so the existing key is preserved without loss.
        return trimmed
    }

    fun isEncryptedFormat(storedValue: String): Boolean {
        return storedValue.startsWith(PREFIX_KEYSTORE) || storedValue.startsWith(PREFIX_FALLBACK)
    }

    private fun getOrCreateSecretKey(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (existing != null) {
                existing.secretKey
            } else {
                val keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    KEYSTORE_PROVIDER
                )
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                keyGenerator.init(spec)
                keyGenerator.generateKey()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun encodeFallback(plainText: String): String {
        val bytes = plainText.toByteArray(StandardCharsets.UTF_8)
        val masked = ByteArray(bytes.size) { i ->
            (bytes[i].toInt() xor FALLBACK_MASK[i % FALLBACK_MASK.size].toInt()).toByte()
        }
        return PREFIX_FALLBACK + Base64.encodeToString(masked, Base64.NO_WRAP)
    }

    private fun decodeFallback(encodedPayload: String): String {
        return try {
            val masked = Base64.decode(encodedPayload, Base64.NO_WRAP)
            val unmasked = ByteArray(masked.size) { i ->
                (masked[i].toInt() xor FALLBACK_MASK[i % FALLBACK_MASK.size].toInt()).toByte()
            }
            String(unmasked, StandardCharsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }
}

class BlinkPreferencesRepository(private val context: Context) {

    private object Keys {
        val DISPATCH_MODE = stringPreferencesKey("dispatch_mode")
        val TARGET_AI_APP = stringPreferencesKey("target_ai_app")
        val AI_PROVIDER = stringPreferencesKey("ai_provider")
        val GEMINI_ROUTING_MODE = stringPreferencesKey("gemini_routing_mode")
        val FAST_MODEL = stringPreferencesKey("fast_model")
        val GEMINI_MODEL = stringPreferencesKey("gemini_model")
        val GROQ_MODEL = stringPreferencesKey("groq_model")
        val CODING_LANGUAGE = stringPreferencesKey("coding_language")
        val ANSWER_STYLE = stringPreferencesKey("answer_style")
        val CAPTURE_SENSITIVITY = stringPreferencesKey("capture_sensitivity")
        val IMAGE_RESOLUTION = stringPreferencesKey("image_resolution")
        val MAX_IMAGES = intPreferencesKey("max_images_per_session")
        val AUTO_CAPTURE = booleanPreferencesKey("auto_capture_enabled")
        val LAPTOP_SCREEN_MODE = booleanPreferencesKey("laptop_screen_mode")
        val START_BLACK_MODE = booleanPreferencesKey("start_in_black_mode")
        val ALLOW_BRIGHTNESS_CONTROL = booleanPreferencesKey("allow_brightness_control")
        val CUSTOM_BRIGHTNESS_LEVEL = floatPreferencesKey("custom_brightness_level")
        val SAVE_HISTORY = booleanPreferencesKey("save_session_history")
        val DEFAULT_PROMPT_ID = intPreferencesKey("default_prompt_id")

        val GEMINI_KEY_ENCRYPTED = stringPreferencesKey("gemini_api_key_enc")
        val GROQ_KEY_ENCRYPTED = stringPreferencesKey("groq_api_key_enc")
        val GEMINI_QUOTA_STATE_JSON = stringPreferencesKey("gemini_quota_state_json")

        // Migration support for previously saved Gemini/Groq keys
        val LEGACY_GEMINI_KEY = stringPreferencesKey("gemini_api_key")
        val LEGACY_GROQ_KEY = stringPreferencesKey("groq_api_key")
    }

    private fun resolveBuildConfigGeminiKey(): String {
        return runCatching {
            val field = BuildConfig::class.java.declaredFields.firstOrNull {
                it.name.equals("GEMINI_API_KEY", ignoreCase = true) ||
                    it.name.equals("GOOGLE_API_KEY", ignoreCase = true)
            }
            field?.isAccessible = true
            val raw = (field?.get(null) as? String)?.trim().orEmpty()
            if (raw.isEmpty() ||
                raw.startsWith("YOUR_", ignoreCase = true) ||
                raw.contains("PLACEHOLDER", ignoreCase = true) ||
                raw.equals("null", ignoreCase = true) ||
                raw.equals("TODO", ignoreCase = true)
            ) {
                ""
            } else {
                raw
            }
        }.getOrDefault("")
    }

    private fun resolveBuildConfigGroqKey(): String {
        return runCatching {
            val field = BuildConfig::class.java.declaredFields.firstOrNull {
                it.name.equals("GROQ_API_KEY", ignoreCase = true)
            }
            field?.isAccessible = true
            val raw = (field?.get(null) as? String)?.trim().orEmpty()
            if (raw.isEmpty() ||
                raw.startsWith("YOUR_", ignoreCase = true) ||
                raw.contains("PLACEHOLDER", ignoreCase = true) ||
                raw.equals("null", ignoreCase = true) ||
                raw.equals("TODO", ignoreCase = true)
            ) {
                ""
            } else {
                raw
            }
        }.getOrDefault("")
    }

    val geminiQuotaStateJsonFlow: Flow<String> = context.blinkDataStore.data.map { prefs ->
        prefs[Keys.GEMINI_QUOTA_STATE_JSON].orEmpty()
    }

    suspend fun saveGeminiQuotaStateJson(jsonString: String) {
        context.blinkDataStore.edit { prefs ->
            if (jsonString.isBlank()) {
                prefs.remove(Keys.GEMINI_QUOTA_STATE_JSON)
            } else {
                prefs[Keys.GEMINI_QUOTA_STATE_JSON] = jsonString
            }
        }
    }

    val settingsFlow: Flow<BlinkSettings> = context.blinkDataStore.data.map { prefs ->
        val dispatchMode = prefs[Keys.DISPATCH_MODE]?.let {
            runCatching { DispatchMode.valueOf(it) }.getOrNull()
        } ?: DispatchMode.SMART_DISPATCH

        val targetAiApp = prefs[Keys.TARGET_AI_APP]?.let {
            runCatching { TargetAiApp.valueOf(it) }.getOrNull()
        } ?: TargetAiApp.SYSTEM_CHOOSER

        val provider = prefs[Keys.AI_PROVIDER]?.let {
            runCatching { AiProvider.valueOf(it) }.getOrNull()
        } ?: AiProvider.GEMINI_CLOUD

        val geminiRoutingMode = prefs[Keys.GEMINI_ROUTING_MODE]?.let {
            runCatching { GeminiRoutingMode.valueOf(it) }.getOrNull()
        } ?: GeminiRoutingMode.AUTO_SMART

        val codingLanguage = prefs[Keys.CODING_LANGUAGE]?.let {
            runCatching { CodingLanguage.valueOf(it) }.getOrNull()
        } ?: CodingLanguage.CPP

        val style = prefs[Keys.ANSWER_STYLE]?.let {
            runCatching { AnswerStyle.valueOf(it) }.getOrNull()
        } ?: AnswerStyle.BALANCED

        val sensitivity = prefs[Keys.CAPTURE_SENSITIVITY]?.let {
            runCatching { CaptureSensitivity.valueOf(it) }.getOrNull()
        } ?: CaptureSensitivity.FAST

        val resolution = prefs[Keys.IMAGE_RESOLUTION]?.let {
            runCatching { ImageResolutionPref.valueOf(it) }.getOrNull()
        } ?: ImageResolutionPref.HIGH_1080P

        // Reuse existing Gemini API key (encrypted store -> legacy plain-text store -> BuildConfig)
        val storedGeminiEncrypted = prefs[Keys.GEMINI_KEY_ENCRYPTED].orEmpty()
        val legacyGeminiPlain = prefs[Keys.LEGACY_GEMINI_KEY].orEmpty()
        val geminiKey = when {
            storedGeminiEncrypted.isNotBlank() -> SecureCredentialCipher.decrypt(storedGeminiEncrypted)
            legacyGeminiPlain.isNotBlank() -> SecureCredentialCipher.decrypt(legacyGeminiPlain)
            else -> resolveBuildConfigGeminiKey()
        }

        val storedGroqEncrypted = prefs[Keys.GROQ_KEY_ENCRYPTED].orEmpty()
        val legacyGroqPlain = prefs[Keys.LEGACY_GROQ_KEY].orEmpty()
        val groqKey = when {
            storedGroqEncrypted.isNotBlank() -> SecureCredentialCipher.decrypt(storedGroqEncrypted)
            legacyGroqPlain.isNotBlank() -> SecureCredentialCipher.decrypt(legacyGroqPlain)
            else -> resolveBuildConfigGroqKey()
        }

        // Enforce default model IDs for the two supported providers
        val rawGeminiModel = prefs[Keys.GEMINI_MODEL]?.trim().orEmpty()
        val validatedGeminiModel = if (GeminiVisionModel.isSupportedModel(rawGeminiModel)) {
            rawGeminiModel
        } else {
            GeminiVisionModel.DEFAULT_MODEL_ID
        }

        val rawGroqModel = prefs[Keys.GROQ_MODEL]?.trim().orEmpty()
        val validatedGroqModel = if (GroqVisionModel.isSupportedVisionModel(rawGroqModel)) {
            rawGroqModel
        } else {
            GroqVisionModel.DEFAULT_MODEL_ID
        }

        val activeFastModel = when (provider) {
            AiProvider.GEMINI_CLOUD -> validatedGeminiModel
            AiProvider.GROQ -> validatedGroqModel
        }

        BlinkSettings(
            dispatchMode = dispatchMode,
            targetAiApp = targetAiApp,
            aiProvider = provider,
            geminiRoutingMode = geminiRoutingMode,
            fastModel = activeFastModel,
            groqModel = validatedGroqModel,
            codingLanguage = codingLanguage,
            answerStyle = style,
            captureSensitivity = sensitivity,
            imageResolution = resolution,
            maxImagesPerSession = (prefs[Keys.MAX_IMAGES] ?: 5).coerceIn(1, 8),
            autoCaptureEnabled = prefs[Keys.AUTO_CAPTURE] ?: true,
            laptopScreenMode = prefs[Keys.LAPTOP_SCREEN_MODE] ?: true,
            startInBlackScreenMode = prefs[Keys.START_BLACK_MODE] ?: false,
            allowBrightnessControl = prefs[Keys.ALLOW_BRIGHTNESS_CONTROL] ?: false,
            customBrightnessLevel = (prefs[Keys.CUSTOM_BRIGHTNESS_LEVEL] ?: 0.25f).coerceIn(0.05f, 1.0f),
            saveSessionHistory = prefs[Keys.SAVE_HISTORY] ?: true,
            defaultPromptId = prefs[Keys.DEFAULT_PROMPT_ID] ?: 1,
            geminiApiKey = geminiKey,
            groqApiKey = groqKey
        )
    }

    suspend fun updateDispatchMode(mode: DispatchMode) {
        context.blinkDataStore.edit { it[Keys.DISPATCH_MODE] = mode.name }
    }

    suspend fun updateTargetAiApp(app: TargetAiApp) {
        context.blinkDataStore.edit { it[Keys.TARGET_AI_APP] = app.name }
    }

    suspend fun updateCodingLanguage(language: CodingLanguage) {
        context.blinkDataStore.edit { it[Keys.CODING_LANGUAGE] = language.name }
    }

    suspend fun updateGeminiRoutingMode(mode: GeminiRoutingMode) {
        context.blinkDataStore.edit { it[Keys.GEMINI_ROUTING_MODE] = mode.name }
    }

    suspend fun updateAiProvider(provider: AiProvider) {
        context.blinkDataStore.edit { prefs ->
            prefs[Keys.AI_PROVIDER] = provider.name
            when (provider) {
                AiProvider.GEMINI_CLOUD -> {
                    val currentGemini = prefs[Keys.GEMINI_MODEL]?.trim().orEmpty()
                    val validGemini = if (GeminiVisionModel.isSupportedModel(currentGemini)) {
                        currentGemini
                    } else {
                        GeminiVisionModel.DEFAULT_MODEL_ID
                    }
                    prefs[Keys.GEMINI_MODEL] = validGemini
                    prefs[Keys.FAST_MODEL] = validGemini
                }
                AiProvider.GROQ -> {
                    val currentGroq = prefs[Keys.GROQ_MODEL]?.trim().orEmpty()
                    val validGroq = if (GroqVisionModel.isSupportedVisionModel(currentGroq)) {
                        currentGroq
                    } else {
                        GroqVisionModel.DEFAULT_MODEL_ID
                    }
                    prefs[Keys.GROQ_MODEL] = validGroq
                    prefs[Keys.FAST_MODEL] = validGroq
                }
            }
        }
    }

    suspend fun updateGeminiModel(modelId: String, switchToManualMode: Boolean = false) {
        val clean = modelId.trim()
        val validated = if (GeminiVisionModel.isSupportedModel(clean)) {
            clean
        } else {
            GeminiVisionModel.DEFAULT_MODEL_ID
        }
        context.blinkDataStore.edit { prefs ->
            prefs[Keys.GEMINI_MODEL] = validated
            if (switchToManualMode) {
                prefs[Keys.GEMINI_ROUTING_MODE] = GeminiRoutingMode.MANUAL_MODEL.name
            }
            if (prefs[Keys.AI_PROVIDER] == null || prefs[Keys.AI_PROVIDER] == AiProvider.GEMINI_CLOUD.name) {
                prefs[Keys.FAST_MODEL] = validated
            }
        }
    }

    suspend fun updateGroqModel(modelId: String) {
        val clean = modelId.trim()
        val validated = if (GroqVisionModel.isSupportedVisionModel(clean)) {
            clean
        } else {
            GroqVisionModel.DEFAULT_MODEL_ID
        }
        context.blinkDataStore.edit { prefs ->
            prefs[Keys.GROQ_MODEL] = validated
            if (prefs[Keys.AI_PROVIDER] == AiProvider.GROQ.name) {
                prefs[Keys.FAST_MODEL] = validated
            }
        }
    }

    suspend fun updateFastModel(model: String) {
        val clean = model.trim()
        context.blinkDataStore.edit { prefs ->
            val currentProvider = prefs[Keys.AI_PROVIDER]?.let {
                runCatching { AiProvider.valueOf(it) }.getOrNull()
            } ?: AiProvider.GEMINI_CLOUD

            if (currentProvider == AiProvider.GEMINI_CLOUD) {
                val validatedGemini = if (GeminiVisionModel.isSupportedModel(clean)) {
                    clean
                } else {
                    GeminiVisionModel.DEFAULT_MODEL_ID
                }
                prefs[Keys.GEMINI_MODEL] = validatedGemini
                prefs[Keys.FAST_MODEL] = validatedGemini
            } else {
                val validated = if (GroqVisionModel.isSupportedVisionModel(clean)) {
                    clean
                } else {
                    GroqVisionModel.DEFAULT_MODEL_ID
                }
                prefs[Keys.GROQ_MODEL] = validated
                prefs[Keys.FAST_MODEL] = validated
            }
        }
    }

    suspend fun updateAnswerStyle(style: AnswerStyle) {
        context.blinkDataStore.edit { it[Keys.ANSWER_STYLE] = style.name }
    }

    suspend fun updateCaptureSensitivity(sensitivity: CaptureSensitivity) {
        context.blinkDataStore.edit { it[Keys.CAPTURE_SENSITIVITY] = sensitivity.name }
    }

    suspend fun updateImageResolution(resolution: ImageResolutionPref) {
        context.blinkDataStore.edit { it[Keys.IMAGE_RESOLUTION] = resolution.name }
    }

    suspend fun updateMaxImages(maxImages: Int) {
        context.blinkDataStore.edit { it[Keys.MAX_IMAGES] = maxImages.coerceIn(1, 8) }
    }

    suspend fun updateAutoCapture(enabled: Boolean) {
        context.blinkDataStore.edit { it[Keys.AUTO_CAPTURE] = enabled }
    }

    suspend fun updateLaptopScreenMode(enabled: Boolean) {
        context.blinkDataStore.edit { it[Keys.LAPTOP_SCREEN_MODE] = enabled }
    }

    suspend fun updateStartInBlackMode(enabled: Boolean) {
        context.blinkDataStore.edit { it[Keys.START_BLACK_MODE] = enabled }
    }

    suspend fun updateAllowBrightnessControl(allowed: Boolean) {
        context.blinkDataStore.edit { it[Keys.ALLOW_BRIGHTNESS_CONTROL] = allowed }
    }

    suspend fun updateCustomBrightnessLevel(level: Float) {
        context.blinkDataStore.edit { it[Keys.CUSTOM_BRIGHTNESS_LEVEL] = level.coerceIn(0.05f, 1.0f) }
    }

    suspend fun updateSaveHistory(enabled: Boolean) {
        context.blinkDataStore.edit { it[Keys.SAVE_HISTORY] = enabled }
    }

    suspend fun updateDefaultPromptId(promptId: Int) {
        context.blinkDataStore.edit { it[Keys.DEFAULT_PROMPT_ID] = promptId }
    }

    suspend fun updateGeminiApiKey(geminiKey: String) {
        context.blinkDataStore.edit { prefs ->
            prefs.remove(Keys.LEGACY_GEMINI_KEY)
            val clean = geminiKey.trim()
            if (clean.isEmpty()) {
                prefs.remove(Keys.GEMINI_KEY_ENCRYPTED)
            } else {
                prefs[Keys.GEMINI_KEY_ENCRYPTED] = SecureCredentialCipher.encrypt(clean)
            }
            prefs[Keys.FAST_MODEL] = BlinkSettings.DEFAULT_GEMINI_MODEL
        }
    }

    suspend fun updateGroqApiKey(groqKey: String) {
        context.blinkDataStore.edit { prefs ->
            prefs.remove(Keys.LEGACY_GROQ_KEY)
            val clean = groqKey.trim()
            if (clean.isEmpty()) {
                prefs.remove(Keys.GROQ_KEY_ENCRYPTED)
            } else {
                prefs[Keys.GROQ_KEY_ENCRYPTED] = SecureCredentialCipher.encrypt(clean)
            }
        }
    }

    suspend fun updateApiKeys(
        gemini: String,
        groq: String
    ) {
        context.blinkDataStore.edit { prefs ->
            prefs.remove(Keys.LEGACY_GEMINI_KEY)
            prefs.remove(Keys.LEGACY_GROQ_KEY)

            val cleanGemini = gemini.trim()
            if (cleanGemini.isEmpty()) {
                prefs.remove(Keys.GEMINI_KEY_ENCRYPTED)
            } else {
                prefs[Keys.GEMINI_KEY_ENCRYPTED] = SecureCredentialCipher.encrypt(cleanGemini)
            }

            val cleanGroq = groq.trim()
            if (cleanGroq.isEmpty()) {
                prefs.remove(Keys.GROQ_KEY_ENCRYPTED)
            } else {
                prefs[Keys.GROQ_KEY_ENCRYPTED] = SecureCredentialCipher.encrypt(cleanGroq)
            }
        }
    }
}
