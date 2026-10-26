package com.grulla.music.ui

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * "Crane & Ink": white paper, black ink and one red crown, after the red-crowned crane.
 * Crown red means sound (what the app plays), indigo means you (your loop, your
 * selection, the notes Follow me waits for), amber means attention.
 */

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** The user's appearance choice (Settings), persisted. */
class AppearanceSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val mutableMode = MutableStateFlow(
        prefs.getString(KEY_MODE, null)?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
    )
    val mode: StateFlow<ThemeMode> = mutableMode.asStateFlow()

    fun setMode(mode: ThemeMode) {
        prefs.edit { putString(KEY_MODE, mode.name) }
        mutableMode.value = mode
    }

    private companion object {
        const val KEY_MODE = "theme_mode"
    }
}

@Immutable
data class GrullaColors(
    val night: Boolean,
    /** App background around the score and lists. */
    val paper: Color,
    /** Cards, the dock, the score page. */
    val sheet: Color,
    val ink: Color,
    val graphite: Color,
    val line: Color,
    val lineStrong: Color,
    /** Neutral fill for unselected chips and tags. */
    val tag: Color,
    val crown: Color,
    val onCrown: Color,
    val crownHalo: Color,
    val indigo: Color,
    val onIndigo: Color,
    val indigoTint: Color,
    val onIndigoTint: Color,
    val indigoHalo: Color,
    val amber: Color,
    val amberContainer: Color,
    val onAmberContainer: Color,
    val track: Color,
)

val DayColors = GrullaColors(
    night = false,
    paper = Color(0xFFF4F2EE),
    sheet = Color(0xFFFFFFFF),
    ink = Color(0xFF15181C),
    graphite = Color(0xFF545A62),
    line = Color(0xFFDCD6CC),
    lineStrong = Color(0xFFCFC8BC),
    tag = Color(0xFFF1EDE6),
    crown = Color(0xFFC4302B),
    onCrown = Color(0xFFFFFFFF),
    crownHalo = Color(0xFFF8E3E0),
    indigo = Color(0xFF3454A0),
    onIndigo = Color(0xFFFFFFFF),
    indigoTint = Color(0xFFE6EBF6),
    onIndigoTint = Color(0xFF22397A),
    indigoHalo = Color(0xFFC9D3EC),
    amber = Color(0xFFA35A00),
    amberContainer = Color(0xFFFBF1E4),
    onAmberContainer = Color(0xFF7A4300),
    track = Color(0xFFECE7DF),
)

val NightColors = GrullaColors(
    night = true,
    paper = Color(0xFF0F1113),
    sheet = Color(0xFF181B1F),
    ink = Color(0xFFECE8E1),
    graphite = Color(0xFFA7ACB3),
    line = Color(0xFF2A2E33),
    lineStrong = Color(0xFF3A3F46),
    tag = Color(0xFF22262B),
    crown = Color(0xFFFF7466),
    onCrown = Color(0xFF1A0C0A),
    crownHalo = Color(0xFF3A2422),
    indigo = Color(0xFF9DB2EC),
    onIndigo = Color(0xFF10204A),
    indigoTint = Color(0xFF232C42),
    onIndigoTint = Color(0xFFDCE4FA),
    indigoHalo = Color(0xFF2E3A58),
    amber = Color(0xFFE3A04B),
    amberContainer = Color(0xFF33260F),
    onAmberContainer = Color(0xFFF5D3A6),
    track = Color(0xFF2E3338),
)

