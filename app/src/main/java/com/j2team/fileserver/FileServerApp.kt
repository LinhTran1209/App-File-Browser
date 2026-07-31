package com.j2team.fileserver

import android.app.Application
import com.j2team.fileserver.core.cache.AppCacheManager
import com.j2team.fileserver.core.network.FileBrowserClient
import com.j2team.fileserver.core.session.EncryptedSecretStore
import com.j2team.fileserver.core.session.ProcessSecretStore
import com.j2team.fileserver.core.session.SessionRepository
import com.j2team.fileserver.feature.transfers.TransferStore
import com.j2team.fileserver.feature.sync.SyncWorker
import com.j2team.fileserver.feature.sync.FolderSyncService
import com.j2team.fileserver.feature.sync.ServerIdentityStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FileServerApp : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        EncryptedSecretStore(applicationContext).clearAll()
        applicationScope.launch { AppCacheManager.cleanup(applicationContext) }
        SyncWorker.schedulePeriodic(this)
        FolderSyncService.refresh(this)
    }

    /** Process-owned queue/session survive Activity recreation and share the global transfer runtime. */
    val transferStore by lazy { TransferStore(this) }
    val sessionRepository by lazy { SessionRepository(ProcessSecretStore(), FileBrowserClient(), identityStore = ServerIdentityStore(this)) }
}
