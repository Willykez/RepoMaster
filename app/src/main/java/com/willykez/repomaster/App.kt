package com.willykez.repomaster

import android.app.Application
import com.willykez.repomaster.data.AccentPalettePrefs
import com.willykez.repomaster.data.AppearancePrefs
import com.willykez.repomaster.data.GitIdentityPrefs
import com.willykez.repomaster.data.db.AppDatabase
import com.willykez.repomaster.data.repository.CredentialRepository
import com.willykez.repomaster.data.repository.RepoRepository
import com.willykez.repomaster.sync.SyncNotifier
import com.willykez.repomaster.sync.SyncScheduler

/**
 * Application class. Holds simple hand-rolled singletons for the database
 * and repositories — no DI framework needed for an app this small.
 */
class App : Application() {

    lateinit var repoRepository: RepoRepository
        private set

    lateinit var credentialRepository: CredentialRepository
        private set

    override fun onCreate() {
        super.onCreate()

        val db = AppDatabase.getDatabase(this)
        repoRepository = RepoRepository(db.repoDao())
        credentialRepository = CredentialRepository(db.credentialDao())

        SyncNotifier.createChannel(this)
        AppearancePrefs.init(this)
        GitIdentityPrefs.init(this)
        AccentPalettePrefs.init(this)

        // Re-applies whatever the user last set in Settings — WorkManager schedules don't
        // survive a full app data wipe/reinstall, but they do survive normal process death,
        // so this is mainly a safety net plus the thing that (re)schedules after a toggle.
        SyncScheduler.applyFromPrefs(this)
    }
}
