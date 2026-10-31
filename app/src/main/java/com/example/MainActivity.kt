package com.example

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.BlinkMainScreen
import com.example.ui.BlinkViewModel
import com.example.ui.theme.BlinkAITheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        hideSystemStatusBar()

        // Initialize ultra-minimal camera-first UI
        setContent {
            var isBlackMode by remember { mutableStateOf(false) }

            BlinkAITheme(isBlackScreenMode = isBlackMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val blinkViewModel: BlinkViewModel = viewModel()
                    BlinkMainScreen(
                        viewModel = blinkViewModel,
                        onBrightnessStateChanged = { blackScreenActive, allowBrightnessControl, customBrightnessLevel ->
                            isBlackMode = blackScreenActive
                            updateWindowBrightness(
                                isBlackScreenMode = blackScreenActive,
                                allowBrightnessControl = allowBrightnessControl,
                                customBrightnessLevel = customBrightnessLevel
                            )
                            hideSystemStatusBar()
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemStatusBar()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemStatusBar()
        }
    }

    /**
     * Hides the top Android system status bar (battery, time, notification icons)
     * for an unobstructed, stealth full-screen experience. Swiping from the top
     * only transiently reveals the bar before auto-hiding again.
     */
    private fun hideSystemStatusBar() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val lp = window.attributes
                if (lp.layoutInDisplayCutoutMode != WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES) {
                    lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    window.attributes = lp
                }
            }
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.statusBars())
        }
    }

    /**
     * Never reduce hardware display brightness without explicit user permission.
     * Only applies customBrightnessLevel when BOTH isBlackScreenMode AND allowBrightnessControl are true.
     */
    private fun updateWindowBrightness(
        isBlackScreenMode: Boolean,
        allowBrightnessControl: Boolean,
        customBrightnessLevel: Float
    ) {
        val layoutParams = window.attributes
        layoutParams.screenBrightness = if (isBlackScreenMode && allowBrightnessControl) {
            customBrightnessLevel.coerceIn(0.05f, 1.0f)
        } else {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        window.attributes = layoutParams
    }
}

