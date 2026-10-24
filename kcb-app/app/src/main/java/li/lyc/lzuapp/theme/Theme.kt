package li.lyc.lzuapp.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** 主题模式：跟随系统 / 浅色 / 深色 */
enum class ThemeMode(val storageValue: String) {
    SYSTEM("system"), LIGHT("light"), DARK("dark");

    companion object {
        // 默认浅色（用户在「设置」里可改为跟随系统/深色）
        fun fromStorage(value: String?): ThemeMode =
            entries.firstOrNull { it.storageValue == value } ?: LIGHT
    }
}

/** 主题配色：蓝 / 粉 / 绿 / 灰（低饱和，默认蓝） */
enum class ThemeColor(val storageValue: String, val label: String) {
    BLUE("blue", "蓝色"),
    PINK("pink", "粉色"),
    GREEN("green", "绿色"),
    GRAY("gray", "灰色");

    companion object {
        fun fromStorage(value: String?): ThemeColor =
            entries.firstOrNull { it.storageValue == value } ?: BLUE
    }
}

// ---------------- 浅蓝 ----------------

private val BlueLight = lightColorScheme(
    primary = Color(0xFF3373A8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCDE2F7),
    onPrimaryContainer = Color(0xFF0B1E2E),
    secondary = Color(0xFF4F6172),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD2E3F0),
    onSecondaryContainer = Color(0xFF0F1B25),
    tertiary = Color(0xFF5C6B77),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDFE6EC),
    onTertiaryContainer = Color(0xFF182229),
    background = Color(0xFFF5F9FD),
    onBackground = Color(0xFF191C1F),
    surface = Color(0xFFF5F9FD),
    onSurface = Color(0xFF191C1F),
    surfaceVariant = Color(0xFFD8E3ED),
    onSurfaceVariant = Color(0xFF3F4850),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEDF3FA),
    surfaceContainer = Color(0xFFE5EEF7),
    surfaceContainerHigh = Color(0xFFDEE9F3),
    surfaceContainerHighest = Color(0xFFD6E2EE),
    outline = Color(0xFF707981),
    outlineVariant = Color(0xFFBFC8D1),
)

private val BlueDark = darkColorScheme(
    primary = Color(0xFFA6CCF3),
    onPrimary = Color(0xFF0C2C44),
    primaryContainer = Color(0xFF2A5178),
    onPrimaryContainer = Color(0xFFCDE2F7),
    secondary = Color(0xFFB9C8D4),
    onSecondary = Color(0xFF24343F),
    secondaryContainer = Color(0xFF3A4A57),
    onSecondaryContainer = Color(0xFFD2E3F0),
    tertiary = Color(0xFFBEC9D2),
    onTertiary = Color(0xFF28333B),
    tertiaryContainer = Color(0xFF3F4952),
    onTertiaryContainer = Color(0xFFDFE6EC),
    background = Color(0xFF0D141C),
    onBackground = Color(0xFFDFE3E8),
    surface = Color(0xFF0D141C),
    onSurface = Color(0xFFDFE3E8),
    surfaceVariant = Color(0xFF3F4850),
    onSurfaceVariant = Color(0xFFBFC8D1),
    surfaceContainerLowest = Color(0xFF080F16),
    surfaceContainerLow = Color(0xFF151D27),
    surfaceContainer = Color(0xFF1A222D),
    surfaceContainerHigh = Color(0xFF242D3A),
    surfaceContainerHighest = Color(0xFF2E3847),
    outline = Color(0xFF89939C),
    outlineVariant = Color(0xFF3F4850),
)

// ---------------- 浅粉 ----------------

