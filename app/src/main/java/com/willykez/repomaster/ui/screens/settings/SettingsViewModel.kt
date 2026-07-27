package com.willykez.repomaster.ui.screens.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.willykez.repomaster.App
import com.willykez.repomaster.data.AutomationPrefs
import com.willykez.repomaster.data.GitIdentityPrefs
import com.willykez.repomaster.data.PublicStorage
import com.willykez.repomaster.data.db.entity.RepoEntity
import com.willykez.repomaster.sync.SyncPrefs
import com.willykez.repomaster.sync.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val backgroundSyncEnabled: Boolean = false,
    val intervalHours: Long = SyncPrefs.DEFAULT_INTERVAL_HOURS,
    val authorName: String = "",
    val authorEmail: String = "",
    val storageRootPath: String = "",
    val apkCacheBytes: Long = 0L,
    val isCalculatingCache: Boolean = false,
    val allRepos: List<RepoEntity> = emptyList(),
    val automatedRepoIds: Set<Long> = emptySet(),
    val message: String? = null,
)

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(
        SettingsUiState(
            backgroundSyncEnabled = SyncPrefs.isEnabled(app),
            intervalHours = SyncPrefs.intervalHours(app),
            authorName = GitIdentityPrefs.currentName(app),
            authorEmail = GitIdentityPrefs.currentEmail(app),
            storageRootPath = PublicStorage.rootDir().absolutePath,
            automatedRepoIds = AutomationPrefs.enabledRepoIds(app),
        ),
    )
    val uiState: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        refreshCacheSize()
        loadRepos()
    }

    private fun loadRepos() {
        viewModelScope.launch {
            val repos = (getApplication<App>()).repoRepository.allRepos.first()
            _state.value = _state.value.copy(allRepos = repos)
        }
    }

    fun setAutomatedRepoIds(ids: Set<Long>) {
        val app = getApplication<android.app.Application>()
        AutomationPrefs.setEnabledRepoIds(app, ids)
        _state.value = _state.value.copy(automatedRepoIds = ids)
    }

    fun setBackgroundSyncEnabled(enabled: Boolean) {
        val app = getApplication<android.app.Application>()
        SyncPrefs.setEnabled(app, enabled)
        SyncScheduler.applyFromPrefs(app)
        _state.value = _state.value.copy(backgroundSyncEnabled = enabled)
    }

    fun setIntervalHours(hours: Long) {
        val app = getApplication<android.app.Application>()
        SyncPrefs.setIntervalHours(app, hours)
        if (_state.value.backgroundSyncEnabled) SyncScheduler.applyFromPrefs(app)
        _state.value = _state.value.copy(intervalHours = hours.coerceAtLeast(SyncPrefs.MIN_INTERVAL_HOURS))
    }

    fun setGitIdentity(name: String, email: String) {
        val app = getApplication<android.app.Application>()
        GitIdentityPrefs.set(app, name, email)
        _state.value = _state.value.copy(
            authorName = GitIdentityPrefs.currentName(app),
            authorEmail = GitIdentityPrefs.currentEmail(app),
            message = "Commits will now be attributed to ${GitIdentityPrefs.currentName(app)}",
        )
    }

    fun refreshCacheSize() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isCalculatingCache = true)
            val bytes = withContext(Dispatchers.IO) { PublicStorage.directorySizeBytes(PublicStorage.apkDownloadsRootDir()) }
            _state.value = _state.value.copy(apkCacheBytes = bytes, isCalculatingCache = false)
        }
    }

    /** Clears every repo's downloaded-APK folder at once — safe to wipe entirely since
     *  [com.willykez.repomaster.data.ApkInstaller] always treats this as disposable, re-downloadable
     *  install media, never something a repo depends on. */
    fun clearApkCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { PublicStorage.apkDownloadsRootDir().deleteRecursively() }
            refreshCacheSize()
            _state.value = _state.value.copy(message = "Cleared downloaded APK cache")
        }
    }

    fun dismissMessage() { _state.value = _state.value.copy(message = null) }
}
