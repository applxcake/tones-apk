package com.tones.music.utils

import android.content.Context
import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

object PerformanceUtils {
    
    /**
     * Check if device supports high refresh rate (120Hz+)
     */
    fun isHighRefreshRateDevice(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            val refreshRate = windowManager.defaultDisplay.refreshRate
            refreshRate >= 90f
        } else {
            false
        }
    }
    
    /**
     * Get optimized animation duration based on device refresh rate
     */
    fun getOptimizedAnimationDuration(context: Context, baseDuration: Int): Int {
        return if (isHighRefreshRateDevice(context)) {
            (baseDuration * 0.7).toInt() // Faster animations for high refresh rate
        } else {
            baseDuration
        }
    }
    
    /**
     * Get optimized blur radius based on device performance
     */
    fun getOptimizedBlurRadius(context: Context, baseRadius: Float): Float {
        return if (isHighRefreshRateDevice(context)) {
            baseRadius * 0.5f // Reduced blur for better performance
        } else {
            baseRadius
        }
    }
}

@Composable
fun rememberPerformanceOptimizations(): PerformanceOptimizations {
    val context = LocalContext.current
    val density = LocalDensity.current
    
    return remember {
        PerformanceOptimizations(
            isHighRefreshRate = PerformanceUtils.isHighRefreshRateDevice(context),
            optimizedAnimationDuration = { baseDuration ->
                PerformanceUtils.getOptimizedAnimationDuration(context, baseDuration)
            },
            optimizedBlurRadius = { baseRadius ->
                PerformanceUtils.getOptimizedBlurRadius(context, baseRadius)
            }
        )
    }
}

data class PerformanceOptimizations(
    val isHighRefreshRate: Boolean,
    val optimizedAnimationDuration: (Int) -> Int,
    val optimizedBlurRadius: (Float) -> Float
) 