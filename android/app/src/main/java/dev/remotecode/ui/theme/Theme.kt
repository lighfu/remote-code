package dev.remotecode.ui.theme

import dev.remotecode.i18n.appString
import androidx.compose.ui.res.stringResource

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import dev.remotecode.R
import dev.remotecode.RemoteCodeApp
import dev.remotecode.data.Appearance

@Immutable
data class ProviderColors(val container: Color, val onContainer: Color)

@Immutable
data class ExtraColors(
    val claude: ProviderColors,
    val codex: ProviderColors,
    val diffAdd: Color,
    val diffRemove: Color,
    val success: Color,
)

val LocalExtraColors = staticCompositionLocalOf<ExtraColors> { error("RemoteCodeTheme の外です") }
val LocalCodeFont = staticCompositionLocalOf<FontFamily> { FontFamily.Monospace }
val LocalChatFont = staticCompositionLocalOf<FontFamily> { FontFamily.Default }
val LocalCodeScale = staticCompositionLocalOf { 1f }

val Mono: FontFamily
    @Composable @ReadOnlyComposable get() = LocalCodeFont.current

val CodeScale: Float
    @Composable @ReadOnlyComposable get() = LocalCodeScale.current

val ChatFont: FontFamily
    @Composable @ReadOnlyComposable get() = LocalChatFont.current

private val fontProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

/** 空文字はシステム標準。 */
val BodyFonts: List<Pair<String, String>> get() = listOf(
    "" to appString(R.string.screen_theme_001),
    "Noto Sans JP" to "Noto Sans JP",
    "M PLUS 1p" to "M PLUS 1p",
    "M PLUS Rounded 1c" to "M PLUS Rounded 1c",
    "Zen Maru Gothic" to appString(R.string.screen_theme_002),
    "Zen Kaku Gothic New" to appString(R.string.screen_theme_003),
    "BIZ UDPGothic" to appString(R.string.screen_theme_004),
    "IBM Plex Sans JP" to "IBM Plex Sans JP",
    "Kosugi Maru" to appString(R.string.screen_theme_005),
    "Sawarabi Gothic" to appString(R.string.screen_theme_006),
    "Murecho" to "Murecho",
    "Noto Serif JP" to appString(R.string.screen_theme_007),
    "Zen Old Mincho" to appString(R.string.screen_theme_008),
)

/** 空文字はシステムの等幅。 */
val CodeFonts: List<Pair<String, String>> get() = listOf(
    "" to appString(R.string.screen_theme_009),
    "BIZ UDMincho" to appString(R.string.screen_theme_010),
    "BIZ UDPMincho" to appString(R.string.screen_theme_011),
    "Noto Serif JP" to appString(R.string.screen_theme_007),
    "Shippori Mincho" to appString(R.string.screen_theme_012),
    "Zen Old Mincho" to appString(R.string.screen_theme_008),
    "Sawarabi Mincho" to appString(R.string.screen_theme_013),
    "Klee One" to appString(R.string.screen_theme_014),
    "M PLUS 1 Code" to appString(R.string.screen_theme_015),
    "BIZ UDGothic" to appString(R.string.screen_theme_016),
    "Noto Sans JP" to appString(R.string.screen_theme_017),
    "Zen Kaku Gothic New" to appString(R.string.screen_theme_018),
    "JetBrains Mono" to "JetBrains Mono",
    "Fira Code" to "Fira Code",
    "Source Code Pro" to "Source Code Pro",
    "Roboto Mono" to "Roboto Mono",
    "IBM Plex Mono" to "IBM Plex Mono",
    "Ubuntu Mono" to "Ubuntu Mono",
    "Ubuntu Sans Mono" to "Ubuntu Sans Mono",
    "Inconsolata" to "Inconsolata",
    "Space Mono" to "Space Mono",
    "DM Mono" to "DM Mono",
    "Red Hat Mono" to "Red Hat Mono",
    "Noto Sans Mono" to "Noto Sans Mono",
    "Geist Mono" to "Geist Mono",
    "Martian Mono" to "Martian Mono",
    "Victor Mono" to "Victor Mono",
    "Azeret Mono" to "Azeret Mono",
    "Spline Sans Mono" to "Spline Sans Mono",
    "Chivo Mono" to "Chivo Mono",
    "Fragment Mono" to "Fragment Mono",
    "Sometype Mono" to "Sometype Mono",
    "Reddit Mono" to "Reddit Mono",
    "Atkinson Hyperlegible Mono" to "Atkinson Hyperlegible Mono",
    "Kode Mono" to "Kode Mono",
    "Sono" to "Sono",
    "Overpass Mono" to "Overpass Mono",
    "Cousine" to "Cousine",
    "PT Mono" to "PT Mono",
    "Anonymous Pro" to "Anonymous Pro",
    "B612 Mono" to "B612 Mono",
    "Share Tech Mono" to "Share Tech Mono",
    "Oxygen Mono" to "Oxygen Mono",
    "Courier Prime" to "Courier Prime",
    "Cutive Mono" to "Cutive Mono",
    "Xanh Mono" to "Xanh Mono",
    "Syne Mono" to "Syne Mono",
    "Nova Mono" to "Nova Mono",
)

