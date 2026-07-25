package com.willykez.repomaster.data

import android.content.Context
import android.content.SharedPreferences
import com.willykez.repomaster.ui.theme.AccentPalette

/**
 * Persists which accent palette is active — a preset ID, or "custom" with a stored hex —
 * and reapplies it at app start. [AccentPalette.apply] itself only mutates in-memory
 * `mutableStateOf` colors; without this, every palette choice would reset back to Default
 * the moment the process restarted.
 */
object AccentPalettePrefs {
    private const val PREFS_NAME = "repomaster_accent_prefs"
    private const val CUSTOM_ID = "custom"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val presetId = prefs.getString("preset_id", "default") ?: "default"
        val customHex = prefs.getString("custom_hex", null)
        if (presetId == CUSTOM_ID && customHex != null) {
            AccentPalette.applyCustomHex(customHex)
        } else {
            AccentPalette.applyPreset(presetId)
        }
    }

    fun currentPresetId(context: Context): String {
        init(context)
        return prefs.getString("preset_id", "default") ?: "default"
    }

    fun currentCustomHex(context: Context): String? {
        init(context)
        return prefs.getString("custom_hex", null)
    }

    fun selectPreset(context: Context, presetId: String) {
        init(context)
        prefs.edit().putString("preset_id", presetId).remove("custom_hex").apply()
        AccentPalette.applyPreset(presetId)
    }

    /** @return false (and changes nothing, including the persisted selection) if [hex] isn't
     *  a valid color. */
    fun selectCustomHex(context: Context, hex: String): Boolean {
        init(context)
        val applied = AccentPalette.applyCustomHex(hex)
        if (applied) prefs.edit().putString("preset_id", CUSTOM_ID).putString("custom_hex", hex).apply()
        return applied
    }
}