private val PinkLight = lightColorScheme(
    primary = Color(0xFFA94F63),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF9DCE3),
    onPrimaryContainer = Color(0xFF2C1218),
    secondary = Color(0xFF6A5257),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF3DDE2),
    onSecondaryContainer = Color(0xFF241417),
    tertiary = Color(0xFF6C5B5D),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEDE2E3),
    onTertiaryContainer = Color(0xFF251A1B),
    background = Color(0xFFFDF6F7),
    onBackground = Color(0xFF1C1A1A),
    surface = Color(0xFFFDF6F7),
    onSurface = Color(0xFF1C1A1A),
    surfaceVariant = Color(0xFFEFDDE1),
    onSurfaceVariant = Color(0xFF4E4345),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAEEF0),
    surfaceContainer = Color(0xFFF6E8EB),
    surfaceContainerHigh = Color(0xFFF1E2E6),
    surfaceContainerHighest = Color(0xFFEDDCE1),
    outline = Color(0xFF7F7375),
    outlineVariant = Color(0xFFD2C6C7),
)

private val PinkDark = darkColorScheme(
    primary = Color(0xFFF2AAB9),
    onPrimary = Color(0xFF411C24),
    primaryContainer = Color(0xFF7B3B4D),
    onPrimaryContainer = Color(0xFFF9DCE3),
    secondary = Color(0xFFD1BEC1),
    onSecondary = Color(0xFF37292C),
    secondaryContainer = Color(0xFF4E3F42),
    onSecondaryContainer = Color(0xFFF3DDE2),
    tertiary = Color(0xFFCDBEBF),
    onTertiary = Color(0xFF372C2D),
    tertiaryContainer = Color(0xFF504344),
    onTertiaryContainer = Color(0xFFEDE2E3),
    background = Color(0xFF1A0F14),
    onBackground = Color(0xFFE4DFDF),
    surface = Color(0xFF1A0F14),
    onSurface = Color(0xFFE4DFDF),
    surfaceVariant = Color(0xFF4E4345),
    onSurfaceVariant = Color(0xFFD2C6C7),
    surfaceContainerLowest = Color(0xFF140A0E),
    surfaceContainerLow = Color(0xFF24171E),
    surfaceContainer = Color(0xFF291B22),
    surfaceContainerHigh = Color(0xFF34252C),
    surfaceContainerHighest = Color(0xFF403037),
    outline = Color(0xFF9B8E90),
    outlineVariant = Color(0xFF4E4345),
)

// ---------------- 绿色 ----------------

private val GreenLight = lightColorScheme(
    primary = Color(0xFF248A56),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFBDF0D2),
    onPrimaryContainer = Color(0xFF0D2B1D),
    secondary = Color(0xFF4A6853),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC6EDD2),
    onSecondaryContainer = Color(0xFF0E1F14),
    tertiary = Color(0xFF5B7370),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDCE9E6),
    onTertiaryContainer = Color(0xFF14211F),
    background = Color(0xFFF2FCF5),
    onBackground = Color(0xFF181D1A),
    surface = Color(0xFFF2FCF5),
    onSurface = Color(0xFF181D1A),
    surfaceVariant = Color(0xFFD2E8D6),
    onSurfaceVariant = Color(0xFF3F4A43),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFE9F7EC),
    surfaceContainer = Color(0xFFE2F2E5),
    surfaceContainerHigh = Color(0xFFDCEDDF),
    surfaceContainerHighest = Color(0xFFD3E8D8),
    outline = Color(0xFF6F7A72),
    outlineVariant = Color(0xFFBEC9C1),
)

private val GreenDark = darkColorScheme(
    primary = Color(0xFF8FE0B0),
    onPrimary = Color(0xFF0C3521),
    primaryContainer = Color(0xFF1E6B40),
    onPrimaryContainer = Color(0xFFBDF0D2),
    secondary = Color(0xFFB2CCBA),
    onSecondary = Color(0xFF203427),
    secondaryContainer = Color(0xFF364B3D),
    onSecondaryContainer = Color(0xFFC6EDD2),
    tertiary = Color(0xFFAFC9C5),
    onTertiary = Color(0xFF233331),
    tertiaryContainer = Color(0xFF3A4A48),
    onTertiaryContainer = Color(0xFFDCE9E6),
    background = Color(0xFF0C1710),
    onBackground = Color(0xFFDEE4DF),
    surface = Color(0xFF0C1710),
    onSurface = Color(0xFFDEE4DF),
    surfaceVariant = Color(0xFF3D4E42),
    onSurfaceVariant = Color(0xFFBEC9C1),
    surfaceContainerLowest = Color(0xFF071209),
    surfaceContainerLow = Color(0xFF132016),
    surfaceContainer = Color(0xFF18251B),
    surfaceContainerHigh = Color(0xFF222F25),
    surfaceContainerHighest = Color(0xFF2C3A2E),
    outline = Color(0xFF8A938D),
    outlineVariant = Color(0xFF3D4E42),
)

