package com.j2team.fileserver.feature.transfers

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/** Small durable queue; the worker can update it as bytes are streamed. */
class TransferStore(context: Context) {
    private val prefs = context.getSharedPreferences("transfer_queue", Context.MODE_PRIVATE)
    private val _tasks = MutableStateFlow(recoverAndPersist())
    val tasks: StateFlow<List<TransferTask>> = _tasks

    fun all(): List<TransferTask> = _tasks.value

    private fun readPersisted(): List<TransferTask> = runCatching {
        val json = JSONArray(prefs.getString("items", "[]") ?: "[]")
        (0 until json.length()).map { fromJson(json.getJSONObject(it)) }.sortedByDescending { it.updatedAt }
    }.getOrDefault(emptyList())

    private fun recoverAndPersist(): List<TransferTask> {
        val loaded = readPersisted()
        val recovered = recoverInterruptedTransfers(loaded)
        if (recovered != loaded) persist(recovered)
        return recovered.sortedByDescending { it.updatedAt }
    }

    fun enqueue(name: String, path: String, direction: TransferDirection, totalBytes: Long = 0L, sourceUri: String? = null, profileId: String? = null): TransferTask =
        save(TransferTask(UUID.randomUUID().toString(), name, path, direction, sourceUri, profileId, totalBytes = totalBytes))

    @Synchronized
    fun save(task: TransferTask): TransferTask {
        val updated = task.copy(updatedAt = System.currentTimeMillis())
        val items = all().filterNot { it.id == updated.id } + updated
        persist(items)
        _tasks.value = items.sortedByDescending { it.updatedAt }
        return updated
    }

    fun update(id: String, transferredBytes: Long, state: TransferState? = null, error: String? = null): TransferTask? =
        all().firstOrNull { it.id == id }?.let { save(it.copy(transferredBytes = transferredBytes, state = state ?: it.state, error = error)) }

    @Synchronized
    fun remove(id: String) {
        val remaining = removeTransfer(all(), id)
        persist(remaining)
        _tasks.value = remaining.sortedByDescending { it.updatedAt }
    }
    fun active(): List<TransferTask> = all().filter { it.isActive }

    private fun toJson(t: TransferTask) = JSONObject().apply {
        put("id", t.id); put("name", t.name); put("path", t.path); put("direction", t.direction.name)
        put("sourceUri", t.sourceUri)
        put("profileId", t.profileId)
        put("total", t.totalBytes); put("transferred", t.transferredBytes); put("state", t.state.name)
        put("error", t.error); put("created", t.createdAt); put("updated", t.updatedAt)
    }
    private fun persist(items: List<TransferTask>) { prefs.edit().putString("items", JSONArray().apply { items.forEach { put(toJson(it)) } }.toString()).apply() }
    private fun fromJson(o: JSONObject) = TransferTask(o.getString("id"), o.getString("name"), o.getString("path"),
        TransferDirection.valueOf(o.getString("direction")), o.optString("sourceUri").ifBlank { null }, o.optString("profileId").ifBlank { null }, o.optLong("total"), o.optLong("transferred"),
        runCatching { TransferState.valueOf(o.getString("state")) }.getOrDefault(TransferState.Queued), o.optString("error").ifBlank { null }, o.optLong("created"), o.optLong("updated"))
}
