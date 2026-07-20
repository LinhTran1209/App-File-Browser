package com.j2team.fileserver.feature.transfers

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Small durable queue; the worker can update it as bytes are streamed. */
class TransferStore(context: Context) {
    private val prefs = context.getSharedPreferences("transfer_queue", Context.MODE_PRIVATE)

    fun all(): List<TransferTask> = runCatching {
        val json = JSONArray(prefs.getString("items", "[]") ?: "[]")
        (0 until json.length()).map { fromJson(json.getJSONObject(it)) }.sortedByDescending { it.updatedAt }
    }.getOrDefault(emptyList())

    fun enqueue(name: String, path: String, direction: TransferDirection, totalBytes: Long = 0L): TransferTask =
        save(TransferTask(UUID.randomUUID().toString(), name, path, direction, totalBytes = totalBytes))

    fun save(task: TransferTask): TransferTask {
        val updated = task.copy(updatedAt = System.currentTimeMillis())
        val items = all().filterNot { it.id == updated.id } + updated
        prefs.edit().putString("items", JSONArray().apply { items.forEach { put(toJson(it)) } }.toString()).apply()
        return updated
    }

    fun update(id: String, transferredBytes: Long, state: TransferState? = null, error: String? = null): TransferTask? =
        all().firstOrNull { it.id == id }?.let { save(it.copy(transferredBytes = transferredBytes, state = state ?: it.state, error = error)) }

    fun remove(id: String) { prefs.edit().putString("items", JSONArray().apply { all().filterNot { it.id == id }.forEach { put(toJson(it)) } }.toString()).apply() }
    fun active(): List<TransferTask> = all().filter { it.isActive }

    private fun toJson(t: TransferTask) = JSONObject().apply {
        put("id", t.id); put("name", t.name); put("path", t.path); put("direction", t.direction.name)
        put("total", t.totalBytes); put("transferred", t.transferredBytes); put("state", t.state.name)
        put("error", t.error); put("created", t.createdAt); put("updated", t.updatedAt)
    }
    private fun fromJson(o: JSONObject) = TransferTask(o.getString("id"), o.getString("name"), o.getString("path"),
        TransferDirection.valueOf(o.getString("direction")), o.optLong("total"), o.optLong("transferred"),
        runCatching { TransferState.valueOf(o.getString("state")) }.getOrDefault(TransferState.Queued), o.optString("error").ifBlank { null }, o.optLong("created"), o.optLong("updated"))
}