// ---------------- 灰色 ----------------

private val GrayLight = lightColorScheme(
    primary = Color(0xFF343538),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE2E2E4),
    onPrimaryContainer = Color(0xFF191A1C),
    secondary = Color(0xFF5C5D5F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE1E1E3),
    onSecondaryContainer = Color(0xFF191A1B),
    tertiary = Color(0xFF606164),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE5E5E8),
    onTertiaryContainer = Color(0xFF1D1E20),
    background = Color(0xFFF9F9FA),
    onBackground = Color(0xFF1A1B1C),
    surface = Color(0xFFF9F9FA),
    onSurface = Color(0xFF1A1B1C),
    surfaceVariant = Color(0xFFE3E3E5),
    onSurfaceVariant = Color(0xFF464749),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F1F2),
    surfaceContainer = Color(0xFFEBEBED),
    surfaceContainerHigh = Color(0xFFE5E5E8),
    surfaceContainerHighest = Color(0xFFDFDFE2),
    outline = Color(0xFF76777A),
    outlineVariant = Color(0xFFC6C6C9),
)

private val GrayDark = darkColorScheme(
    primary = Color(0xFFC4C5C8),
    onPrimary = Color(0xFF2C2D2F),
    primaryContainer = Color(0xFF48494C),
    onPrimaryContainer = Color(0xFFE2E2E4),
    secondary = Color(0xFFC0C1C3),
    onSecondary = Color(0xFF2B2C2D),
    secondaryContainer = Color(0xFF454648),
    onSecondaryContainer = Color(0xFFE1E1E3),
    tertiary = Color(0xFFC2C3C6),
    onTertiary = Color(0xFF2C2D30),
    tertiaryContainer = Color(0xFF47484B),
    onTertiaryContainer = Color(0xFFE5E5E8),
    background = Color(0xFF111112),
    onBackground = Color(0xFFE2E2E4),
    surface = Color(0xFF111112),
    onSurface = Color(0xFFE2E2E4),
    surfaceVariant = Color(0xFF464749),
    onSurfaceVariant = Color(0xFFC6C6C9),
    surfaceContainerLowest = Color(0xFF0C0C0D),
    surfaceContainerLow = Color(0xFF19191B),
    surfaceContainer = Color(0xFF1D1D1F),
    surfaceContainerHigh = Color(0xFF28282A),
    surfaceContainerHighest = Color(0xFF333336),
    outline = Color(0xFF909094),
    outlineVariant = Color(0xFF464749),
)

fun colorSchemeFor(themeColor: ThemeColor, dark: Boolean): ColorScheme = when (themeColor) {
    ThemeColor.BLUE -> if (dark) BlueDark else BlueLight
    ThemeColor.PINK -> if (dark) PinkDark else PinkLight
    ThemeColor.GREEN -> if (dark) GreenDark else GreenLight
    ThemeColor.GRAY -> if (dark) GrayDark else GrayLight
}

@Composable
fun KcbTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    themeColor: ThemeColor = ThemeColor.BLUE,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    // 手动覆盖系统深色时，ComponentActivity 的 enableEdgeToEdge(auto) 只跟随系统 uiMode，
    // 这里按 App 实际主题修正系统栏图标颜色，保证状态栏时间/电量可读。
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorSchemeFor(themeColor, darkTheme),
        content = content,
    )
}
