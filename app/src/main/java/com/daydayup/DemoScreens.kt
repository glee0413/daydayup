package com.daydayup

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

data class DemoSet(val reps: Int = 10, val kg: Double = 10.0)
data class DemoExercise(val name: String, val sets: List<DemoSet> = List(3) { DemoSet() })
data class DemoResult(val exercise: String, val reps: Int, val kg: Double, val difficulty: Int, val skipped: Boolean)
data class DemoTemplate(val name: String, val day: String, val exercises: List<DemoExercise>)

private fun defaultTemplate() = DemoTemplate("上肢力量 A", "周一",
    listOf(DemoExercise("二头弯举"), DemoExercise("三头肌伸展", List(3) { DemoSet(10, 8.0) })))

class DemoState {
    var templates by mutableStateOf(listOf(defaultTemplate()))
    var selectedTemplateIndex by mutableIntStateOf(0)
    var templateName: String
        get() = templates[selectedTemplateIndex].name
        set(value) { updateTemplate { it.copy(name = value) } }
    var scheduledDay: String
        get() = templates[selectedTemplateIndex].day
        set(value) { updateTemplate { it.copy(day = value) } }
    var exercises: List<DemoExercise>
        get() = templates[selectedTemplateIndex].exercises
        set(value) { updateTemplate { it.copy(exercises = value) } }
    var workoutExercises by mutableStateOf(emptyList<DemoExercise>())
    var exerciseIndex by mutableIntStateOf(0)
    var setIndex by mutableIntStateOf(0)
    var phase by mutableStateOf("notStarted")
    var seconds by mutableIntStateOf(0)
    var rep by mutableIntStateOf(1)
    var completed by mutableIntStateOf(0)
    var skipped by mutableIntStateOf(0)
    var actualReps by mutableIntStateOf(10)
    var actualKg by mutableDoubleStateOf(10.0)
    var difficulty by mutableIntStateOf(3)
    var results by mutableStateOf(emptyList<DemoResult>())
    var coachSuggestion by mutableStateOf(false)
    var coachFromWorkout by mutableStateOf(false)
    var connectionPreview by mutableStateOf(false)
    var reportPeriod by mutableStateOf("周")
    var upSeconds by mutableIntStateOf(2)
    var downSeconds by mutableIntStateOf(2)
    var setRestSeconds by mutableIntStateOf(60)
    var exerciseRestSeconds by mutableIntStateOf(90)
    private var savedPhase = "prepare"
    private var savedSeconds = 5

    private fun updateTemplate(change: (DemoTemplate) -> DemoTemplate) {
        templates = templates.toMutableList().also { it[selectedTemplateIndex] = change(it[selectedTemplateIndex]) }
    }
    fun copyTemplate() {
        templates = templates + templates[selectedTemplateIndex].copy(name = "${templateName} 副本")
        selectedTemplateIndex = templates.lastIndex
    }

    fun reset() {
        templates = listOf(defaultTemplate()); selectedTemplateIndex = 0; workoutExercises = emptyList()
        exerciseIndex = 0; setIndex = 0; phase = "notStarted"; seconds = 0; rep = 1
        completed = 0; skipped = 0; results = emptyList(); coachSuggestion = false; coachFromWorkout = false; connectionPreview = false
        upSeconds = 2; downSeconds = 2; setRestSeconds = 60; exerciseRestSeconds = 90
    }

