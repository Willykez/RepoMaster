package com.willykez.repomaster.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object SyncScheduler {

    /** Call on app start and whenever the Settings toggle/interval changes. Cheap to call
     * repeatedly — WorkManager no-ops via ExistingPeriodicWorkPolicy.UPDATE if nothing
     * actually changed, and replaces the schedule if the interval did. */
    fun applyFromPrefs(context: Context) {
        if (SyncPrefs.isEnabled(context)) {
            schedule(context, SyncPrefs.intervalMinutes(context))
        } else {
            cancel(context)
        }
    }

    private fun schedule(context: Context, intervalMinutes: Long) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // coerceAtLeast here too, not just in SyncPrefs — this is the actual call that would
        // throw if handed something below WorkManager's real floor, so it shouldn't depend on
        // every caller having already clamped correctly upstream.
        val request = PeriodicWorkRequestBuilder<SyncWorker>(
            intervalMinutes.coerceAtLeast(SyncPrefs.MIN_INTERVAL_MINUTES), TimeUnit.MINUTES,
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    private fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(SyncWorker.WORK_NAME)
    }
}