private fun materialScheme(c: GrullaColors): ColorScheme {
    val base = if (c.night) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c.indigo,
        onPrimary = c.onIndigo,
        primaryContainer = c.indigoTint,
        onPrimaryContainer = c.onIndigoTint,
        secondary = c.indigo,
        onSecondary = c.onIndigo,
        secondaryContainer = c.indigoTint,
        onSecondaryContainer = c.onIndigoTint,
        tertiary = c.crown,
        onTertiary = c.onCrown,
        tertiaryContainer = c.crownHalo,
        onTertiaryContainer = c.ink,
        error = c.amber,
        onError = c.sheet,
        errorContainer = c.amberContainer,
        onErrorContainer = c.onAmberContainer,
        background = c.paper,
        onBackground = c.ink,
        surface = c.paper,
        onSurface = c.ink,
        surfaceVariant = c.tag,
        onSurfaceVariant = c.graphite,
        surfaceTint = Color.Transparent,
        surfaceBright = c.sheet,
        surfaceDim = c.paper,
        surfaceContainerLowest = c.sheet,
        surfaceContainerLow = c.sheet,
        surfaceContainer = c.sheet,
        surfaceContainerHigh = c.sheet,
        surfaceContainerHighest = c.tag,
        inverseSurface = c.ink,
        inverseOnSurface = c.paper,
        inversePrimary = if (c.night) DayColors.indigo else NightColors.indigo,
        outline = c.lineStrong,
        outlineVariant = c.line,
        scrim = Color(0xCC000000),
    )
}

/** Fraunces (engraved-style serif) for titles and note names, Inter for everything else. Both OFL. */
@Immutable
data class GrullaFonts(val serif: FontFamily, val sans: FontFamily)

@Composable
private fun rememberFonts(): GrullaFonts {
    val assets = LocalContext.current.assets
    return remember(assets) {
        fun inter(weight: Int) = Font(
            "fonts/Inter-Variable.ttf",
            assets,
            FontWeight(weight),
            variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
        )
        fun fraunces(weight: Int) = Font(
            "fonts/Fraunces-Variable.ttf",
            assets,
            FontWeight(weight),
            variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.Setting("opsz", 48f)),
        )
        GrullaFonts(
            serif = FontFamily(fraunces(500), fraunces(600)),
            sans = FontFamily(inter(400), inter(500), inter(600), inter(700)),
        )
    }
}

private fun typography(f: GrullaFonts): Typography {
    fun serif(size: Int, line: Int) = TextStyle(fontFamily = f.serif, fontWeight = FontWeight.SemiBold, fontSize = size.sp, lineHeight = line.sp)
    fun sans(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) =
        TextStyle(fontFamily = f.sans, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp)
    return Typography(
        displayLarge = serif(48, 56),
        displayMedium = serif(40, 48),
        displaySmall = serif(30, 36),
        headlineLarge = serif(28, 34),
        headlineMedium = serif(26, 32),
        headlineSmall = serif(24, 30),
        titleLarge = serif(19, 24),
        titleMedium = sans(16, 22, FontWeight.SemiBold),
        titleSmall = sans(14, 20, FontWeight.SemiBold),
        bodyLarge = sans(16, 24),
        bodyMedium = sans(14, 20),
        bodySmall = sans(12, 16),
        labelLarge = sans(15, 20, FontWeight.SemiBold),
        labelMedium = sans(13, 18, FontWeight.SemiBold),
        labelSmall = sans(12, 16, FontWeight.SemiBold).copy(letterSpacing = 0.06.em),
    )
}

private val LocalGrullaColors = staticCompositionLocalOf { DayColors }
private val LocalGrullaFonts = staticCompositionLocalOf<GrullaFonts?> { null }

/** Theme values beyond Material's: `Grulla.colors.crown`, `Grulla.fonts.serif`. */
object Grulla {
    val colors: GrullaColors
        @Composable @ReadOnlyComposable get() = LocalGrullaColors.current

    val fonts: GrullaFonts
        @Composable @ReadOnlyComposable get() = LocalGrullaFonts.current ?: error("GrullaTheme is missing")
}

@Composable
fun isNightTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun GrullaTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val colors = if (isNightTheme(mode)) NightColors else DayColors
    val fonts = rememberFonts()
    CompositionLocalProvider(LocalGrullaColors provides colors, LocalGrullaFonts provides fonts) {
        MaterialTheme(
            colorScheme = materialScheme(colors),
            typography = typography(fonts),
            shapes = Shapes(
                extraSmall = RoundedCornerShape(8.dp),
                small = RoundedCornerShape(12.dp),
                medium = RoundedCornerShape(16.dp),
                large = RoundedCornerShape(20.dp),
                extraLarge = RoundedCornerShape(24.dp),
            ),
            content = content,
        )
    }
}
