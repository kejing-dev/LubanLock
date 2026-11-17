package io.embodit.lubanlock.ui.theme

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.systemColorScheme
import io.embodit.lubanlock.domain.Language
import io.embodit.lubanlock.platform.Graph
import java.util.Locale

/** Beech / cherry / walnut sticks, turquoise icon, amber arrows (design assets); text roles measured on the window glass (D13). */
object LubanColors {
    /** Interaction teal (white text on it 5.9:1). */
    val Primary = Color(0xFF1E6B66)
    val OnPrimary = Color(0xFFFFFFFF)
    val PrimaryContainer = Color(0xFFD5F0EE)
    val Secondary = Color(0xFF8C6A3F)
    val Error = Color(0xFFB3261E)
    val OnError = Color(0xFFFFFFFF)
    val Ink = Color(0xFF1B1F23)
    /** Secondary / small text on the window glass (>= 4.5:1 on the darkest measured glass #B4B3B1). */
    val InkSecondary = Color(0xFF3A3F44)
    val Amber = Color(0xFFE8A33D)
    /**
     * Solid light base under every control-window page (QA #3 q3-2): the emulator sometimes draws only part of the
     * system glass frame and leaves the rest black (pico-cli-feedback "玻璃面板…下半截长时间黑"), which put the S01 text
     * on black. Close to the measured light glass, so the window still reads as glass; Ink on it 13:1.
     */
    val WindowBase = Color(0xFFE4E2DE)

    // cards / figures
    val TagFace = Color(0xFFFBF6EC)
    val Beech = Color(0xFFD9B98C)
    val Cherry = Color(0xFFA0522D)
    val Walnut = Color(0xFF5A3A22)
    val Vermilion = Color(0xFFC0392B)
    val Gold = Color(0xFFF3D27A)
    val Turquoise = Color(0xFF3FB8AF)
}

/**
 * PicoTheme with all 16 roles assigned (Cloud Air pattern). The language setting localises resources below this
 * point through a ContextWrapper that only overrides getResources (a bare configuration context breaks SpatialView).
 */
@Composable
fun LubanTheme(content: @Composable () -> Unit) {
    val base = LocalContext.current
    val graph = remember(base) { Graph.from(base) }
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val localized = remember(base, settings.language) { base.localized(settings.language) }
    val system = systemColorScheme(localized)
    val scheme = system.copy(
        fillPrimary = system.fillPrimary,
        fillSecondary = system.fillSecondary,
        fillTertiary = system.fillTertiary,
        fillLight = system.fillLight,
        labelPrimaryLight = LubanColors.OnPrimary,
        labelPrimary = LubanColors.Ink,
        labelSecondary = LubanColors.InkSecondary,
        labelTertiary = system.labelTertiary,
        labelQuaternary = system.labelQuaternary,
        lightenHover = system.lightenHover,
        lightenPressed = system.lightenPressed,
        error = LubanColors.Error,
        alert = LubanColors.Amber,
        passable = LubanColors.Secondary,
        interaction = LubanColors.Primary,
        dividerLine = system.dividerLine,
    )
    CompositionLocalProvider(LocalContext provides localized) {
        PicoTheme(colorScheme = scheme, typography = PicoTheme.typography.withNotoSans(), content = content)
    }
}

fun Context.localized(language: Language): Context {
    val locale = when (language) {
        Language.SYSTEM -> return this
        Language.ZH -> Locale.SIMPLIFIED_CHINESE
        Language.EN -> Locale.ENGLISH
    }
    val config = Configuration(resources.configuration)
    config.setLocale(locale)
    val res = createConfigurationContext(config).resources
    return object : ContextWrapper(this) {
        override fun getResources(): Resources = res
    }
}
