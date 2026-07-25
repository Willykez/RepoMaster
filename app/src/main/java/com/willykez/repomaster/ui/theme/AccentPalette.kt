package com.willykez.repomaster.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.abs

/** One accent seed and the secondary/tertiary it's paired with. */
data class AccentTriplet(val primary: Color, val secondary: Color, val tertiary: Color)

/**
 * Curated accent palettes for the Settings screen's color picker, plus the color-science to
 * generate a coherent secondary/tertiary pair from any single seed color (used for every
 * preset except Default, and for a custom user-picked hex).
 *
 * Applying a palette just reassigns [CommandBlue]/[SignalGold]/[Emerald] in
 * [com.willykez.repomaster.ui.theme] — those are `mutableStateOf`-backed, so every screen in
 * the app that reads them (which is most of them, via the legacy token aliases) repaints
 * immediately. No navigation, no per-screen wiring.
 */
object AccentPalette {

    /** The app's original, hand-tuned trio — not algorithmically derived like the others,
     *  since coral and emerald were chosen deliberately, not as a hue-rotation of violet. */
    private val DEFAULT_TRIPLET = AccentTriplet(
        primary = Color(0xFFC6A6FF),
        secondary = Color(0xFFFF8A5B),
        tertiary = Color(0xFF4CDA9B),
    )

    data class Preset(val id: String, val label: String, val swatch: Color)

    val presets: List<Preset> = listOf(
        Preset("default", "Default", DEFAULT_TRIPLET.primary),
        Preset("ember", "Ember", Color(0xFFF97316)),
        Preset("ocean", "Ocean", Color(0xFF0284C7)),
        Preset("grove", "Grove", Color(0xFF6B8E23)),
        Preset("berry", "Berry", Color(0xFFD946EF)),
        Preset("honey", "Honey", Color(0xFFFACC15)),
        Preset("dusk", "Dusk", Color(0xFF6B7280)),
        Preset("iris", "Iris", Color(0xFF7C3AED)),
    )

    fun tripletFor(presetId: String): AccentTriplet {
        if (presetId == "default") return DEFAULT_TRIPLET
        val seed = presets.firstOrNull { it.id == presetId }?.swatch ?: return DEFAULT_TRIPLET
        return generateTriplet(seed)
    }

    fun apply(triplet: AccentTriplet) {
        CommandBlue = triplet.primary
        SignalGold = triplet.secondary
        Emerald = triplet.tertiary
    }

    fun applyPreset(presetId: String) = apply(tripletFor(presetId))

    /** @return false (and applies nothing) if [hex] isn't a valid 6-digit hex color. */
    fun applyCustomHex(hex: String): Boolean {
        val color = parseHexColorOrNull(hex) ?: return false
        apply(generateTriplet(color))
        return true
    }

    /**
     * Secondary rotates +20° hue with a softened, clamped saturation; tertiary rotates −40°
     * hue with a different saturation/lightness curve — enough separation that three colors
     * from one seed read as a deliberate trio rather than three shades of the same hue.
     * Plain HSL math, not full HCT/Material color science — this app's accents are flat
     * chips/icons/lane colors, not a generated tonal ColorScheme, so HSL rotation is the
     * right amount of sophistication for what's actually visible.
     */
    fun generateTriplet(primary: Color): AccentTriplet {
        val (h, s, l) = colorToHsl(primary)

        val secHue = (h + 20f) % 360f
        val secSat = if (s < 0.30f) s else (s * 0.70f).coerceIn(0.35f, 0.85f)
        val secLight = if (l < 0.5f) (l + 0.04f).coerceIn(0f, 1f) else (l - 0.04f).coerceIn(0f, 1f)
        val secondary = hslToColor(secHue, secSat, secLight)

        val terHue = (h - 40f + 360f) % 360f
        val terSat = if (s < 0.35f) s else (s * 0.85f).coerceIn(0.40f, 0.90f)
        val terLight = if (l < 0.5f) (l + 0.08f).coerceIn(0f, 1f) else (l - 0.08f).coerceIn(0f, 1f)
        val tertiary = hslToColor(terHue, terSat, terLight)

        return AccentTriplet(primary, secondary, tertiary)
    }
}

fun parseHexColorOrNull(hex: String): Color? {
    val clean = hex.removePrefix("#").trim()
    if (clean.length != 6 || clean.any { it !in "0123456789abcdefABCDEF" }) return null
    return try {
        Color(("FF" + clean).toLong(16).toInt())
    } catch (e: Exception) {
        null
    }
}

private fun colorToHsl(color: Color): Triple<Float, Float, Float> {
    val r = color.red
    val g = color.green
    val b = color.blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    val lightness = (max + min) / 2f

    var hue = 0f
    var saturation = 0f
    if (delta != 0f) {
        saturation = if (lightness < 0.5f) delta / (max + min) else delta / (2f - max - min)
        hue = when (max) {
            r -> (g - b) / delta + (if (g < b) 6f else 0f)
            g -> (b - r) / delta + 2f
            else -> (r - g) / delta + 4f
        }
        hue *= 60f
    }
    return Triple(hue, saturation, lightness)
}

private fun hslToColor(hue: Float, saturation: Float, lightness: Float): Color {
    val chroma = (1f - abs(2f * lightness - 1f)) * saturation
    val x = chroma * (1f - abs((hue / 60f) % 2f - 1f))
    val m = lightness - chroma / 2f
    val (r, g, b) = when {
        hue < 60f -> Triple(chroma, x, 0f)
        hue < 120f -> Triple(x, chroma, 0f)
        hue < 180f -> Triple(0f, chroma, x)
        hue < 240f -> Triple(0f, x, chroma)
        hue < 300f -> Triple(x, 0f, chroma)
        else -> Triple(chroma, 0f, x)
    }
    return Color(red = r + m, green = g + m, blue = b + m, alpha = 1f)
}
