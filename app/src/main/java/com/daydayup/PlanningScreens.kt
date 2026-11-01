package com.daydayup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

private val weekdays = listOf("一", "二", "三", "四", "五", "六", "日")
private fun todayText() = LocalDate.now().toString()

@Composable
fun CourseCalendar(store: PlanningStore, openSettings: () -> Unit, startRun: (Int) -> Unit,
                   startStrength: (CourseTemplate) -> Unit, metronomeActive: Boolean) {
    var selected by remember { mutableStateOf(LocalDate.now()) }
    var week by remember { mutableStateOf(LocalDate.now().with(DayOfWeek.MONDAY)) }
    var detail by remember { mutableStateOf<CourseTemplate?>(null) }
    val courses = store.plan.coursesFor(selected.dayOfWeek.value)
    ScreenColumn {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("每周课程", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Ink)
            TextButton(onClick = openSettings) { Text("课表设置") }
        }
        Text("${week.monthValue}月${week.dayOfMonth}日 — ${week.plusDays(6).monthValue}月${week.plusDays(6).dayOfMonth}日", color = Muted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { week = week.minusWeeks(1); selected = week }) { Text("‹ 上周") }
            TextButton(onClick = { week = LocalDate.now().with(DayOfWeek.MONDAY); selected = LocalDate.now() }) { Text("今天") }
            TextButton(onClick = { week = week.plusWeeks(1); selected = week }) { Text("下周 ›") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            (0L..6L).forEach { offset ->
                val date = week.plusDays(offset)
                WeekCell(weekdays[offset.toInt()], "${date.dayOfMonth}", selected == date, Modifier.weight(1f)) { selected = date }
            }
        }
        Text("${selected.monthValue}月${selected.dayOfMonth}日 · ${weekdays[selected.dayOfWeek.value - 1]}", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("以下是每周重复的计划安排；力量训练执行仍为演示，不计入真实完成记录。", color = Muted, fontSize = 12.sp)
        if (courses.isEmpty()) SectionCard {
            Text("这一天还没有安排课程", color = Muted)
            TextButton(onClick = openSettings) { Text("去配置课程 →") }
        }
        courses.forEach { course ->
            SectionCard {
                Text(course.name, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(if (course.type == "run") "户外跑步 · 建议 ${course.bpm} 步/分钟" else "力量训练 · 演示执行", color = Muted)
                if (course.type == "strength" && metronomeActive) Text("请先结束正在进行的节拍会话", color = Muted)
                Row {
                    TextButton(onClick = { detail = course }) { Text("详情") }
                    TextButton(onClick = { if (course.type == "run") startRun(course.bpm) else startStrength(course) },
                        enabled = course.type == "run" || !metronomeActive) { Text("▶ 开始") }
                }
            }
        }
    }
    detail?.let { course -> AlertDialog(onDismissRequest = { detail = null }, title = { Text(course.name) },
        text = { Text(if (course.type == "run") "建议步频 ${course.bpm}。进入跑步控制页后仍需手动点击开始。"
            else course.exercises.joinToString("\n") { "${it.name} · ${it.sets.size} 组" }.ifEmpty { "尚未添加动作" }) },
        confirmButton = { TextButton(onClick = { detail = null }) { Text("关闭") } }) }
}

@Composable
fun CourseSettings(store: PlanningStore, back: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("strength") }
    var deleting by remember { mutableStateOf<CourseTemplate?>(null) }
    ScreenColumn {
        TextButton(onClick = back) { Text("← 返回课程日历") }
        Text("课表设置", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("课程和周安排保存在本机。修改模板会更新所有引用它的日期。", color = Muted)
        SectionCard {
            OutlinedTextField(name, { name = it }, label = { Text("新课程名称") }, modifier = Modifier.fillMaxWidth())
            Row { FilterChip(type == "strength", onClick = { type = "strength" }, label = { Text("力量") }); Spacer(Modifier.width(8.dp));
                FilterChip(type == "run", onClick = { type = "run" }, label = { Text("跑步") }) }
            ActionButton("添加课程模板", {
                if (name.isNotBlank()) {
                    val course = CourseTemplate(System.currentTimeMillis().toString(), name.trim(), type,
                        exercises = if (type == "strength") listOf(PlanExercise("二头弯举")) else emptyList())
                    store.updatePlan(store.plan.copy(templates = store.plan.templates + course)); name = ""
                }
            }, Modifier.fillMaxWidth())
        }
        store.plan.templates.forEach { course ->
            key(course.id) {
                SectionCard {
                    var editedName by remember(course.id) { mutableStateOf(course.name) }
                    OutlinedTextField(editedName, { editedName = it }, label = { Text("课程名称") }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { if (editedName.isNotBlank()) store.updatePlan(store.plan.copy(templates = store.plan.templates.map {
                        if (it.id == course.id) it.copy(name = editedName.trim()) else it })) }) { Text("保存名称") }
                    if (course.type == "run") {
                        Text("建议步频 ${course.bpm}")
                        Slider(course.bpm.toFloat(), onValueChange = { value ->
                            val bpm = (value.toInt() / 5 * 5).coerceIn(160, 220)
                            store.updatePlan(store.plan.copy(templates = store.plan.templates.map { if (it.id == course.id) it.copy(bpm = bpm) else it }))
                        }, valueRange = 160f..220f, steps = 11)
                    } else {
                        course.exercises.forEachIndexed { exIndex, ex ->
                            var exName by remember(course.id, exIndex) { mutableStateOf(ex.name) }
                            Row {
                                OutlinedTextField(exName, { exName = it }, label = { Text("动作") }, modifier = Modifier.weight(1f))
                                TextButton(onClick = { if (exName.isNotBlank()) changeExercise(store, course, exIndex, ex.copy(name = exName.trim())) }) { Text("保存") }
                            }
                            ex.sets.forEachIndexed { setIndex, set ->
                                var kgText by remember(course.id, exIndex, setIndex) { mutableStateOf(set.kg.toString()) }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("${setIndex + 1}组 ${set.reps}次", Modifier.weight(1f))
                                    TextButton(onClick = { changeSet(store, course, exIndex, setIndex, set.copy(reps = (set.reps - 1).coerceAtLeast(1))) }) { Text("−") }
                                    TextButton(onClick = { changeSet(store, course, exIndex, setIndex, set.copy(reps = set.reps + 1)) }) { Text("+") }
                                }
                                Row {
                                    OutlinedTextField(kgText, { kgText = it }, label = { Text("公斤") }, singleLine = true, modifier = Modifier.width(130.dp))
                                    TextButton(onClick = { parseKg(kgText)?.let { kg -> changeSet(store, course, exIndex, setIndex, set.copy(kg = kg)) } }) { Text("应用") }
                                    TextButton(onClick = { changeSet(store, course, exIndex, setIndex, set.copy(kg = (set.kg + 0.5).coerceAtMost(500.0))); kgText = (set.kg + 0.5).toString() }) { Text("+0.5") }
                                }
                                if (ex.sets.size > 1) TextButton(onClick = {
                                    changeExercise(store, course, exIndex, ex.copy(sets = ex.sets.filterIndexed { i, _ -> i != setIndex }))
                                }) { Text("删除此组") }
                            }
                            TextButton(onClick = { changeExercise(store, course, exIndex, ex.copy(sets = ex.sets + ex.sets.lastOrNull().let { it ?: PlanSet() })) }) { Text("＋ 添加一组") }
                            if (course.exercises.size > 1) TextButton(onClick = {
                                updateCourse(store, course.copy(exercises = course.exercises.filterIndexed { i, _ -> i != exIndex }))
                            }) { Text("删除动作") }
                        }
                        var exerciseName by remember(course.id) { mutableStateOf("") }
                        OutlinedTextField(exerciseName, { exerciseName = it }, label = { Text("新动作") })
                        TextButton(onClick = { if (exerciseName.isNotBlank()) {
                            updateCourse(store, course.copy(exercises = course.exercises + PlanExercise(exerciseName.trim()))); exerciseName = ""
                        } }) { Text("添加动作") }
                        Text("指导节奏", fontWeight = FontWeight.Bold)
                        TempoRow("起（秒）", course.upSeconds, 1, 10, 1) { updateCourse(store, course.copy(upSeconds = it)) }
                        TempoRow("落（秒）", course.downSeconds, 1, 10, 1) { updateCourse(store, course.copy(downSeconds = it)) }
                        TempoRow("组间休息（秒）", course.setRestSeconds, 5, 300, 5) { updateCourse(store, course.copy(setRestSeconds = it)) }
                        TempoRow("动作间休息（秒）", course.exerciseRestSeconds, 5, 300, 5) { updateCourse(store, course.copy(exerciseRestSeconds = it)) }
                        Text("每组开始前固定准备 5 秒。", color = Muted)
                    }
                    Text("安排到星期", fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        (1..7).forEach { day -> WeekCell(weekdays[day - 1], null, course.id in store.plan.days[day].orEmpty(), Modifier.weight(1f)) {
                            val ids = store.plan.days[day].orEmpty().toMutableList()
                            if (course.id in ids) ids.remove(course.id) else ids.add(course.id)
                            store.updatePlan(store.plan.copy(days = store.plan.days + (day to ids)))
                        } }
                    }
                    (1..7).filter { course.id in store.plan.days[it].orEmpty() }.forEach { day ->
                        val ids = store.plan.days[day].orEmpty()
                        if (ids.size > 1) Row {
                            Text("周${weekdays[day - 1]} · 第 ${ids.indexOf(course.id) + 1} 项", Modifier.weight(1f))
                            TextButton(onClick = { moveAssignment(store, day, course.id, -1) }) { Text("上移") }
                            TextButton(onClick = { moveAssignment(store, day, course.id, 1) }) { Text("下移") }
                        }
                    }
                    TextButton(onClick = { deleting = course }) { Text("删除模板") }
                }
            }
        }
    }
    deleting?.let { course ->
        val days = (1..7).filter { course.id in store.plan.days[it].orEmpty() }.joinToString("、") { "周${weekdays[it - 1]}" }
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除${course.name}？") },
            text = { Text(if (days.isEmpty()) "该模板尚未安排。" else "同时移除 $days 的安排。") },
            confirmButton = { TextButton(onClick = {
                store.updatePlan(store.plan.copy(templates = store.plan.templates.filterNot { it.id == course.id },
                    days = store.plan.days.mapValues { (_, ids) -> ids.filterNot { it == course.id } })); deleting = null
            }) { Text("删除") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
}

@Composable
private fun TempoRow(label: String, value: Int, min: Int, max: Int, step: Int, change: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("$label：$value", Modifier.weight(1f))
        TextButton(onClick = { change((value - step).coerceAtLeast(min)) }) { Text("−") }
        TextButton(onClick = { change((value + step).coerceAtMost(max)) }) { Text("+") }
    }
}

@Composable
private fun WeekCell(label: String, date: String?, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick).background(if (selected) Teal.copy(alpha = 0.18f) else Color.White,
        RoundedCornerShape(14.dp)).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, fontSize = 12.sp)
        if (date != null) Text(date, fontWeight = FontWeight.Bold)
    }
}

