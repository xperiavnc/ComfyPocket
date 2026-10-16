package com.comfyui.mobile.ui.theme

import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/** Fixed brand palette ids (no Monet / wallpaper extraction). */
object ThemePaletteIds {
    const val COMFY_BLUE = "comfy_blue"
    const val JADE_TEAL = "jade_teal"
    const val DUSK_VIOLET = "dusk_violet"
    const val GRAPHITE_AMBER = "graphite_amber"
    const val WARM_ROSE = "warm_rose"
    const val OCEAN_CYAN = "ocean_cyan"
    const val FOREST_OLIVE = "forest_olive"
    const val CORAL_SAND = "coral_sand"
    const val DEFAULT = COMFY_BLUE

    val ALL = listOf(
        COMFY_BLUE, JADE_TEAL, DUSK_VIOLET, GRAPHITE_AMBER,
        WARM_ROSE, OCEAN_CYAN, FOREST_OLIVE, CORAL_SAND
    )

    fun normalize(id: String?): String =
        if (id != null && id in ALL) id else DEFAULT
}

data class ThemePalette(
    val id: String,
    val displayName: String,
    val summary: String,
    val previewLight: Color,
    val previewDark: Color,
    val lightColors: Colors,
    val darkColors: Colors
)

// --- comfy_blue: cleaner brand blue, softer contrast ---
private val ComfyBlueLight = lightColorScheme(
    primary = Color(0xFF3B6FD9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE8FF),
    onPrimaryContainer = Color(0xFF0B1F4A),
    secondary = Color(0xFF5A6478),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFFFFF),
    onSecondaryContainer = Color(0xFF1C1F26),
    secondaryVariant = Color(0xFFF2F4F8),
    onSecondaryVariant = Color(0xFF1C1F26),
    tertiaryContainer = Color(0xFFE8F0FF),
    onTertiaryContainer = Color(0xFF2A56B0),
    background = Color(0xFFF6F7FA),
    onBackground = Color(0xFF1A1D24),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1D24),
    surfaceVariant = Color(0xFFE6EAF2),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFE6EAF2),
    surfaceContainerHighest = Color(0xFFD9DEE9),
    outline = Color(0xFFC2C9D6),
    dividerLine = Color(0xFFD7DCE6),
    error = Color(0xFFE5484D)
)

private val ComfyBlueDark = darkColorScheme(
    primary = Color(0xFF6B9EFF),
    onPrimary = Color(0xFF0A1E45),
    primaryContainer = Color(0xFF243B66),
    onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = Color(0xFFA0AEC2),
    onSecondary = Color(0xFF141820),
    secondaryContainer = Color(0xFF1A2030),
    onSecondaryContainer = Color(0xFFE8EEF7),
    secondaryVariant = Color(0xFF222A3A),
    onSecondaryVariant = Color(0xFFE8EEF7),
    tertiaryContainer = Color(0xFF263552),
    onTertiaryContainer = Color(0xFFB4CBFF),
    background = Color(0xFF0E1117),
    onBackground = Color(0xFFE8EEF7),
    surface = Color(0xFF151A24),
    onSurface = Color(0xFFE8EEF7),
    surfaceVariant = Color(0xFF222B3C),
    surfaceContainer = Color(0xFF171D28),
    surfaceContainerHigh = Color(0xFF222B3C),
    surfaceContainerHighest = Color(0xFF2C364A),
    outline = Color(0xFF3A455A),
    dividerLine = Color(0xFF222A3A),
    error = Color(0xFFFF6B6B)
)

// --- jade_teal ---
private val JadeTealLight = lightColorScheme(
    primary = Color(0xFF0F8F84),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD0F5EF),
    onPrimaryContainer = Color(0xFF053330),
    secondary = Color(0xFF4F635F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFFFFF),
    onSecondaryContainer = Color(0xFF1A2422),
    secondaryVariant = Color(0xFFEFF6F4),
    onSecondaryVariant = Color(0xFF1A2422),
    tertiaryContainer = Color(0xFFD5F5E8),
    onTertiaryContainer = Color(0xFF0B5C4C),
    background = Color(0xFFF4F8F7),
    onBackground = Color(0xFF15201E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF15201E),
    surfaceVariant = Color(0xFFDDE8E4),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFDDE8E4),
    surfaceContainerHighest = Color(0xFFCEDCD7),
    outline = Color(0xFFB2C5BF),
    dividerLine = Color(0xFFD1DED9),
    error = Color(0xFFE5484D)
)

