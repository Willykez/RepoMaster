package com.willykez.repomaster.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.willykez.repomaster.MainActivity
import com.willykez.repomaster.R
import com.willykez.repomaster.navigation.Routes

private const val CHANNEL_ID = "background_sync"
private const val NOTIFICATION_ID_NEW_COMMITS = 4201
private const val NOTIFICATION_ID_AUTOMATION = 4202

/**
 * Posts at most one notification per kind per background sync pass — never one per repo.
 * [SyncWorker] fetches every tracked repo every time it runs; most passes find nothing new,
 * and even a genuinely busy sync period touching several repos should read as one thing to
 * check, not a small flood of separate alerts. "New commits found" and "auto-committed
 * changes" are deliberately separate notifications (different IDs) rather than merged into
 * one, since they're different kinds of news — one is "something to look at," the other is
 * "something already happened without you."
 *
 * Tapping either notification deep-links straight into the specific repo it's about (via the
 * `repomaster://` scheme registered in AndroidManifest.xml — see [Routes.deepLinkRepo]) when
 * exactly one repo is involved, instead of just opening the app to the repo list and making
 * you go find it. When more than one repo had news in the same pass, there's no single repo
 * to deep-link to, so it falls back to opening the app normally.
 */
object SyncNotifier {

    /** Registers the notification channel. Safe to call every app start — creating a channel
     *  that already exists is a no-op — and channels only need to exist on API 26+. Must run
     *  before the first notification post, so this lives in [com.willykez.repomaster.App.onCreate]. */
    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Background sync",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Lets you know when a background fetch finds new commits, or when automation commits/pushes on your behalf"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** [reposWithNewCommits] is (repoId, repoName) pairs, one per repo that had at least one
     *  tracking ref move during this sync pass — see
     *  [com.willykez.repomaster.git.GitEngine.fetchAndCountUpdates]. No-ops entirely if empty. */
    fun notifyNewCommits(context: Context, reposWithNewCommits: List<Pair<Long, String>>) {
        if (reposWithNewCommits.isEmpty()) return
        val title = if (reposWithNewCommits.size == 1) {
            "${reposWithNewCommits.first().second} has new commits"
        } else {
            "${reposWithNewCommits.size} repos have new commits"
        }
        val body = reposWithNewCommits.joinToString(", ") { it.second }
        val target = reposWithNewCommits.singleOrNull()?.first?.let { Routes.deepLinkRepo(it) }
        post(context, NOTIFICATION_ID_NEW_COMMITS, title, body, target)
    }

    /** [reposAutoCommitted] is (repoId, repoName) pairs, one per repo where the opt-in
     *  "auto-commit & push" automation actually made a commit this pass — see
     *  [SyncWorker.runAutomation]. No-ops entirely if empty (the common case). */
    fun notifyAutomation(context: Context, reposAutoCommitted: List<Pair<Long, String>>) {
        if (reposAutoCommitted.isEmpty()) return
        val title = if (reposAutoCommitted.size == 1) {
            "Auto-committed changes in ${reposAutoCommitted.first().second}"
        } else {
            "Auto-committed changes in ${reposAutoCommitted.size} repos"
        }
        val body = reposAutoCommitted.joinToString(", ") { it.second }
        val target = reposAutoCommitted.singleOrNull()?.first?.let { Routes.deepLinkRepo(it) }
        post(context, NOTIFICATION_ID_AUTOMATION, title, body, target)
    }

    /** No-ops if the app doesn't currently have notification permission — [SyncWorker] runs
     *  headless with no Activity to request POST_NOTIFICATIONS from, so this only ever checks/
     *  uses whatever permission state Settings already established, never prompts.
     *
     *  [deepLinkUri] null falls back to a plain MainActivity launch (used when more than one
     *  repo is involved, so there's no single destination to jump to). */
    private fun post(context: Context, notificationId: Int, title: String, body: String, deepLinkUri: String?) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        val intent = if (deepLinkUri != null) {
            Intent(Intent.ACTION_VIEW, Uri.parse(deepLinkUri)).apply {
                setPackage(context.packageName) // stay in-app; don't offer this to other apps that might also claim the scheme
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        } else {
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            context, notificationId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (e: SecurityException) {
            // Permission was revoked between the areNotificationsEnabled() check above and
            // this call (rare, but possible) — nothing useful to do from a headless worker
            // besides not crashing the sync job over a missed notification.
        }
    }
}
