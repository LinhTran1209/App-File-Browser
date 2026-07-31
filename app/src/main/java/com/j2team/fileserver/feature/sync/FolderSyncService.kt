package com.j2team.fileserver.feature.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.j2team.fileserver.R
import com.j2team.fileserver.core.network.FileBrowserClient
import com.j2team.fileserver.feature.servers.ServerStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class FolderSyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitor: Job? = null

    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.sync_notification_channel), NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION_ID, NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_transfers)
            .setContentTitle(getString(R.string.sync_notification_title))
            .setContentText(getString(R.string.sync_notification_text))
            .setOngoing(true).setOnlyAlertOnce(true).build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        monitor?.cancel()
        monitor = scope.launch { monitorFolders() }
        return START_STICKY
    }

    private suspend fun monitorFolders() = coroutineScope {
        val folderStore = SyncFolderStore(applicationContext)
        val tokenStore = EncryptedSyncTokenStore(applicationContext)
        val profiles = ServerStore(applicationContext).all()
        val identities = ServerIdentityStore(applicationContext)
        val folders = folderStore.all().filter { it.enabled && tokenStore.get(it.id) != null }
        if (folders.isEmpty()) { stopSelf(); return@coroutineScope }
        folders.forEach { folder ->
            val token = tokenStore.get(folder.id) ?: return@forEach
            val candidates = identities.candidates(folder, profiles)
            if (candidates.isEmpty()) return@forEach
            launch {
                val client = FileBrowserClient()
                val remote = SyncRemoteRepository(client, token)
                val engine = SyncEngine(applicationContext, folderStore, remote)
                var profileIndex = candidates.indexOfFirst(client::isReachable).takeIf { it >= 0 } ?: 0
                val localChanges = Channel<Unit>(Channel.CONFLATED)
                val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) { localChanges.trySend(Unit) }
                    override fun onChange(selfChange: Boolean, uri: Uri?) { localChanges.trySend(Unit) }
                }
                runCatching { contentResolver.registerContentObserver(Uri.parse(folder.localTreeUri), true, observer) }
                try {
                    candidates.indices.any { offset ->
                        val index = (profileIndex + offset) % candidates.size
                        if (engine.run(candidates[index], folder.id).isSuccess) { profileIndex = index; true } else false
                    }
                    launch {
                        for (signal in localChanges) {
                            delay(750)
                            if (engine.run(candidates[profileIndex], folder.id).isFailure) {
                                profileIndex = (profileIndex + 1) % candidates.size
                                engine.run(candidates[profileIndex], folder.id)
                            }
                        }
                    }
                    while (isActive) {
                        val current = folderStore.find(folder.id) ?: break
                        if (!current.enabled) break
                        val page = remote.changes(candidates[profileIndex], current.remotePath, current.cursor, waitSeconds = 25).getOrNull()
                        if (page == null) {
                            profileIndex = (profileIndex + 1) % candidates.size
                            delay(2_000)
                        }
                        else if (page.reset || page.changes.isNotEmpty()) engine.run(candidates[profileIndex], folder.id)
                    }
                } finally {
                    runCatching { contentResolver.unregisterContentObserver(observer) }
                    localChanges.close()
                }
            }
        }
    }

    override fun onDestroy() {
        monitor?.cancel()
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "folder_sync"
        private const val NOTIFICATION_ID = 1700

        fun refresh(context: Context) {
            val intent = Intent(context, FolderSyncService::class.java)
            if (SyncFolderStore(context).all().any { it.enabled }) runCatching { ContextCompat.startForegroundService(context, intent) }
            else context.stopService(intent)
        }
    }
}