private val JadeTealDark = darkColorScheme(
    primary = Color(0xFF5ECFBF),
    onPrimary = Color(0xFF00352F),
    primaryContainer = Color(0xFF1A4F49),
    onPrimaryContainer = Color(0xFFD0F5EF),
    secondary = Color(0xFFA3B8B2),
    onSecondary = Color(0xFF0F1A18),
    secondaryContainer = Color(0xFF162420),
    onSecondaryContainer = Color(0xFFE6F2EF),
    secondaryVariant = Color(0xFF1E2E2A),
    onSecondaryVariant = Color(0xFFE6F2EF),
    tertiaryContainer = Color(0xFF1F3F38),
    onTertiaryContainer = Color(0xFFA8E6D8),
    background = Color(0xFF0E1211),
    onBackground = Color(0xFFE6F2EF),
    surface = Color(0xFF151C1A),
    onSurface = Color(0xFFE6F2EF),
    surfaceVariant = Color(0xFF212E2A),
    surfaceContainer = Color(0xFF171F1C),
    surfaceContainerHigh = Color(0xFF212E2A),
    surfaceContainerHighest = Color(0xFF2B3A35),
    outline = Color(0xFF3A4A45),
    dividerLine = Color(0xFF222E2A),
    error = Color(0xFFFF6B6B)
)

// --- dusk_violet ---
private val DuskVioletLight = lightColorScheme(
    primary = Color(0xFF6D4ED1),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9E0FF),
    onPrimaryContainer = Color(0xFF250F5C),
    secondary = Color(0xFF635B74),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFFFFF),
    onSecondaryContainer = Color(0xFF1E1A26),
    secondaryVariant = Color(0xFFF4F1F8),
    onSecondaryVariant = Color(0xFF1E1A26),
    tertiaryContainer = Color(0xFFF0E7FF),
    onTertiaryContainer = Color(0xFF4C2FA8),
    background = Color(0xFFF7F5FA),
    onBackground = Color(0xFF1C1824),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1824),
    surfaceVariant = Color(0xFFE8E3F0),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFE8E3F0),
    surfaceContainerHighest = Color(0xFFDBD4E6),
    outline = Color(0xFFC5BDCF),
    dividerLine = Color(0xFFD8D2E0),
    error = Color(0xFFE5484D)
)

private val DuskVioletDark = darkColorScheme(
    primary = Color(0xFFB39DFF),
    onPrimary = Color(0xFF2A1560),
    primaryContainer = Color(0xFF3D2D6B),
    onPrimaryContainer = Color(0xFFE9E0FF),
    secondary = Color(0xFFB0A6C2),
    onSecondary = Color(0xFF16121E),
    secondaryContainer = Color(0xFF1C1828),
    onSecondaryContainer = Color(0xFFF0EBF8),
    secondaryVariant = Color(0xFF262033),
    onSecondaryVariant = Color(0xFFF0EBF8),
    tertiaryContainer = Color(0xFF32284A),
    onTertiaryContainer = Color(0xFFD2C4FF),
    background = Color(0xFF100E16),
    onBackground = Color(0xFFF0EBF8),
    surface = Color(0xFF17141F),
    onSurface = Color(0xFFF0EBF8),
    surfaceVariant = Color(0xFF242030),
    surfaceContainer = Color(0xFF1A1622),
    surfaceContainerHigh = Color(0xFF242030),
    surfaceContainerHighest = Color(0xFF302B3E),
    outline = Color(0xFF3F3850),
    dividerLine = Color(0xFF262033),
    error = Color(0xFFFF6B6B)
)

// --- graphite_amber ---
private val GraphiteAmberLight = lightColorScheme(
    primary = Color(0xFFC47B16),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE8C2),
    onPrimaryContainer = Color(0xFF3D2500),
    secondary = Color(0xFF5F5A52),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFFFFF),
    onSecondaryContainer = Color(0xFF1C1A16),
    secondaryVariant = Color(0xFFF5F3EF),
    onSecondaryVariant = Color(0xFF1C1A16),
    tertiaryContainer = Color(0xFFFFF0D6),
    onTertiaryContainer = Color(0xFF8A5500),
    background = Color(0xFFF7F6F3),
    onBackground = Color(0xFF1A1814),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1814),
    surfaceVariant = Color(0xFFE8E4DC),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFE8E4DC),
    surfaceContainerHighest = Color(0xFFDBD6CC),
    outline = Color(0xFFC5BDB0),
    dividerLine = Color(0xFFD8D2C8),
    error = Color(0xFFE5484D)
)

