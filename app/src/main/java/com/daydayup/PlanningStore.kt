package com.daydayup

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

data class PlanSet(val reps: Int = 10, val kg: Double = 10.0)
data class PlanExercise(val name: String, val sets: List<PlanSet> = List(3) { PlanSet() })
data class CourseTemplate(val id: String, val name: String, val type: String, val bpm: Int = 180,
                          val exercises: List<PlanExercise> = emptyList(), val upSeconds: Int = 2,
                          val downSeconds: Int = 2, val setRestSeconds: Int = 60, val exerciseRestSeconds: Int = 90)
data class WeeklyPlan(val templates: List<CourseTemplate> = emptyList(), val days: Map<Int, List<String>> = emptyMap()) {
    fun coursesFor(day: Int) = days[day].orEmpty().mapNotNull { id -> templates.find { it.id == id } }
}
fun parseKg(text: String): Double? = text.toDoubleOrNull()?.takeIf { it >= 0.0 && it <= 500.0 }

data class WeightEntry(val id: Long, val date: String, val kg: Double)
data class FitnessTest(val id: Long, val date: String, val name: String, val result: Double,
                       val unit: String, val kg: Double? = null, val side: String = "", val note: String = "")
data class PersonalProfile(val birthMonth: String = "", val heightCm: Int? = null, val sex: String = "",
                           val goal: String = "", val experience: String = "", val availableDays: Set<Int> = emptySet(),
                           val weights: List<WeightEntry> = emptyList(), val tests: List<FitnessTest> = emptyList(),
                           val updatedAt: Long = 0L)

class PlanningStore(context: Context) {
    private val prefs = context.getSharedPreferences("planning_v1", Context.MODE_PRIVATE)
    var plan by mutableStateOf(readPlan())
        private set
    var profile by mutableStateOf(readProfile())
        private set

    fun updatePlan(value: WeeklyPlan) { plan = value; prefs.edit().putString("plan", encodePlan(value).toString()).apply() }
    fun updateProfile(value: PersonalProfile) { profile = value; prefs.edit().putString("profile", encodeProfile(value).toString()).apply() }
    fun clear() { prefs.edit().clear().apply(); plan = WeeklyPlan(); profile = PersonalProfile() }
    fun exportJson(): String = JSONObject().put("plan", encodePlan(plan)).put("profile", encodeProfile(profile)).toString(2)

    private fun readPlan(): WeeklyPlan = runCatching {
        val json = JSONObject(prefs.getString("plan", "{}") ?: "{}")
        val templates = json.optJSONArray("templates") ?: JSONArray()
        val days = json.optJSONObject("days") ?: JSONObject()
        WeeklyPlan((0 until templates.length()).map { i ->
            val t = templates.getJSONObject(i)
            val exercises = t.optJSONArray("exercises") ?: JSONArray()
            CourseTemplate(t.getString("id"), t.getString("name"), t.getString("type"), t.optInt("bpm", 180),
                (0 until exercises.length()).map { e ->
                    val ex = exercises.getJSONObject(e); val sets = ex.optJSONArray("sets") ?: JSONArray()
                    PlanExercise(ex.getString("name"), (0 until sets.length()).map { s ->
                        val set = sets.getJSONObject(s); PlanSet(set.getInt("reps"), set.getDouble("kg"))
                    })
                }, t.optInt("upSeconds", 2), t.optInt("downSeconds", 2), t.optInt("setRestSeconds", 60), t.optInt("exerciseRestSeconds", 90))
        }, (1..7).associateWith { day ->
            val values = days.optJSONArray(day.toString()) ?: JSONArray()
            (0 until values.length()).map { values.getString(it) }
        })
    }.getOrDefault(WeeklyPlan())

    private fun encodePlan(value: WeeklyPlan) = JSONObject().put("templates", JSONArray().apply {
        value.templates.forEach { t -> put(JSONObject().put("id", t.id).put("name", t.name).put("type", t.type).put("bpm", t.bpm)
            .put("upSeconds", t.upSeconds).put("downSeconds", t.downSeconds)
            .put("setRestSeconds", t.setRestSeconds).put("exerciseRestSeconds", t.exerciseRestSeconds)
            .put("exercises", JSONArray().apply { t.exercises.forEach { ex -> put(JSONObject().put("name", ex.name)
                .put("sets", JSONArray().apply { ex.sets.forEach { s -> put(JSONObject().put("reps", s.reps).put("kg", s.kg)) } })) } })) }
    }).put("days", JSONObject().apply { value.days.forEach { (day, ids) -> put(day.toString(), JSONArray(ids)) } })

    private fun readProfile(): PersonalProfile = runCatching {
        val json = JSONObject(prefs.getString("profile", "{}") ?: "{}")
        val weights = json.optJSONArray("weights") ?: JSONArray()
        val tests = json.optJSONArray("tests") ?: JSONArray()
        val days = json.optJSONArray("availableDays") ?: JSONArray()
        PersonalProfile(json.optString("birthMonth"), json.optInt("heightCm").takeIf { it > 0 }, json.optString("sex"),
            json.optString("goal"), json.optString("experience"), (0 until days.length()).map { days.getInt(it) }.toSet(),
            (0 until weights.length()).map { i -> weights.getJSONObject(i).let { WeightEntry(it.getLong("id"), it.getString("date"), it.getDouble("kg")) } },
            (0 until tests.length()).map { i -> tests.getJSONObject(i).let { FitnessTest(it.getLong("id"), it.getString("date"), it.getString("name"),
                it.getDouble("result"), it.getString("unit"), if (it.isNull("kg")) null else it.optDouble("kg"), it.optString("side"), it.optString("note")) } }, json.optLong("updatedAt"))
    }.getOrDefault(PersonalProfile())

    private fun encodeProfile(value: PersonalProfile) = JSONObject().put("birthMonth", value.birthMonth)
        .put("heightCm", value.heightCm).put("sex", value.sex).put("goal", value.goal).put("experience", value.experience)
        .put("updatedAt", value.updatedAt)
        .put("availableDays", JSONArray(value.availableDays.toList())).put("weights", JSONArray().apply {
            value.weights.forEach { put(JSONObject().put("id", it.id).put("date", it.date).put("kg", it.kg)) }
        }).put("tests", JSONArray().apply {
            value.tests.forEach { put(JSONObject().put("id", it.id).put("date", it.date).put("name", it.name)
                .put("result", it.result).put("unit", it.unit).put("kg", it.kg).put("side", it.side).put("note", it.note)) }
        })
}
