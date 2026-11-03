package com.deskpet.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * 主题策略（2026-11 调研结论）：
 * - Android 12+ 优先 Material You 动态取色：界面与用户壁纸同源，是当下 Android 的
 *   "默认审美"；桌宠工具类应用没有强品牌色诉求，跟随系统最不出错也最好看
 * - 低版本回退到定制暖橘色调（贴合"宠物"的品牌联想），替代旧版默认紫色
 * - 深色模式完整支持；状态栏透明融入界面，不再用 primary 色块（M2 时代写法）
 */

// —— 定制暖橘色板（fallback，API < 31 使用）——
private val LightColors = lightColorScheme(
    primary = Color(0xFF8B5000),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDCC2),
    onPrimaryContainer = Color(0xFF2E1500),
    secondary = Color(0xFF765848),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDCC2),
    onSecondaryContainer = Color(0xFF2B160A),
    tertiary = Color(0xFF646232),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEBE7B1),
    onTertiaryContainer = Color(0xFF1E1D00),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFFF8F5),
    onBackground = Color(0xFF221A15),
    surface = Color(0xFFFFF8F5),
    onSurface = Color(0xFF221A15),
    surfaceVariant = Color(0xFFF3DFD1),
    onSurfaceVariant = Color(0xFF52443B),
    outline = Color(0xFF85746A),
    outlineVariant = Color(0xFFD8C3B5)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB784),
    onPrimary = Color(0xFF4E2600),
    primaryContainer = Color(0xFF6E3A00),
    onPrimaryContainer = Color(0xFFFFDCC2),
    secondary = Color(0xFFE5BFA7),
    onSecondary = Color(0xFF432B1D),
    secondaryContainer = Color(0xFF5C4132),
    onSecondaryContainer = Color(0xFFFFDCC2),
    tertiary = Color(0xFFCFCB98),
    onTertiary = Color(0xFF343207),
    tertiaryContainer = Color(0xFF4C4A1C),
    onTertiaryContainer = Color(0xFFEBE7B1),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF1A120D),
    onBackground = Color(0xFFF1DFD4),
    surface = Color(0xFF1A120D),
    onSurface = Color(0xFFF1DFD4),
    surfaceVariant = Color(0xFF52443B),
    onSurfaceVariant = Color(0xFFD8C3B5),
    outline = Color(0xFFA08D81),
    outlineVariant = Color(0xFF52443B)
)

@Composable
fun DeskPetTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // 动态取色导致的取色差异不该让截图测试/预览抖动，留开关便于预览固定
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        androidx.compose.runtime.SideEffect {
            val window = (view.context as Activity).window
            // 状态栏透明融入界面（edge-to-edge），图标明暗随主题
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