private val GraphiteAmberDark = darkColorScheme(
    primary = Color(0xFFE0A84A),
    onPrimary = Color(0xFF3A2500),
    primaryContainer = Color(0xFF5C4318),
    onPrimaryContainer = Color(0xFFFFE8C2),
    secondary = Color(0xFFB3AAA0),
    onSecondary = Color(0xFF161410),
    secondaryContainer = Color(0xFF1C1A16),
    onSecondaryContainer = Color(0xFFF2EEE6),
    secondaryVariant = Color(0xFF27241F),
    onSecondaryVariant = Color(0xFFF2EEE6),
    tertiaryContainer = Color(0xFF3A3220),
    onTertiaryContainer = Color(0xFFF0C878),
    background = Color(0xFF100F0D),
    onBackground = Color(0xFFF2EEE6),
    surface = Color(0xFF171614),
    onSurface = Color(0xFFF2EEE6),
    surfaceVariant = Color(0xFF25221D),
    surfaceContainer = Color(0xFF1A1815),
    surfaceContainerHigh = Color(0xFF25221D),
    surfaceContainerHighest = Color(0xFF322E28),
    outline = Color(0xFF433E36),
    dividerLine = Color(0xFF27241F),
    error = Color(0xFFFF6B6B)
)

// --- warm_rose: soft rose / blush ---
private val WarmRoseLight = lightColorScheme(
    primary = Color(0xFFC45B7A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9E4),
    onPrimaryContainer = Color(0xFF4A1024),
    secondary = Color(0xFF735560),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFFFFF),
    onSecondaryContainer = Color(0xFF24181C),
    secondaryVariant = Color(0xFFF8F0F3),
    onSecondaryVariant = Color(0xFF24181C),
    tertiaryContainer = Color(0xFFFFE8EF),
    onTertiaryContainer = Color(0xFF9A2F52),
    background = Color(0xFFFAF5F7),
    onBackground = Color(0xFF22181C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF22181C),
    surfaceVariant = Color(0xFFF0E2E7),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF0E2E7),
    surfaceContainerHighest = Color(0xFFE4D2D9),
    outline = Color(0xFFCFB8C1),
    dividerLine = Color(0xFFE2D4DA),
    error = Color(0xFFE5484D)
)

private val WarmRoseDark = darkColorScheme(
    primary = Color(0xFFF0A0B6),
    onPrimary = Color(0xFF4A1528),
    primaryContainer = Color(0xFF6B3044),
    onPrimaryContainer = Color(0xFFFFD9E4),
    secondary = Color(0xFFC4A8B2),
    onSecondary = Color(0xFF1A1216),
    secondaryContainer = Color(0xFF241820),
    onSecondaryContainer = Color(0xFFF8EEF2),
    secondaryVariant = Color(0xFF2E2228),
    onSecondaryVariant = Color(0xFFF8EEF2),
    tertiaryContainer = Color(0xFF3E2530),
    onTertiaryContainer = Color(0xFFFFC2D4),
    background = Color(0xFF130E11),
    onBackground = Color(0xFFF8EEF2),
    surface = Color(0xFF1B1518),
    onSurface = Color(0xFFF8EEF2),
    surfaceVariant = Color(0xFF281F24),
    surfaceContainer = Color(0xFF1E171B),
    surfaceContainerHigh = Color(0xFF281F24),
    surfaceContainerHighest = Color(0xFF352930),
    outline = Color(0xFF4A3A42),
    dividerLine = Color(0xFF2E2228),
    error = Color(0xFFFF6B6B)
)

// --- ocean_cyan: cool sea / sky ---
private val OceanCyanLight = lightColorScheme(
    primary = Color(0xFF1A8FB5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC8ECF8),
    onPrimaryContainer = Color(0xFF003344),
    secondary = Color(0xFF4F6470),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFFFFF),
    onSecondaryContainer = Color(0xFF172028),
    secondaryVariant = Color(0xFFEEF5F8),
    onSecondaryVariant = Color(0xFF172028),
    tertiaryContainer = Color(0xFFD6F2FA),
    onTertiaryContainer = Color(0xFF0A6A88),
    background = Color(0xFFF3F8FA),
    onBackground = Color(0xFF152028),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF152028),
    surfaceVariant = Color(0xFFDCE8EE),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFDCE8EE),
    surfaceContainerHighest = Color(0xFFCDDBE4),
    outline = Color(0xFFB0C2CC),
    dividerLine = Color(0xFFD0DCE4),
    error = Color(0xFFE5484D)
)

