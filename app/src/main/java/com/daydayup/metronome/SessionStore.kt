package com.daydayup.metronome

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class SessionRecord(
    val id: Long,
    val name: String,
    val startedAt: Long,
    val endedAt: Long,
    val durationMs: Long,
    val initialBpm: Int,
    val changes: List<RateChange>,
    val interrupted: Boolean = false,
)

class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("real_metronome_sessions", Context.MODE_PRIVATE)

    fun records(): List<SessionRecord> {
        val data = JSONArray(prefs.getString("records", "[]"))
        return (0 until data.length()).map { decode(data.getJSONObject(it)) }.sortedByDescending { it.startedAt }
    }

    fun begin(id: Long, startedAt: Long, bpm: Int) {
        prefs.edit().putString("active", JSONObject().put("id", id).put("startedAt", startedAt)
            .put("bpm", bpm).put("duration", 0L).put("checkpointWall", startedAt)
            .put("changes", JSONArray()).toString()).apply()
    }

    fun checkpoint(durationMs: Long, changes: List<RateChange>) {
        val active = prefs.getString("active", null) ?: return
        val json = JSONObject(active).put("duration", durationMs)
            .put("checkpointWall", System.currentTimeMillis()).put("changes", encodeChanges(changes))
        prefs.edit().putString("active", json.toString()).apply()
    }

    fun finish(endedAt: Long, durationMs: Long, changes: List<RateChange>) {
        val active = prefs.getString("active", null) ?: return
        val json = JSONObject(active)
        val record = SessionRecord(json.getLong("id"), "节拍器训练", json.getLong("startedAt"),
            endedAt, durationMs, json.getInt("bpm"), changes)
        save(record)
        prefs.edit().remove("active").apply()
    }

    fun recoverInterrupted() {
        val active = prefs.getString("active", null) ?: return
        val json = JSONObject(active)
        val duration = json.optLong("duration", 0L)
        save(SessionRecord(json.getLong("id"), "节拍器训练", json.getLong("startedAt"),
            json.optLong("checkpointWall", json.getLong("startedAt") + duration), duration, json.getInt("bpm"),
            decodeChanges(json.optJSONArray("changes") ?: JSONArray()), interrupted = true))
        prefs.edit().remove("active").apply()
    }

    fun rename(id: Long, name: String) = rewrite { if (it.id == id) it.copy(name = name.trim().ifEmpty { it.name }) else it }
    fun delete(id: Long) {
        val values = records().filterNot { it.id == id }
        prefs.edit().putString("records", JSONArray().apply { values.forEach { put(encode(it)) } }.toString()).apply()
    }

    fun exportJson(): String = JSONArray().apply { records().forEach { put(encode(it)) } }.toString(2)

    private fun save(record: SessionRecord) {
        val all = records().filterNot { it.id == record.id } + record
        prefs.edit().putString("records", JSONArray().apply { all.forEach { put(encode(it)) } }.toString()).apply()
    }

    private fun rewrite(transform: (SessionRecord) -> SessionRecord) {
        val all = records().map(transform)
        prefs.edit().putString("records", JSONArray().apply { all.forEach { put(encode(it)) } }.toString()).apply()
    }

    private fun encodeChanges(changes: List<RateChange>) = JSONArray().apply {
        changes.forEach { put(JSONObject().put("elapsedMs", it.elapsedMs).put("bpm", it.bpm)) }
    }
    private fun decodeChanges(values: JSONArray) = (0 until values.length()).map {
        values.getJSONObject(it).let { change -> RateChange(change.getLong("elapsedMs"), change.getInt("bpm")) }
    }
    private fun encode(value: SessionRecord) = JSONObject().put("id", value.id).put("name", value.name)
        .put("startedAt", value.startedAt).put("endedAt", value.endedAt)
        .put("durationMs", value.durationMs).put("initialBpm", value.initialBpm)
        .put("changes", encodeChanges(value.changes)).put("interrupted", value.interrupted)
    private fun decode(value: JSONObject) = SessionRecord(value.getLong("id"), value.getString("name"),
        value.getLong("startedAt"), value.getLong("endedAt"), value.getLong("durationMs"),
        value.getInt("initialBpm"), decodeChanges(value.getJSONArray("changes")), value.optBoolean("interrupted"))
}
