package com.willykez.repomaster.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Turns a downloaded GitHub Actions artifact (always a zip — GitHub wraps every artifact,
 * even a single file, in one) into a system install prompt.
 *
 * The extracted APK is saved to [PublicStorage.apkDownloadsDir] — a public, per-repo folder
 * under `.RepoMaster/apk-downloads/<repoName>/`, next to (not inside) that repo's working
 * copy. Public rather than app-private cache means the APK is still there afterward if
 * someone wants to grab it with a file manager, share it, or side-load it on another
 * device — the same "visible to any other tool" reasoning behind putting repos themselves
 * in public storage (see [PublicStorage]).
 */
object ApkInstaller {

    sealed class Result {
        data class Ready(val installIntent: Intent) : Result()
        object NoApkInArchive : Result()
        data class Failed(val message: String) : Result()
    }

    /** Builds the FileProvider-backed install [Intent] for an APK that's already sitting on
     *  disk — shared by [extractAndBuildInstallIntent] (right after extracting one from a
     *  zip) and the APK downloads explorer (reinstalling one that was already extracted in a
     *  previous session, without needing to re-download anything). */
    fun installIntentForFile(context: Context, apkFile: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    suspend fun extractAndBuildInstallIntent(
        context: Context,
        zipBytes: ByteArray,
        artifactName: String,
        repoName: String,
    ): Result = withContext(Dispatchers.IO) {
        try {
            if (!PublicStorage.hasStorageAccess(context)) {
                return@withContext Result.Failed(
                    "Repo Master needs storage access to save the APK. Grant it from the banner on the repo list, then try again.",
                )
            }

            val outDir = PublicStorage.apkDownloadsDir(repoName)
            // Clear anything left from a previous install for this repo — this folder only
            // ever needs to hold the one build currently being installed, not every build
            // anyone's ever tapped Install on.
            outDir.listFiles()?.forEach { it.delete() }

            val apkFile = File(outDir, "${artifactName.ifBlank { "build" }}.apk")
            var found = false

            ZipInputStream(zipBytes.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith(".apk", ignoreCase = true)) {
                        apkFile.outputStream().use { out -> zip.copyTo(out) }
                        found = true
                        break
                    }
                    entry = zip.nextEntry
                }
            }

            if (!found) return@withContext Result.NoApkInArchive

            Result.Ready(installIntentForFile(context, apkFile))
        } catch (e: Exception) {
            Result.Failed(e.message ?: "Couldn't extract the APK from this build output")
        }
    }
}
