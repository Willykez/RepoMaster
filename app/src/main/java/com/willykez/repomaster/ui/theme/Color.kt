package com.willykez.repomaster.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * Repo Master's Material 3 Expressive palette.
 *
 * This replaces the old flat "cockpit" blue/gold scheme with M3 Expressive's
 * richer three-accent model (primary / secondary / tertiary), tuned for
 * higher chroma and more contrast between tonal steps — the look Expressive
 * is built around. Dynamic color (Material You, wallpaper-derived) is the
 * default on Android 12+; these are the static fallback + the seed colors
 * used to *build* the expressive scheme on older devices.
 *
 * CommandBlue/SignalGold/Emerald are the three accent seeds, and — unlike the
 * rest of this file — are `mutableStateOf`-backed rather than plain `val`s.
 * This is what lets the Settings screen's color palette picker (see
 * [com.willykez.repomaster.ui.theme.AccentPalette]) repaint literally every
 * screen in the app immediately: every file that already does
 * `import com.willykez.repomaster.ui.theme.*` and reads `Amber` or
 * `CommandBlue` inside a @Composable automatically subscribes to this state
 * on read, the same way reading any other Compose State does — no per-screen
 * changes needed anywhere else in the app for a palette swap to take effect.
 * Every color *derived* from these three (Deep/Dim variants, the legacy
 * aliases below) is a computed `get()` rather than a plain `val` for the same
 * reason: a plain `val Amber = SignalGold` would freeze at whatever
 * SignalGold's value was at class-init time and never update again.
 */

// ---------------------------------------------------------------------------
// Surfaces — dark is still the default identity, but warmer/more neutral
// than the old blue-black "Void", closer to M3 Expressive's neutral-variant
// surfaces which lean on tonal elevation instead of hairline borders.
// Not part of the accent-palette system below — surfaces stay fixed
// regardless of which accent palette is active, same as the reference app
// keeps its surface ladder independent of the chosen seed color.
// ---------------------------------------------------------------------------
val Void = Color(0xFF14120F)          // app background — warm near-black
val Hull = Color(0xFF1C1A17)          // base surface (scaffold, bars)
val Deck = Color(0xFF262320)          // card surface
val DeckRaised = Color(0xFF322E2A)    // pressed / raised card, sheets, menus
val HullBorder = Color(0x1FFFFFFF)    // hairline — used sparingly now; expressive cards lean on tonal fill, not borders

// ---------------------------------------------------------------------------
// The three accent seeds — reassigned in place by AccentPalette.apply(),
// never replaced/re-declared, so every existing reference stays valid.
// ---------------------------------------------------------------------------

/** Primary accent — navigation, links, primary actions. Default: vivid orchid/violet. */
var CommandBlue: Color by mutableStateOf(Color(0xFFC6A6FF))
    internal set

/** Secondary accent — still rationed to commit/push (anything touching the remote). Default: flame coral. */
var SignalGold: Color by mutableStateOf(Color(0xFFFF8A5B))
    internal set

/** Tertiary accent — "everything's clean/synced" state and general success accents. Default: bright emerald. */
var Emerald: Color by mutableStateOf(Color(0xFF4CDA9B))
    internal set

// Derived shades — computed, not cached, so they track the seeds above.
val CommandBlueDeep: Color get() = CommandBlue.deepened()
val CommandBlueDim: Color get() = CommandBlue.copy(alpha = 0.20f)
val SignalGoldDeep: Color get() = SignalGold.deepened()
val EmeraldDeep: Color get() = Emerald.deepened()

// Status — tied to the accent seeds where the original design intended that
// (added/success reuses the tertiary emerald, modified reuses the secondary
// coral), independent fixed colors where it didn't (deleted is always a red,
// regardless of palette — a destructive status shouldn't change meaning
// depending on which accent palette happens to be active).
val StatusAdded: Color get() = Emerald                  // staged / new file / success
val StatusModified: Color get() = SignalGold            // modified file (shares flare hue family)
val StatusDeleted = Color(0xFFFF5C72)                    // deleted / destructive / errors — always red
val StatusMuted = Color(0xFF9C948A)                      // neutral / clean / secondary text

// Light theme (secondary — dark is still the default identity for a dev tool)
val Paper = Color(0xFFFFF8F2)
val PaperDim = Color(0xFFF0E7DD)
val Graphite = Color(0xFF1F1B16)

// ---------------------------------------------------------------------------
// Legacy token aliases — every screen inherited from the three source apps
// was written against one of these names. Keeping them as aliases (instead
// of touching 50+ screen files individually) repaints the whole app with
// the new Expressive palette immediately and consistently. New/rewritten
// screens should prefer the tokens above. All computed, same reasoning as
// the Deep/Dim variants above.
// ---------------------------------------------------------------------------
val PlumDeep: Color get() = Hull
val PlumSoft: Color get() = Deck
val Amber: Color get() = SignalGold
val AmberDeep: Color get() = SignalGoldDeep
val Cream = Color(0xFFFBF3EA)
val CreamDim: Color get() = DeckRaised
val Ink: Color get() = Void
val InkSurface: Color get() = Deck
val StatusClean: Color get() = StatusMuted

/** Blends this color toward black by [amount] — used for "Deep" accent variants
 *  (pressed states, stronger emphasis) instead of a second hand-picked hex per
 *  accent, so a custom/user-picked palette still gets a sensible Deep shade
 *  rather than none at all. */
fun Color.deepened(amount: Float = 0.32f): Color = Color(
    red = red * (1f - amount),
    green = green * (1f - amount),
    blue = blue * (1f - amount),
    alpha = alpha,
)