    fun addExercise(name: String) { if (name.isNotBlank()) exercises = exercises + DemoExercise(name.trim()) }
    fun updateSet(exercise: Int, set: Int, reps: Int, kg: Double) {
        if (exercise !in exercises.indices || set !in exercises[exercise].sets.indices) return
        exercises = exercises.toMutableList().also { list ->
            list[exercise] = list[exercise].copy(sets = list[exercise].sets.toMutableList().also { it[set] = DemoSet(reps.coerceAtLeast(1), kg.coerceAtLeast(0.0)) })
        }
    }
    fun addSet(exercise: Int) {
        if (exercise !in exercises.indices) return
        exercises = exercises.toMutableList().also { list -> list[exercise] = list[exercise].copy(sets = list[exercise].sets + list[exercise].sets.last()) }
    }
    fun start() { workoutExercises = exercises; exerciseIndex = 0; setIndex = 0; rep = 1; completed = 0; skipped = 0; results = emptyList(); phase = "prepare"; seconds = 5 }
    fun advance() {
        if (setIndex + 1 < workoutExercises[exerciseIndex].sets.size) {
            setIndex++; phase = "rest"; seconds = setRestSeconds
        } else if (exerciseIndex + 1 < workoutExercises.size) {
            exerciseIndex++; setIndex = 0; phase = "rest"; seconds = exerciseRestSeconds
        } else { phase = "done"; seconds = 0 }
    }
    fun result(kind: String) {
        val exercise = workoutExercises[exerciseIndex]
        results = results + DemoResult(exercise.name, if (kind == "跳过") 0 else actualReps,
            if (kind == "跳过") 0.0 else actualKg, difficulty, kind == "跳过")
        if (kind == "跳过") skipped++ else completed++
        advance()
    }
    fun pause(reason: String) {
        if (phase in listOf("prepare", "up", "down", "rest")) {
            savedPhase = phase; savedSeconds = seconds; phase = reason
        }
    }
    fun resume() {
        if (phase in listOf("paused", "pausedCoach", "phone")) {
            phase = savedPhase; seconds = savedSeconds
        }
    }
    fun tick() {
        if (phase !in listOf("prepare", "up", "down", "rest")) return
        if (seconds > 1) { seconds--; return }
        when (phase) {
            "rest" -> { phase = "prepare"; seconds = 5 }
            "prepare" -> { phase = "up"; seconds = upSeconds; rep = 1 }
            "up" -> { phase = "down"; seconds = downSeconds }
            "down" -> if (rep >= workoutExercises[exerciseIndex].sets[setIndex].reps) {
                actualReps = rep; actualKg = workoutExercises[exerciseIndex].sets[setIndex].kg
                phase = "confirm"; seconds = 0
            }
                else { rep++; phase = "up"; seconds = upSeconds }
        }
    }
}

@Composable
fun DemoScreens(page: String, state: DemoState, navigate: (String) -> Unit,
                realMetronomeActive: Boolean, openMetronome: () -> Unit) {
    when (page) {
        "课程" -> CourseDemo(state, navigate, realMetronomeActive, openMetronome)
        "训练" -> WorkoutDemo(state, navigate)
        "教练" -> CoachDemo(state, navigate)
        "报告" -> ReportDemo(state, navigate)
        "设备" -> DeviceDemo(state, navigate)
    }
}

