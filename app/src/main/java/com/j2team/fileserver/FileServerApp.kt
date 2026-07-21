package com.j2team.fileserver

import android.app.Application
import com.j2team.fileserver.core.cache.AppCacheManager
import com.j2team.fileserver.core.network.FileBrowserClient
import com.j2team.fileserver.core.session.EncryptedSecretStore
import com.j2team.fileserver.core.session.ProcessSecretStore
import com.j2team.fileserver.core.session.SessionRepository
import com.j2team.fileserver.feature.transfers.TransferStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FileServerApp : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Purge credentials persisted by older builds; new sessions are process-only.
        EncryptedSecretStore(applicationContext).clearAll()
        applicationScope.launch { AppCacheManager.cleanup(applicationContext) }
    }

    /** Process-owned queue/session survive Activity recreation and share the global transfer runtime. */
    val transferStore by lazy { TransferStore(this) }
    val sessionRepository by lazy { SessionRepository(ProcessSecretStore(), FileBrowserClient()) }
}