private fun moveAssignment(store: PlanningStore, day: Int, id: String, delta: Int) {
    val ids = store.plan.days[day].orEmpty().toMutableList()
    val from = ids.indexOf(id); val to = from + delta
    if (from < 0 || to !in ids.indices) return
    java.util.Collections.swap(ids, from, to)
    store.updatePlan(store.plan.copy(days = store.plan.days + (day to ids)))
}

private fun updateCourse(store: PlanningStore, course: CourseTemplate) {
    store.updatePlan(store.plan.copy(templates = store.plan.templates.map { if (it.id == course.id) course else it }))
}
private fun changeExercise(store: PlanningStore, course: CourseTemplate, index: Int, ex: PlanExercise) {
    updateCourse(store, course.copy(exercises = course.exercises.toMutableList().also { it[index] = ex }))
}
private fun changeSet(store: PlanningStore, course: CourseTemplate, exIndex: Int, setIndex: Int, value: PlanSet) {
    val ex = course.exercises[exIndex]
    changeExercise(store, course, exIndex, ex.copy(sets = ex.sets.toMutableList().also { it[setIndex] = value }))
}

@Composable
fun PersonalProfileScreen(store: PlanningStore, back: () -> Unit) {
    val profile = store.profile
    var birth by remember { mutableStateOf(profile.birthMonth) }
    var height by remember { mutableStateOf(profile.heightCm?.toString() ?: "") }
    var sex by remember { mutableStateOf(profile.sex) }
    var goal by remember { mutableStateOf(profile.goal) }
    var experience by remember { mutableStateOf(profile.experience) }
    var weight by remember { mutableStateOf("") }
    var weightDate by remember { mutableStateOf(todayText()) }
    var testName by remember { mutableStateOf("二头弯举") }
    var testUnit by remember { mutableStateOf("次数") }
    var testResult by remember { mutableStateOf("") }
    var testKg by remember { mutableStateOf("7.5") }
    var testSide by remember { mutableStateOf("双臂") }
    var testDate by remember { mutableStateOf(todayText()) }
    var note by remember { mutableStateOf("") }
    var editTest by remember { mutableStateOf<Long?>(null) }
    var deleteId by remember { mutableStateOf<Long?>(null) }
    var deleteWeight by remember { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf("") }
    ScreenColumn {
        TextButton(onClick = back) { Text("← 返回我的") }
        Text("个人资料", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("可留空；数据只保存在本机，暂不自动生成课表。", color = Muted)
        SectionCard {
            Text("基础身体信息", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (profile.updatedAt > 0) Text("最近更新：${java.time.Instant.ofEpochMilli(profile.updatedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()}", color = Muted)
            val birthMonth = runCatching { YearMonth.parse(profile.birthMonth) }.getOrNull()
            if (birthMonth != null) Text("年龄：${YearMonth.now().year - birthMonth.year - if (YearMonth.now().monthValue < birthMonth.monthValue) 1 else 0} 岁", color = Muted)
            OutlinedTextField(birth, { birth = it }, label = { Text("出生年月 YYYY-MM") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(height, { height = it }, label = { Text("身高（厘米）") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(sex, { sex = it }, label = { Text("性别（可选）") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(goal, { goal = it }, label = { Text("训练目标") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(experience, { experience = it }, label = { Text("训练经验") }, modifier = Modifier.fillMaxWidth())
            Text("每周可训练日")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                (1..7).forEach { day -> WeekCell(weekdays[day - 1], null, day in profile.availableDays, Modifier.weight(1f)) {
                    store.updateProfile(profile.copy(availableDays = if (day in profile.availableDays) profile.availableDays - day else profile.availableDays + day))
                } }
            }
            if (error.isNotEmpty()) Text(error, color = androidx.compose.ui.graphics.Color.Red)
            ActionButton("保存基础资料", {
                val validBirth = birth.isBlank() || runCatching { YearMonth.parse(birth) <= YearMonth.now() }.getOrDefault(false)
                val cm = height.toIntOrNull()
                if (!validBirth || (height.isNotBlank() && (cm == null || cm !in 50..250))) error = "请检查出生年月和身高"
                else { store.updateProfile(profile.copy(birthMonth = birth, heightCm = cm, sex = sex.trim(), goal = goal.trim(), experience = experience.trim(), updatedAt = System.currentTimeMillis())); error = "已保存" }
            }, Modifier.fillMaxWidth())
        }
        SectionCard {
            Text("体重记录", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("最新：${profile.weights.maxByOrNull { it.date }?.kg ?: "未填写"} kg", color = Muted)
            OutlinedTextField(weightDate, { weightDate = it }, label = { Text("测量日期 YYYY-MM-DD") })
            OutlinedTextField(weight, { weight = it }, label = { Text("体重（公斤）") })
            TextButton(onClick = {
                val kg = parseKg(weight)
                if (runCatching { LocalDate.parse(weightDate) }.isSuccess && kg != null && kg > 0)
                    { store.updateProfile(profile.copy(weights = profile.weights + WeightEntry(System.currentTimeMillis(), weightDate, kg))); weight = "" }
                else error = "请检查日期和体重"
            }) { Text("添加体重") }
            profile.weights.sortedByDescending { it.date }.forEach { item -> Row {
                Text("${item.date} · ${item.kg} kg", Modifier.weight(1f)); TextButton(onClick = { deleteWeight = item.id }) { Text("删除") }
            } }
        }
        SectionCard {
            Text("体能测试", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Row { TextButton(onClick = { testName = "二头弯举"; testUnit = "次数"; testKg = "7.5" }) { Text("二头弯举") }
                TextButton(onClick = { testName = "平板支撑"; testUnit = "秒"; testKg = "" }) { Text("平板支撑") } }
            OutlinedTextField(testName, { testName = it }, label = { Text("项目名称／自定义项目") }, modifier = Modifier.fillMaxWidth())
            Row { FilterChip(testUnit == "次数", onClick = { testUnit = "次数" }, label = { Text("次数") }); Spacer(Modifier.width(8.dp));
                FilterChip(testUnit == "秒", onClick = { testUnit = "秒" }, label = { Text("时长（秒）") }) }
            OutlinedTextField(testResult, { testResult = it }, label = { Text("结果（$testUnit）") })
            OutlinedTextField(testKg, { testKg = it }, label = { Text("负重 kg（可选）") })
            OutlinedTextField(testSide, { testSide = it }, label = { Text("左臂／右臂／双臂（可选）") })
            OutlinedTextField(testDate, { testDate = it }, label = { Text("测试日期 YYYY-MM-DD") })
            OutlinedTextField(note, { note = it }, label = { Text("动作规范与备注") }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = {
                val result = testResult.toDoubleOrNull(); val kg = if (testKg.isBlank()) null else parseKg(testKg)
                if (testName.isBlank() || result == null || result < 0 || (testUnit == "次数" && result % 1 != 0.0) ||
                    (testKg.isNotBlank() && kg == null) || runCatching { LocalDate.parse(testDate) }.isFailure) error = "请检查测试项目、结果、负重和日期"
                else {
                    val id = editTest ?: System.currentTimeMillis()
                    val item = FitnessTest(id, testDate, testName.trim(), result, testUnit, kg, testSide.trim(), note.trim())
                    store.updateProfile(profile.copy(tests = profile.tests.filterNot { it.id == id } + item))
                    editTest = null; testResult = ""; note = ""; error = "已保存体测"
                }
            }) { Text(if (editTest == null) "保存测试记录" else "保存修改") }
            profile.tests.sortedByDescending { it.date }.forEach { item -> SectionCard {
                Text("${item.date} · ${item.name} · ${item.result} ${item.unit}" + (item.kg?.let { " · $it kg" } ?: ""))
                if (item.side.isNotBlank() || item.note.isNotBlank()) Text("${item.side} ${item.note}", color = Muted)
                Row {
                    TextButton(onClick = { editTest = item.id; testName = item.name; testUnit = item.unit; testResult = item.result.toString(); testKg = item.kg?.toString() ?: ""; testSide = item.side; testDate = item.date; note = item.note }) { Text("修改") }
                    TextButton(onClick = { deleteId = item.id }) { Text("删除") }
                }
            } }
        }
    }
    if (deleteId != null || deleteWeight != null) AlertDialog(onDismissRequest = { deleteId = null; deleteWeight = null },
        title = { Text("确认删除？") }, text = { Text("删除后将不再用于未来课程建议。") },
        confirmButton = { TextButton(onClick = {
            deleteId?.let { store.updateProfile(store.profile.copy(tests = store.profile.tests.filterNot { item -> item.id == it })) }
            deleteWeight?.let { store.updateProfile(store.profile.copy(weights = store.profile.weights.filterNot { item -> item.id == it })) }
            deleteId = null; deleteWeight = null
        }) { Text("删除") } }, dismissButton = { TextButton(onClick = { deleteId = null; deleteWeight = null }) { Text("取消") } })
}