fun googleFontFamily(name: String, fallback: FontFamily): FontFamily {
    if (name.isBlank()) return fallback
    val g = GoogleFont(name)
    return FontFamily(
        Font(googleFont = g, fontProvider = fontProvider, weight = FontWeight.Normal),
        Font(googleFont = g, fontProvider = fontProvider, weight = FontWeight.Medium),
        Font(googleFont = g, fontProvider = fontProvider, weight = FontWeight.SemiBold),
        Font(googleFont = g, fontProvider = fontProvider, weight = FontWeight.Bold),
    )
}

data class Accent(val id: String, val label: String, val light: List<Long>, val dark: List<Long>)

// 並び: primary, onPrimary, primaryContainer, onPrimaryContainer, secondary, secondaryContainer, onSecondaryContainer, tertiary, tertiaryContainer, onTertiaryContainer
val Accents: List<Accent> get() = listOf(
    Accent("violet", appString(R.string.screen_theme_019),
        listOf(0xFF6750A4, 0xFFFFFFFF, 0xFFEADDFF, 0xFF21005D, 0xFF625B71, 0xFFE8DEF8, 0xFF1D192B, 0xFF7D5260, 0xFFFFD8E4, 0xFF31111D),
        listOf(0xFFD0BCFF, 0xFF381E72, 0xFF4F378B, 0xFFEADDFF, 0xFFCCC2DC, 0xFF4A4458, 0xFFE8DEF8, 0xFFEFB8C8, 0xFF633B48, 0xFFFFD8E4)),
    Accent("blue", appString(R.string.screen_theme_020),
        listOf(0xFF0061A4, 0xFFFFFFFF, 0xFFD1E4FF, 0xFF001D36, 0xFF535F70, 0xFFD7E3F7, 0xFF101C2B, 0xFF6B5778, 0xFFF2DAFF, 0xFF251431),
        listOf(0xFF9ECAFF, 0xFF003258, 0xFF00497D, 0xFFD1E4FF, 0xFFBBC7DB, 0xFF3B4858, 0xFFD7E3F7, 0xFFD6BEE4, 0xFF523F5F, 0xFFF2DAFF)),
    Accent("teal", appString(R.string.screen_theme_021),
        listOf(0xFF006A6A, 0xFFFFFFFF, 0xFF6FF7F6, 0xFF002020, 0xFF4A6363, 0xFFCCE8E7, 0xFF051F1F, 0xFF4B607C, 0xFFD3E4FF, 0xFF041C35),
        listOf(0xFF4CDADA, 0xFF003737, 0xFF004F4F, 0xFF6FF7F6, 0xFFB0CCCB, 0xFF324B4B, 0xFFCCE8E7, 0xFFB3C8E8, 0xFF33485F, 0xFFD3E4FF)),
    Accent("green", appString(R.string.screen_theme_022),
        listOf(0xFF386A20, 0xFFFFFFFF, 0xFFB8F397, 0xFF042100, 0xFF55624C, 0xFFD9E7CB, 0xFF131F0D, 0xFF386666, 0xFFBBEBEB, 0xFF002020),
        listOf(0xFF9DD67D, 0xFF0B3900, 0xFF205107, 0xFFB8F397, 0xFFBDCBB0, 0xFF3E4A36, 0xFFD9E7CB, 0xFFA0CFCF, 0xFF1E4E4E, 0xFFBBEBEB)),
    Accent("orange", appString(R.string.screen_theme_023),
        listOf(0xFF8B5000, 0xFFFFFFFF, 0xFFFFDCBE, 0xFF2C1600, 0xFF725A42, 0xFFFFDCBE, 0xFF291806, 0xFF58633A, 0xFFDCE8B4, 0xFF161E01),
        listOf(0xFFFFB870, 0xFF4A2800, 0xFF693C00, 0xFFFFDCBE, 0xFFE1C1A4, 0xFF59422C, 0xFFFFDCBE, 0xFFC0CC9A, 0xFF404A25, 0xFFDCE8B4)),
    Accent("rose", appString(R.string.screen_theme_024),
        listOf(0xFF984061, 0xFFFFFFFF, 0xFFFFD9E2, 0xFF3E001D, 0xFF74565F, 0xFFFFD9E2, 0xFF2B151C, 0xFF7C5635, 0xFFFFDCC2, 0xFF2E1500),
        listOf(0xFFFFB0C8, 0xFF5E1133, 0xFF7B2949, 0xFFFFD9E2, 0xFFE2BDC6, 0xFF5A3F47, 0xFFFFD9E2, 0xFFEFBD94, 0xFF623F20, 0xFFFFDCC2)),
)