private val OceanCyanDark = darkColorScheme(
    primary = Color(0xFF6CC8E8),
    onPrimary = Color(0xFF003546),
    primaryContainer = Color(0xFF1A4F62),
    onPrimaryContainer = Color(0xFFC8ECF8),
    secondary = Color(0xFFA3B8C4),
    onSecondary = Color(0xFF0E161C),
    secondaryContainer = Color(0xFF162028),
    onSecondaryContainer = Color(0xFFE6F2F8),
    secondaryVariant = Color(0xFF1E2C36),
    onSecondaryVariant = Color(0xFFE6F2F8),
    tertiaryContainer = Color(0xFF1F3A48),
    onTertiaryContainer = Color(0xFFA8DFF2),
    background = Color(0xFF0C1115),
    onBackground = Color(0xFFE6F2F8),
    surface = Color(0xFF141B21),
    onSurface = Color(0xFFE6F2F8),
    surfaceVariant = Color(0xFF1E2830),
    surfaceContainer = Color(0xFF161E24),
    surfaceContainerHigh = Color(0xFF1E2830),
    surfaceContainerHighest = Color(0xFF283440),
    outline = Color(0xFF3A4A55),
    dividerLine = Color(0xFF222E36),
    error = Color(0xFFFF6B6B)
)

// --- forest_olive: muted green / olive ---
private val ForestOliveLight = lightColorScheme(
    primary = Color(0xFF5F7A3E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE8C8),
    onPrimaryContainer = Color(0xFF1A2A08),
    secondary = Color(0xFF5C6354),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFFFFF),
    onSecondaryContainer = Color(0xFF1C2018),
    secondaryVariant = Color(0xFFF2F4EC),
    onSecondaryVariant = Color(0xFF1C2018),
    tertiaryContainer = Color(0xFFE8F0D4),
    onTertiaryContainer = Color(0xFF3F5A22),
    background = Color(0xFFF6F7F2),
    onBackground = Color(0xFF1A1E16),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1E16),
    surfaceVariant = Color(0xFFE4E8DA),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFE4E8DA),
    surfaceContainerHighest = Color(0xFFD6DCCA),
    outline = Color(0xFFB8C0AA),
    dividerLine = Color(0xFFD4DBC8),
    error = Color(0xFFE5484D)
)

private val ForestOliveDark = darkColorScheme(
    primary = Color(0xFFA8C47A),
    onPrimary = Color(0xFF243510),
    primaryContainer = Color(0xFF3E5228),
    onPrimaryContainer = Color(0xFFDCE8C8),
    secondary = Color(0xFFB0B8A4),
    onSecondary = Color(0xFF141810),
    secondaryContainer = Color(0xFF1A1E16),
    onSecondaryContainer = Color(0xFFEEF2E6),
    secondaryVariant = Color(0xFF262A20),
    onSecondaryVariant = Color(0xFFEEF2E6),
    tertiaryContainer = Color(0xFF2E3820),
    onTertiaryContainer = Color(0xFFC4DCA0),
    background = Color(0xFF0F110D),
    onBackground = Color(0xFFEEF2E6),
    surface = Color(0xFF161914),
    onSurface = Color(0xFFEEF2E6),
    surfaceVariant = Color(0xFF21261C),
    surfaceContainer = Color(0xFF181C15),
    surfaceContainerHigh = Color(0xFF21261C),
    surfaceContainerHighest = Color(0xFF2C3226),
    outline = Color(0xFF3E4536),
    dividerLine = Color(0xFF262A20),
    error = Color(0xFFFF6B6B)
)

// --- coral_sand: warm coral / sand ---
private val CoralSandLight = lightColorScheme(
    primary = Color(0xFFD06B4E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE0D4),
    onPrimaryContainer = Color(0xFF3D1608),
    secondary = Color(0xFF6E5A52),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFFFFF),
    onSecondaryContainer = Color(0xFF241C18),
    secondaryVariant = Color(0xFFF8F2EE),
    onSecondaryVariant = Color(0xFF241C18),
    tertiaryContainer = Color(0xFFFFEBE2),
    onTertiaryContainer = Color(0xFFA04830),
    background = Color(0xFFFAF6F3),
    onBackground = Color(0xFF221C18),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF221C18),
    surfaceVariant = Color(0xFFEFE4DC),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEFE4DC),
    surfaceContainerHighest = Color(0xFFE2D4CA),
    outline = Color(0xFFCDBAB0),
    dividerLine = Color(0xFFE2D6CE),
    error = Color(0xFFE5484D)
)