@Composable
fun CourseDemo(state: DemoState, navigate: (String) -> Unit, realMetronomeActive: Boolean, openMetronome: () -> Unit) {
    var newExercise by remember { mutableStateOf("") }
    ScreenColumn {
        DemoBanner()
        Text("每周课表", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text("先建课程模板，再安排到一周中的某一天。此处修改只用于演示。", color = Muted)
        state.templates.forEachIndexed { index, template ->
            FilterChip(state.selectedTemplateIndex == index,
                onClick = { state.selectedTemplateIndex = index },
                label = { Text("${template.day} · ${template.name}") })
        }
        SectionCard {
            OutlinedTextField(state.templateName, onValueChange = { state.templateName = it }, label = { Text("课程模板") }, modifier = Modifier.fillMaxWidth())
            Text("安排到", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("周一", "周二", "周三", "周四").forEach { day ->
                    FilterChip(state.scheduledDay == day, onClick = { state.scheduledDay = day }, label = { Text(day) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("周五", "周六", "周日").forEach { day ->
                    FilterChip(state.scheduledDay == day, onClick = { state.scheduledDay = day }, label = { Text(day) })
                }
            }
            TextButton(onClick = { state.copyTemplate() }) { Text("复制模板") }
        }
        state.exercises.forEachIndexed { index, exercise ->
            SectionCard {
                Text("${index + 1}. ${exercise.name}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
                exercise.sets.forEachIndexed { setIndex, set ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("第 ${setIndex + 1} 组", Modifier.width(64.dp), color = Muted)
                        TextButton(onClick = { state.updateSet(index, setIndex, set.reps - 1, set.kg) }) { Text("−") }
                        Text("${set.reps} 次", Modifier.width(45.dp))
                        TextButton(onClick = { state.updateSet(index, setIndex, set.reps + 1, set.kg) }) { Text("+") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("重量", Modifier.width(64.dp), color = Muted)
                        TextButton(onClick = { state.updateSet(index, setIndex, set.reps, set.kg - 0.5) }) { Text("−") }
                        Text("${set.kg} 公斤", Modifier.width(65.dp))
                        TextButton(onClick = { state.updateSet(index, setIndex, set.reps, set.kg + 0.5) }) { Text("+") }
                    }
                }
                TextButton(onClick = { state.addSet(index) }) { Text("＋ 添加一组") }
            }
        }
        SectionCard {
            OutlinedTextField(newExercise, onValueChange = { newExercise = it }, label = { Text("自定义动作名称") }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { state.addExercise(newExercise); newExercise = "" }) { Text("添加动作") }
        }
        SectionCard {
            Text("指导节奏", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            DemoNumberControl("起（秒）", state.upSeconds, { state.upSeconds = it.coerceIn(1, 10) }, 1)
            DemoNumberControl("落（秒）", state.downSeconds, { state.downSeconds = it.coerceIn(1, 10) }, 1)
            DemoNumberControl("组间休息（秒）", state.setRestSeconds, { state.setRestSeconds = it.coerceIn(5, 300) }, 5)
            DemoNumberControl("动作间休息（秒）", state.exerciseRestSeconds, { state.exerciseRestSeconds = it.coerceIn(5, 300) }, 5)
            Text("每组开始前固定准备 5 秒。", color = Muted, fontSize = 12.sp)
        }
        TextButton(onClick = { state.coachFromWorkout = false; navigate("教练") }) { Text("训练前咨询教练（演示）") }
        if (realMetronomeActive) SectionCard {
            Text("节拍器正在运行", fontWeight = FontWeight.Bold)
            Text("请先结束节拍器，再开始演示训练。", color = Muted)
            TextButton(onClick = openMetronome) { Text("返回节拍器") }
        } else ActionButton("开始演示训练", { state.start(); navigate("训练") }, Modifier.fillMaxWidth())
    }
}

@Composable
fun DemoNumberControl(label: String, value: Int, update: (Int) -> Unit, step: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f), color = Muted)
        TextButton(onClick = { update(value - step) }) { Text("−") }
        Text("$value", Modifier.width(34.dp))
        TextButton(onClick = { update(value + step) }) { Text("+") }
    }
}

@Composable
fun WorkoutDemo(state: DemoState, navigate: (String) -> Unit) {
    LaunchedEffect(state.phase, state.seconds) {
        if (state.phase in listOf("prepare", "up", "down", "rest")) { delay(1_000); state.tick() }
    }
    LaunchedEffect(state.phase) {
        if (state.phase == "phone") { delay(3_000); state.resume() }
    }
    val exercise = state.workoutExercises.getOrNull(state.exerciseIndex)
    val set = exercise?.sets?.getOrNull(state.setIndex)
    ScreenColumn {
        DemoBanner()
        Text("训练指导", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Ink)
        if (state.phase == "notStarted") ActionButton("开始演示训练", { state.start() }, Modifier.fillMaxWidth())
        else if (state.phase == "done") {
            SectionCard {
                Text("本次演示已结束", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("已确认 ${state.completed} 组 · 跳过 ${state.skipped} 组", color = Muted)
                Text("这不是你的真实训练记录。", color = Muted)
            }
            ActionButton("查看示例报告", { navigate("报告") }, Modifier.fillMaxWidth())
        } else {
            SectionCard {
                Text(exercise?.name ?: "动作", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text("第 ${state.setIndex + 1} / ${exercise?.sets?.size ?: 0} 组 · 目标 ${set?.reps ?: 0} 次 · ${set?.kg ?: 0} 公斤", color = Muted)
                Text(when (state.phase) {
                    "prepare" -> "准备开始"; "up" -> "起 · 第 ${state.rep} 次"; "down" -> "落 · 第 ${state.rep} 次"
                    "rest" -> "休息中"; "confirm" -> "请确认本组结果"; "paused", "pausedCoach" -> "已暂停"
                    "phone" -> "模拟来电 · 自动恢复中"
                    else -> state.phase
                }, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = if (state.phase == "prepare" || state.phase == "paused") Amber else Teal)
                if (state.seconds > 0) Text("${state.seconds} 秒", fontSize = 42.sp, fontWeight = FontWeight.Bold, color = Ink)
                if (state.phase == "rest" && state.seconds <= 3) Text("准备进入下一组", color = Amber)
                if (state.phase == "confirm") {
                    DemoNumberControl("实际次数", state.actualReps, { state.actualReps = it.coerceAtLeast(0) }, 1)
                    Row { Text("实际重量 ${state.actualKg} 公斤", Modifier.weight(1f));
                        TextButton(onClick = { state.actualKg = (state.actualKg - 0.5).coerceAtLeast(0.0) }) { Text("−") }
                        TextButton(onClick = { state.actualKg += 0.5 }) { Text("+") } }
                    DemoNumberControl("主观难度（1–5）", state.difficulty, { state.difficulty = it.coerceIn(1, 5) }, 1)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { state.result("完成") }) { Text("完成") }
                        OutlinedButton(onClick = { state.result("少做") }) { Text("少做") }
                        OutlinedButton(onClick = { state.result("跳过") }) { Text("跳过") }
                    }
                }
                if (state.phase == "rest") TextButton(onClick = { state.seconds = 1 }) { Text("跳过等待（演示）") }
            }
            SectionCard {
                Text("训练控制", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (state.phase in listOf("paused", "pausedCoach"))
                        OutlinedButton(onClick = { state.resume() }) { Text("继续") }
                    else OutlinedButton(onClick = { state.pause("paused") }) { Text("暂停") }
                    OutlinedButton(onClick = { state.result("跳过") }) { Text("跳过本组") }
                }
                TextButton(onClick = { state.pause("phone") }) { Text("模拟来电（3 秒后自动恢复）") }
                TextButton(onClick = { state.coachFromWorkout = true; state.pause("pausedCoach"); navigate("教练") }) { Text("呼叫教练（演示）") }
                TextButton(onClick = { state.phase = "done" }) { Text("提前结束") }
            }
            Text("来电演示：指导与计时暂停；通话结束后自动恢复。AI 教练演示：结束后手动继续。", color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
fun CoachDemo(state: DemoState, navigate: (String) -> Unit) {
    ScreenColumn {
        DemoBanner()
        Text("体能教练", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text("语音通话交互预览 · 当前未连接 AI，也不会使用麦克风。", color = Muted)
        SectionCard {
            Text("训练前可以咨询；训练中可以询问下一步。真实通话将需要联网，并在开始前说明数据处理方式。", color = Ink)
            Text("示例提问：今天状态一般，二头弯举该减重吗？", color = Muted)
            ActionButton("模拟教练建议", { state.coachSuggestion = true }, Modifier.fillMaxWidth())
        }
        if (state.coachSuggestion) SectionCard {
            Text("建议待确认", fontWeight = FontWeight.Bold, color = Ink)
            Text("演示建议：二头弯举每组重量降低 2 公斤。", color = Muted)
            Text("只有确认后才会修改本次演示课程。", color = Muted, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    state.workoutExercises = state.workoutExercises.mapIndexed { index, exercise ->
                        if (index == 0) exercise.copy(sets = exercise.sets.map { it.copy(kg = (it.kg - 2.0).coerceAtLeast(0.0)) }) else exercise
                    }
                    state.coachSuggestion = false
                }) { Text("确认修改") }
                OutlinedButton(onClick = { state.coachSuggestion = false }) { Text("保持原计划") }
            }
        }
        ActionButton("结束演示通话", {
            if (state.coachFromWorkout) { state.coachFromWorkout = false; navigate("训练") } else navigate("课程")
        }, Modifier.fillMaxWidth(), secondary = true)
        Text("疼痛或伤病时，应停止当前动作并寻求专业评估。", color = Muted, fontSize = 12.sp)
    }
}

@Composable
fun ReportDemo(state: DemoState, navigate: (String) -> Unit) {
    ScreenColumn {
        DemoBanner()
        Text("训练报告", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text("以下数值均为示例，不代表你的真实训练效果。", color = Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("周", "月", "年").forEach { period -> FilterChip(state.reportPeriod == period,
                onClick = { state.reportPeriod = period }, label = { Text(period) }) }
        }
        SectionCard {
            Text("计划与实际 · 示例", fontWeight = FontWeight.Bold)
            Text(when (state.reportPeriod) { "周" -> "计划 3 次 / 完成 2 次"; "月" -> "计划 12 次 / 完成 9 次"; else -> "计划 144 次 / 完成 108 次" },
                fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text("周期：${if (state.reportPeriod == "周") "周一至周日" else "自然${state.reportPeriod}"}，按实际完成日期归档。", color = Muted)
        }
        SectionCard {
            Text("训练量趋势 · 示例", fontWeight = FontWeight.Bold)
            Text("组次、重量和时长将在真实训练记录可用后计算。", color = Muted)
        }
        SectionCard {
            Text("主观感受 · 示例", fontWeight = FontWeight.Bold)
            Text("难度与恢复需要你确认；数据不足时不判断体能提升。", color = Muted)
        }
        TextButton(onClick = { navigate("设备") }) { Text("了解未来设备数据接入 →") }
    }
}

@Composable
fun DeviceDemo(state: DemoState, navigate: (String) -> Unit) {
    ScreenColumn {
        DemoBanner()
        Text("连接测量设备", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text("未来可按需连接手表等设备，加入心率等真实测量指标。", color = Muted)
        SectionCard {
            Text("授权预览", fontWeight = FontWeight.Bold)
            Text("接入时会先说明读取哪些数据，由你主动授权。当前不会扫描设备或获取健康数据。", color = Muted)
            ActionButton(if (state.connectionPreview) "已查看授权说明" else "查看连接流程", { state.connectionPreview = true }, Modifier.fillMaxWidth())
            if (state.connectionPreview) Text("示例流程：选择设备 → 查看数据范围 → 授权 → 同步状态。", color = Teal)
        }
        TextButton(onClick = { navigate("报告") }) { Text("← 返回报告") }
    }
}
