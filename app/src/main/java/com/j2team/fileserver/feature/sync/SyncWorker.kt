package com.j2team.fileserver.feature.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.j2team.fileserver.core.network.FileBrowserClient
import com.j2team.fileserver.feature.servers.ServerStore
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = SyncFolderStore(applicationContext)
        val tokenStore = EncryptedSyncTokenStore(applicationContext)
        val profiles = ServerStore(applicationContext).all()
        val identities = ServerIdentityStore(applicationContext)
        val requested = inputData.getString(KEY_FOLDER_ID)
        val folders = if (requested == null) store.all().filter { it.enabled } else listOfNotNull(store.find(requested)?.takeIf { it.enabled })
        var retry = false
        folders.forEach { folder ->
            val token = tokenStore.get(folder.id) ?: return@forEach
            val engine = SyncEngine(applicationContext, store, SyncRemoteRepository(FileBrowserClient(), token))
            val candidates = identities.candidates(folder, profiles)
            if (candidates.isEmpty() || candidates.none { engine.run(it, folder.id).isSuccess }) retry = true
        }
        return if (retry && runAttemptCount < 3) Result.retry() else Result.success()
    }

    companion object {
        private const val KEY_FOLDER_ID = "folder_id"
        private const val PERIODIC_NAME = "file-server-folder-sync"
        private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS).setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun runNow(context: Context, folderId: String) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints).setInputData(Data.Builder().putString(KEY_FOLDER_ID, folderId).build()).build()
            WorkManager.getInstance(context).enqueueUniqueWork("sync-$folderId", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
