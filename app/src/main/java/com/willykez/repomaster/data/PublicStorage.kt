package com.willykez.repomaster.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * Repos now live in a PUBLIC folder — /storage/emulated/0/.RepoMaster/repos/<name> —
 * instead of this app's private Android/data/com.willykez.repomaster/files folder.
 *
 * Why this matters: Android/data is locked down starting Android 11 — even
 * file manager apps can't easily browse into another app's Android/data
 * folder anymore. A public folder under the root of shared storage is
 * visible to any file manager, any other app, Termux, a PC over USB/MTP,
 * etc, so you can patch files with other tools and Repo Master will see the
 * changes next time you open the repo.
 *
 * The trade-off: JGit needs a real java.io.File path (not a SAF content://
 * URI), so the simplest way to get one for a public folder is the
 * MANAGE_EXTERNAL_STORAGE ("All files access") permission on Android 11+.
 * That's a manual toggle in system Settings — Android won't show a normal
 * permission popup for it — so this class also handles building the Intent
 * that takes the user straight to the right settings screen.
 *
 * The folder is dot-prefixed (`.RepoMaster`) to hide it from gallery/media
 * scanners and default file-manager views, per the standard Android
 * "hidden shared folder" convention — it's still genuinely public storage,
 * just not cluttering the top level of internal storage. This is
 * non-breaking for existing installs: repo locations are stored as an
 * absolute path (`RepoEntity.fullSavePath`) rather than recomputed from
 * this folder name, so already-tracked repos keep working no matter which
 * folder they were originally cloned into.
 *
 * One consequence: [RepoRepository.scanForLocalRepos] only looks inside
 * the current [reposRootDir]. A repo someone had manually dropped into the
 * old visible `RepoMaster` folder won't be auto-detected going forward —
 * it needs to be moved under `.RepoMaster`, or re-cloned.
 */
object PublicStorage {
    private const val FOLDER_NAME = ".RepoMaster"

    /** Root folder for all cloned repos. Creates it if it doesn't exist yet. */
    fun reposRootDir(): File {
        val root = File(Environment.getExternalStorageDirectory(), FOLDER_NAME)
        val repos = File(root, "repos")
        repos.mkdirs()
        return repos
    }

    /**
     * Where the Actions screen's "Install" button saves an extracted APK — under
     * `.RepoMaster/apk-downloads/<repoName>/`, not inside the repo's own working copy.
     * Keeping it out of the repo folder matters: a file dropped straight into the working
     * tree would show up as an untracked change in every Changes/Log screen for that repo,
     * which a downloaded build artifact has no business being. Organizing per-repo (rather
     * than one shared folder) just makes it obvious which app a given APK came from if
     * someone goes looking in a file manager later.
     *
     * Contents here are disposable — each new install for a repo overwrites whatever was
     * there before, the same "always exactly one thing at a time" model as the app-private
     * cache version used previously, just relocated to public storage per repo.
     */
    fun apkDownloadsDir(repoName: String): File {
        val root = File(Environment.getExternalStorageDirectory(), FOLDER_NAME)
        val dir = File(File(root, "apk-downloads"), repoName.ifBlank { "unknown-repo" })
        dir.mkdirs()
        return dir
    }

    /**
     * Whether the app currently has the access it needs to read/write the
     * public folder above. On Android 10 and below this is always true here
     * (classic WRITE_EXTERNAL_STORAGE, requested separately, covers it).
     */
    /** The overall `.RepoMaster` root — parent of both `repos/` and `apk-downloads/`.
     *  Doesn't create it (unlike [reposRootDir]/[apkDownloadsDir]) since this is purely
     *  informational, e.g. showing the path in Settings; nothing should force-create the
     *  root folder just by looking at where it *would* be. */
    fun rootDir(): File = File(Environment.getExternalStorageDirectory(), FOLDER_NAME)

    /** Parent of every per-repo APK downloads subfolder — used by Settings to show total
     *  size and offer a "clear all" that doesn't require iterating tracked repos first. */
    fun apkDownloadsRootDir(): File = File(rootDir(), "apk-downloads")

    fun directorySizeBytes(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun hasStorageAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    /** Only meaningful on Android 11+ — builds the Intent for the All Files Access settings screen. */
    fun allFilesAccessIntent(context: Context): Intent {
        return Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }
}
