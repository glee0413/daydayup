package com.daydayup

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daydayup.metronome.Cadence
import com.daydayup.metronome.MetronomeRuntime
import com.daydayup.metronome.MetronomeService
import com.daydayup.metronome.SessionRecord
import com.daydayup.metronome.SessionStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val Ink = Color(0xFF102129)
val Paper = Color(0xFFF5F7F6)
val Teal = Color(0xFF21B5A5)
val Amber = Color(0xFFF2B84B)
val Muted = Color(0xFF60747A)

class MainActivity : ComponentActivity() {
    private lateinit var store: SessionStore
    private var notificationsGranted by mutableStateOf(true)
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { contentResolver.openOutputStream(it)?.use { output -> output.write(store.exportJson().toByteArray(Charsets.UTF_8)) } }
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { notificationsGranted = it }

    override fun onResume() {
        super.onResume()
        notificationsGranted = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SessionStore(this)
        val audioSettings = getSharedPreferences("metronome_audio", MODE_PRIVATE)
        MetronomeRuntime.volume = audioSettings.getFloat("click_volume", 1f).coerceIn(0f, 1f)
        MetronomeRuntime.soundEnabled = audioSettings.getBoolean("click_enabled", true)
        if (MetronomeRuntime.phase == "idle") store.recoverInterrupted()
        setContent {
            var page by remember { mutableStateOf("首页") }
            val demo = remember { DemoState() }
            var records by remember { mutableStateOf(store.records()) }
            val phase = MetronomeRuntime.phase
            LaunchedEffect(phase, page) { if (phase == "idle" || page == "记录") records = store.records() }
            MaterialTheme(colorScheme = lightColorScheme(primary = Teal, background = Paper, surface = Color.White)) {
                Column(Modifier.fillMaxSize().background(Paper)) {
                    Row(Modifier.fillMaxWidth().background(Ink).padding(horizontal = 20.dp, vertical = 18.dp),
                        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("天天向上", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                        Text(if (phase == "running") "● 节拍进行中" else "运动陪伴", color = if (phase == "running") Teal else Color.LightGray, fontSize = 12.sp)
                    }
                    Box(Modifier.weight(1f)) {
                        when (page) {
                            "首页" -> HomeScreen(records, { page = "节拍器" }, { page = "课程" }, { page = "报告" })
                            "节拍器" -> MetronomeScreen(
                                send = { action, bpm ->
                                    if (action == MetronomeService.START && Build.VERSION.SDK_INT >= 33 &&
                                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                                    ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    MetronomeService.send(this@MainActivity, action, bpm)
                                },
                                sendVolume = { value ->
                                    audioSettings.edit().putFloat("click_volume", value).apply()
                                    if (MetronomeRuntime.phase != "idle") startService(Intent(this@MainActivity, MetronomeService::class.java)
                                        .setAction(MetronomeService.SET_VOLUME).putExtra(MetronomeService.EXTRA_VOLUME, value))
                                },
                                sendSound = { value ->
                                    audioSettings.edit().putBoolean("click_enabled", value).apply()
                                    if (MetronomeRuntime.phase != "idle") startService(Intent(this@MainActivity, MetronomeService::class.java)
                                        .setAction(MetronomeService.SET_SOUND).putExtra(MetronomeService.EXTRA_SOUND, value))
                                },
                                openRecords = { page = "记录" },
                                demoActive = demo.phase !in listOf("notStarted", "done"),
                                endDemo = { demo.phase = "done" },
                                notificationsGranted = notificationsGranted,
                                openNotificationSettings = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:$packageName"))) })
                            "记录" -> HistoryScreen(records, { page = "节拍器" }, { id -> store.delete(id); records = store.records() },
                                { id, name -> store.rename(id, name); records = store.records() }, { export.launch("daydayup-records.json") })
                            "课程", "训练", "教练", "报告", "设备" -> DemoScreens(page, demo, { page = it }, phase != "idle", { page = "节拍器" })
                            "我的" -> SettingsScreen({ page = "记录" }, { page = "设备" }, { export.launch("daydayup-records.json") }, { demo.reset() })
                        }
                    }
                    Row(Modifier.fillMaxWidth().background(Color.White).padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        listOf("首页", "节拍器", "课程", "报告", "我的").forEach { tab ->
                            Column(Modifier.clickable { page = tab }.padding(horizontal = 10.dp, vertical = 3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(when (tab) { "首页" -> "⌂"; "节拍器" -> "◉"; "课程" -> "▤"; "报告" -> "▥"; else -> "◌" },
                                    fontSize = 20.sp, color = if (page == tab) Teal else Muted)
                                Text(tab, fontSize = 11.sp, color = if (page == tab) Ink else Muted)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ScreenColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
}

@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(20.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
fun ActionButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, secondary: Boolean = false) {
    Button(onClick, modifier.height(52.dp), shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (secondary) Ink else Teal, contentColor = Color.White)) {
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun DemoBanner() {
    Row(Modifier.fillMaxWidth().background(Amber.copy(alpha = 0.22f), RoundedCornerShape(12.dp)).padding(12.dp)) {
        Text("演示模式 · 以下内容为示例，不计入真实训练记录", color = Ink, fontSize = 12.sp)
    }
}

@Composable
fun HomeScreen(records: List<SessionRecord>, openMetronome: () -> Unit, openCourse: () -> Unit, openReport: () -> Unit) {
    ScreenColumn {
        Text("今天，跟着节奏前进。", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text("打开节拍器，让每一步都有清晰的提示。", color = Muted)
        SectionCard(Modifier.background(Ink, RoundedCornerShape(20.dp))) {
            Text("跑步节拍器", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text("一声一步 · 后台持续 · 与音乐同听", color = Muted)
            ActionButton("开始设置节拍", openMetronome, Modifier.fillMaxWidth())
        }
        SectionCard {
            Text("最近使用", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Text(if (records.isEmpty()) "还没有真实记录。完成一次节拍训练后会显示在这里。"
                else "${records.first().name} · ${formatDuration(records.first().durationMs)}", color = Muted)
        }
        DemoBanner()
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard(Modifier.weight(1f).clickable(onClick = openCourse)) { Text("每周课表", fontWeight = FontWeight.Bold); Text("安排训练 →", color = Teal) }
            SectionCard(Modifier.weight(1f).clickable(onClick = openReport)) { Text("训练报告", fontWeight = FontWeight.Bold); Text("查看示例 →", color = Teal) }
        }
    }
}

@Composable
fun MetronomeScreen(send: (String, Int?) -> Unit, sendVolume: (Float) -> Unit, sendSound: (Boolean) -> Unit,
                    openRecords: () -> Unit, demoActive: Boolean, endDemo: () -> Unit,
                    notificationsGranted: Boolean, openNotificationSettings: () -> Unit) {
    val phase = MetronomeRuntime.phase
    val bpm = MetronomeRuntime.bpm
    var input by remember(bpm) { mutableStateOf(bpm.toString()) }
    var error by remember { mutableStateOf("") }
    val running = phase != "idle"
    ScreenColumn {
        Text("跑步节拍器", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text(if (phase == "running") "节拍进行中 · 一声一步" else if (phase == "paused") "已暂停，时长不增加" else "手动开始，随时结束", color = Muted)
        if (!notificationsGranted) SectionCard {
            Text("通知未授权", fontWeight = FontWeight.Bold, color = Ink)
            Text("锁屏时可能看不到暂停和结束操作。请在系统设置中允许通知。", color = Muted)
            TextButton(onClick = openNotificationSettings) { Text("打开通知设置") }
        }
        if (demoActive) SectionCard {
            Text("演示训练仍在进行", fontWeight = FontWeight.Bold, color = Ink)
            Text("同一时间只运行一项训练。结束演示后才能开始节拍器。", color = Muted)
            TextButton(onClick = endDemo) { Text("结束演示训练") }
        }
        SectionCard {
            Text("步频", color = Muted)
            Text("$bpm", fontSize = 76.sp, fontWeight = FontWeight.Bold, color = Ink,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Text("步 / 分钟", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = Muted)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { val value = (bpm - 5).coerceAtLeast(Cadence.MIN); if (running) send(MetronomeService.SET_BPM, value) else MetronomeRuntime.bpm = value }) { Text("− 5") }
                OutlinedTextField(input, onValueChange = { input = it; error = "" }, label = { Text("输入步频") }, singleLine = true, modifier = Modifier.width(132.dp))
                OutlinedButton(onClick = { val value = (bpm + 5).coerceAtMost(Cadence.MAX); if (running) send(MetronomeService.SET_BPM, value) else MetronomeRuntime.bpm = value }) { Text("+ 5") }
            }
            if (error.isNotEmpty()) Text(error, color = Color(0xFFC94B45), fontSize = 12.sp)
            TextButton(onClick = {
                val value = input.toIntOrNull()
                if (value == null || !Cadence.isValid(value)) error = "请输入 160–220 之间的整数"
                else if (running) send(MetronomeService.SET_BPM, value) else MetronomeRuntime.bpm = value
            }) { Text("应用输入的步频") }
        }
        SectionCard {
            Text("本次时长", color = Muted)
            Text(formatDuration(MetronomeRuntime.elapsedMs), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Ink)
            if (phase == "running" && !MetronomeRuntime.audible) Text("计时继续，当前无提示音", color = Color(0xFFC94B45))
            when (phase) {
                "idle" -> if (!demoActive) ActionButton("开始节拍", { send(MetronomeService.START, bpm) }, Modifier.fillMaxWidth())
                "paused" -> ActionButton("继续", { send(MetronomeService.RESUME, null) }, Modifier.fillMaxWidth())
                else -> ActionButton("暂停", { send(MetronomeService.PAUSE, null) }, Modifier.fillMaxWidth(), secondary = true)
            }
            if (running) OutlinedButton(onClick = { send(MetronomeService.STOP, null) }, Modifier.fillMaxWidth()) { Text("结束并保存") }
        }
        SectionCard {
            Text("提示音", fontWeight = FontWeight.SemiBold, color = Ink)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("播放提示音", Modifier.weight(1f)); Switch(MetronomeRuntime.soundEnabled, onCheckedChange = { MetronomeRuntime.soundEnabled = it; sendSound(it) })
            }
            Text("节拍音量 ${Math.round(MetronomeRuntime.volume * 100)}%", color = Muted)
            Slider(MetronomeRuntime.volume, onValueChange = { MetronomeRuntime.volume = it; sendVolume(it) })
            Text("此滑杆只调节节拍声；手机媒体音量仍决定声音上限。第一版不含语音播报。", color = Muted, fontSize = 12.sp)
        }
        TextButton(onClick = openRecords) { Text("查看真实使用记录 →") }
    }
}

@Composable
fun HistoryScreen(records: List<SessionRecord>, back: () -> Unit, delete: (Long) -> Unit, rename: (Long, String) -> Unit, export: () -> Unit) {
    var editing by remember { mutableStateOf<Long?>(null) }
    var name by remember { mutableStateOf("") }
    ScreenColumn {
        TextButton(onClick = back) { Text("← 返回节拍器") }
        Text("真实使用记录", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text("这里只记录节拍器运行，不代表实际步数或跑步距离。", color = Muted)
        if (records.isEmpty()) SectionCard { Text("暂无记录。完成一次节拍训练后会显示在这里。") }
        records.forEach { record ->
            SectionCard {
                Text(record.name, fontWeight = FontWeight.Bold, color = Ink)
                Text("${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(record.startedAt))} · ${formatDuration(record.durationMs)}", color = Muted)
                Text("初始 ${record.initialBpm} 步/分钟 · 调速 ${record.changes.size} 次", color = Muted)
                if (record.interrupted) Text("意外结束 · 已保留可确认时长", color = Color(0xFFC94B45))
                record.changes.forEach { Text("${formatDuration(it.elapsedMs)} 调整为 ${it.bpm} 步/分钟", color = Muted, fontSize = 12.sp) }
                if (editing == record.id) {
                    OutlinedTextField(name, onValueChange = { name = it }, label = { Text("记录名称") })
                    TextButton(onClick = { rename(record.id, name); editing = null }) { Text("保存名称") }
                } else Row {
                    TextButton(onClick = { editing = record.id; name = record.name }) { Text("重命名") }
                    TextButton(onClick = { delete(record.id) }) { Text("删除", color = Color(0xFFC94B45)) }
                }
            }
        }
        ActionButton("导出真实记录", export, Modifier.fillMaxWidth(), secondary = true)
    }
}

@Composable
fun SettingsScreen(openRecords: () -> Unit, openDevice: () -> Unit, export: () -> Unit, resetDemo: () -> Unit) {
    ScreenColumn {
        Text("我的", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Ink)
        SectionCard {
            Text("数据与隐私", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("真实节拍器记录保存在本机。你可以查看、导出或删除。", color = Muted)
            TextButton(onClick = openRecords) { Text("查看真实记录") }
            TextButton(onClick = export) { Text("导出记录") }
        }
        SectionCard {
            Text("即将推出", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("AI 通话需要联网；默认不保存录音。设备数据仅在你主动授权后获取。", color = Muted)
            TextButton(onClick = openDevice) { Text("查看设备连接演示") }
            TextButton(onClick = resetDemo) { Text("重置演示数据") }
        }
    }
}

fun formatDuration(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return "%02d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
}
