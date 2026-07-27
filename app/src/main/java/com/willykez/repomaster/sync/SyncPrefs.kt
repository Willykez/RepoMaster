package com.willykez.repomaster.sync

import android.content.Context

/**
 * Deliberately plain SharedPreferences rather than DataStore — this is exactly two
 * settings (on/off, interval), and DataStore's Flow-based API would be pure overhead here.
 *
 * Interval is stored in minutes, not hours — lets Settings offer a real time picker (any
 * value from 15 minutes to 2 hours) instead of a fixed list of whole-hour options.
 */
object SyncPrefs {
    private const val PREFS_NAME = "repomaster_sync_prefs"
    private const val KEY_ENABLED = "background_sync_enabled"
    private const val KEY_INTERVAL_MINUTES = "background_sync_interval_minutes"

    const val DEFAULT_INTERVAL_MINUTES = 180L // 3h — same default as before this became minute-based

    /**
     * WorkManager's `PeriodicWorkRequest` hard-floors periodic work at 15 minutes
     * ([androidx.work.PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS]) — this is an Android
     * platform limit, not a choice made here, and there's no way around it for genuinely
     * periodic background work without a persistent foreground service (which trades this
     * app's whole "lightweight, battery-friendly background check" design for something much
     * heavier). Anything picked below this is clamped up to it.
     */
    const val MIN_INTERVAL_MINUTES = 15L

    /** 2 hours — checking more often than this for a background *fetch* (not even a pull)
     *  has rapidly diminishing returns against the battery/data cost of doing it more often. */
    const val MAX_INTERVAL_MINUTES = 120L

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun intervalMinutes(context: Context): Long =
        prefs(context).getLong(KEY_INTERVAL_MINUTES, DEFAULT_INTERVAL_MINUTES)
            .coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)

    fun setIntervalMinutes(context: Context, minutes: Long) {
        prefs(context).edit().putLong(KEY_INTERVAL_MINUTES, minutes.coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)).apply()
    }
}