private val CoralSandDark = darkColorScheme(
    primary = Color(0xFFE8A08A),
    onPrimary = Color(0xFF3D180C),
    primaryContainer = Color(0xFF6B3A2A),
    onPrimaryContainer = Color(0xFFFFE0D4),
    secondary = Color(0xFFC0AEA4),
    onSecondary = Color(0xFF181410),
    secondaryContainer = Color(0xFF1E1A16),
    onSecondaryContainer = Color(0xFFF6EEE8),
    secondaryVariant = Color(0xFF2A2420),
    onSecondaryVariant = Color(0xFFF6EEE8),
    tertiaryContainer = Color(0xFF3A2A22),
    onTertiaryContainer = Color(0xFFF0C0B0),
    background = Color(0xFF12100E),
    onBackground = Color(0xFFF6EEE8),
    surface = Color(0xFF1A1714),
    onSurface = Color(0xFFF6EEE8),
    surfaceVariant = Color(0xFF25201C),
    surfaceContainer = Color(0xFF1C1916),
    surfaceContainerHigh = Color(0xFF25201C),
    surfaceContainerHighest = Color(0xFF322C26),
    outline = Color(0xFF463C36),
    dividerLine = Color(0xFF2A2420),
    error = Color(0xFFFF6B6B)
)

val ThemePalettes: List<ThemePalette> = listOf(
    ThemePalette(
        id = ThemePaletteIds.COMFY_BLUE,
        displayName = "默认蓝",
        summary = "清爽品牌蓝，适合日常使用",
        previewLight = Color(0xFF3B6FD9),
        previewDark = Color(0xFF6B9EFF),
        lightColors = ComfyBlueLight,
        darkColors = ComfyBlueDark
    ),
    ThemePalette(
        id = ThemePaletteIds.JADE_TEAL,
        displayName = "青玉",
        summary = "青绿科技感，偏生成/工具场景",
        previewLight = Color(0xFF0F8F84),
        previewDark = Color(0xFF5ECFBF),
        lightColors = JadeTealLight,
        darkColors = JadeTealDark
    ),
    ThemePalette(
        id = ThemePaletteIds.DUSK_VIOLET,
        displayName = "暮紫",
        summary = "柔和紫调，偏创作氛围",
        previewLight = Color(0xFF6D4ED1),
        previewDark = Color(0xFFB39DFF),
        lightColors = DuskVioletLight,
        darkColors = DuskVioletDark
    ),
    ThemePalette(
        id = ThemePaletteIds.GRAPHITE_AMBER,
        displayName = "石墨琥珀",
        summary = "暖琥珀强调，对比清晰耐看",
        previewLight = Color(0xFFC47B16),
        previewDark = Color(0xFFE0A84A),
        lightColors = GraphiteAmberLight,
        darkColors = GraphiteAmberDark
    ),
    ThemePalette(
        id = ThemePaletteIds.WARM_ROSE,
        displayName = "暖玫",
        summary = "柔和玫瑰粉，偏轻松氛围",
        previewLight = Color(0xFFC45B7A),
        previewDark = Color(0xFFF0A0B6),
        lightColors = WarmRoseLight,
        darkColors = WarmRoseDark
    ),
    ThemePalette(
        id = ThemePaletteIds.OCEAN_CYAN,
        displayName = "海青",
        summary = "清凉海青，偏冷静工具感",
        previewLight = Color(0xFF1A8FB5),
        previewDark = Color(0xFF6CC8E8),
        lightColors = OceanCyanLight,
        darkColors = OceanCyanDark
    ),
    ThemePalette(
        id = ThemePaletteIds.FOREST_OLIVE,
        displayName = "森绿",
        summary = "橄榄森绿，偏沉稳阅读感",
        previewLight = Color(0xFF5F7A3E),
        previewDark = Color(0xFFA8C47A),
        lightColors = ForestOliveLight,
        darkColors = ForestOliveDark
    ),
    ThemePalette(
        id = ThemePaletteIds.CORAL_SAND,
        displayName = "珊瑚沙",
        summary = "暖珊瑚沙，偏温柔对比",
        previewLight = Color(0xFFD06B4E),
        previewDark = Color(0xFFE8A08A),
        lightColors = CoralSandLight,
        darkColors = CoralSandDark
    )
)

fun themePaletteById(id: String?): ThemePalette {
    val normalized = ThemePaletteIds.normalize(id)
    return ThemePalettes.first { it.id == normalized }
}