private fun accentScheme(a: Accent, dark: Boolean): ColorScheme {
    val c = (if (dark) a.dark else a.light).map { Color(it) }
    return if (dark) {
        darkColorScheme(
            primary = c[0], onPrimary = c[1], primaryContainer = c[2], onPrimaryContainer = c[3],
            secondary = c[4], secondaryContainer = c[5], onSecondaryContainer = c[6],
            tertiary = c[7], tertiaryContainer = c[8], onTertiaryContainer = c[9],
        )
    } else {
        lightColorScheme(
            primary = c[0], onPrimary = c[1], primaryContainer = c[2], onPrimaryContainer = c[3],
            secondary = c[4], secondaryContainer = c[5], onSecondaryContainer = c[6],
            tertiary = c[7], tertiaryContainer = c[8], onTertiaryContainer = c[9],
        )
    }
}

private fun ColorScheme.amoled(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0B0B0D),
    surfaceContainer = Color(0xFF121214),
    surfaceContainerHigh = Color(0xFF1A1A1D),
    surfaceContainerHighest = Color(0xFF232327),
)

private fun extraColors(scheme: ColorScheme, dark: Boolean): ExtraColors {
    val warm = if (dark) Color(0xFF5C2E1E) else Color(0xFFFFDBCF)
    val onWarm = if (dark) Color(0xFFFFDBCF) else Color(0xFF3A0B00)
    val cool = if (dark) Color(0xFF1F3B4D) else Color(0xFFCDE5FF)
    val onCool = if (dark) Color(0xFFCDE5FF) else Color(0xFF001D31)
    return ExtraColors(
        claude = ProviderColors(scheme.primary.copy(alpha = 0.10f).compositeOver(warm), onWarm),
        codex = ProviderColors(scheme.primary.copy(alpha = 0.10f).compositeOver(cool), onCool),
        diffAdd = if (dark) Color(0xFF7DDB8E) else Color(0xFF1B6E31),
        diffRemove = if (dark) Color(0xFFFF8A80) else Color(0xFFB3261E),
        success = if (dark) Color(0xFF7DDB8E) else Color(0xFF1B6E31),
    )
}

private fun TextStyle.scaled(family: FontFamily?, scale: Float): TextStyle {
    fun TextUnit.s() = if (isSpecified) this * scale else this
    return copy(fontFamily = family ?: fontFamily, fontSize = fontSize.s(), lineHeight = lineHeight.s())
}

private fun typography(family: FontFamily?, scale: Float): Typography {
    val t = Typography()
    return Typography(
        displayLarge = t.displayLarge.scaled(family, scale),
        displayMedium = t.displayMedium.scaled(family, scale),
        displaySmall = t.displaySmall.scaled(family, scale),
        headlineLarge = t.headlineLarge.scaled(family, scale),
        headlineMedium = t.headlineMedium.scaled(family, scale),
        headlineSmall = t.headlineSmall.scaled(family, scale),
        titleLarge = t.titleLarge.scaled(family, scale),
        titleMedium = t.titleMedium.scaled(family, scale),
        titleSmall = t.titleSmall.scaled(family, scale),
        bodyLarge = t.bodyLarge.scaled(family, scale),
        bodyMedium = t.bodyMedium.scaled(family, scale),
        bodySmall = t.bodySmall.scaled(family, scale),
        labelLarge = t.labelLarge.scaled(family, scale),
        labelMedium = t.labelMedium.scaled(family, scale),
        labelSmall = t.labelSmall.scaled(family, scale),
    )
}

@Composable
fun RemoteCodeTheme(content: @Composable () -> Unit) {
    val settings by RemoteCodeApp.instance.repository.settings.collectAsState()
    val a = settings?.appearance ?: Appearance()
    val dark = when (a.themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    var scheme = when {
        a.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        else -> accentScheme(Accents.firstOrNull { it.id == a.accent } ?: Accents[0], dark)
    }
    if (dark && a.amoled) scheme = scheme.amoled()
    val ui = remember(a.uiFont) { if (a.uiFont.isBlank()) null else googleFontFamily(a.uiFont, FontFamily.Default) }
    val body = remember(a.bodyFont) { googleFontFamily(a.bodyFont, FontFamily.Default) }
    val code = remember(a.codeFont) { googleFontFamily(a.codeFont, FontFamily.Monospace) }
    val type = remember(ui, a.textScale) { typography(ui, a.textScale) }
    MaterialExpressiveTheme(colorScheme = scheme, motionScheme = MotionScheme.expressive(), typography = type) {
        CompositionLocalProvider(
            LocalExtraColors provides extraColors(scheme, dark),
            LocalCodeFont provides code,
            LocalChatFont provides body,
            LocalCodeScale provides a.codeScale,
        ) {
            content()
        }
    }
}

object Extra {
    val colors: ExtraColors
        @Composable get() = LocalExtraColors.current
}

@Composable
fun providerColors(provider: String): ProviderColors =
    if (provider == "codex") LocalExtraColors.current.codex else LocalExtraColors.current.claude
