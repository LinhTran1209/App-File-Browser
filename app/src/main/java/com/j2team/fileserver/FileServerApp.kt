package com.j2team.fileserver

import android.app.Application
import com.j2team.fileserver.core.network.FileBrowserClient
import com.j2team.fileserver.core.session.EncryptedSecretStore
import com.j2team.fileserver.core.session.SessionRepository
import com.j2team.fileserver.feature.transfers.TransferStore

class FileServerApp : Application() {
    /** Process-owned queue/session survive Activity recreation and share the global transfer runtime. */
    val transferStore by lazy { TransferStore(this) }
    val sessionRepository by lazy { SessionRepository(EncryptedSecretStore(applicationContext), FileBrowserClient()) }
}
