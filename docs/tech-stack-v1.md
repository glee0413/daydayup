# 天天向上 v1 技术选型分析

> 历史选型分析：本文描述的是曾考虑的较复杂方案，并非当前 Android demo 的实际依赖或实现。当前工程的版本与构建方式以仓库根目录 `README.md` 和 Gradle 文件为准。

日期：2026-10-31
依据：`docs/spec-v1.md`、`docs/superpowers/specs/2026-10-31-android-demo-design.md`
范围：第一版。不含第二版（AI 实时通话、设备同步）的最终实现。

> **版本核实说明（2026-10-31 逐项核对）**
> 本文出现的每一个版本号都来自官方发布页 / 官方兼容性对照表，核实来源见文末第 10 节。
> 凡无法核实的版本号一律不写死 —— 见第 3.9 节"版本核实状态表"。

---

## ⚠️ 0.5 必读：本文是"建议目标栈"，不是当前代码的依赖清单

**仓库中的代码已经可用，且用的是比本文更轻的一套配置。** 两者目前不一致，读文档前务必先看这张表：

| 项 | 本文建议（目标） | 仓库当前实际（已通过构建） |
| --- | --- | --- |
| Gradle | 8.13 | **8.11.1**（`gradle/wrapper/gradle-wrapper.properties`） |
| AGP | 8.13.2 | **8.9.2**（根 `build.gradle.kts`） |
| Kotlin | 2.3.21 | **2.1.20**（同上） |
| compileSdk / targetSdk | 36 / 36 | **35 / 35**（`app/build.gradle.kts`） |
| Compose BOM | 2026.09.00 | **2025.04.01** |
| `activity-compose` | 1.13.0 | **1.10.1** |
| 版本目录 | `gradle/libs.versions.toml` | **未使用**（依赖直接写在 `app/build.gradle.kts`） |
| Room / DataStore / Hilt / media3 / 导航库 | 建议引入 | **未引入** |
| 真实记录存储 | Room 关系表 | **本地 JSON 文件** |

### 这两套配置分别该什么时候用

- **现在（v1 已跑通）**：**不要**按本文去改 `*.gradle.kts`。当前组合（Gradle 8.11.1 + AGP 8.9.2 + Kotlin 2.1.20 + SDK 35）已经能构建出可用 APK，更换版本属于无收益的返工风险。
- **本文的真正用途**：作为**下一次升级时的核对表**。当你确实要动版本号时（例如为了 Google Play 的 target ≥ 36 要求、或引入 Room / Hilt），照第 3.9 节与第 10 节核对推荐版本与来源。

### 唯一建议尽快落实的一项

**`targetSdk` 提到 36。** Google Play 自 2026-08-31 起要求新应用及更新以 API 36 为目标；即便自分发，靠近 36 也能提前暴露 Android 16/17 的后台音频行为差异（见第 10 节第 8 条 —— 而后台音频正是本产品的命脉）。
代价：需装 SDK Platform 36，且 AGP 8.9.2 支持的最高 compileSdk 未必到 36，可能要顺带把 AGP 提到 8.13.x。**建议连 AGP 一起升**，届时本文第 3.1 节就是现成的目标配置。

---

## 0. 结论摘要

| 层 | 选型 | 一句话理由 |
| --- | --- | --- |
| 平台 | 原生 Android（Kotlin） | 唯一真实功能是后台持续音频，深度依赖平台行为，跨平台在这里省不掉原生代码 |
| UI | Jetpack Compose + Material 3 | 声明式状态表达最适合"运行态/暂停态/演示态"多分支界面 |
| 导航 | Navigation3 1.2.0（回退：Navigation 2.10.2 类型安全路由） | Compose 原生，回退栈自己做主，便于"演示/真实"分流 |
| 构建 | Gradle 8.13 + AGP 8.13.2 + Kotlin 2.3.21 + Version Catalog | 8.x 分支终局稳定组合，支持 compileSdk 36；AGP 9 的破坏性变更留到第二版 |
| 音频引擎 | AudioTrack STREAM 模式 + 阻塞写循环（采样级排拍），接口隔离 | 唯一能保证 160–220 步/分不抖动且长时间不漂移的端上方案 |
| 后台生命周期 | 自建 `mediaPlayback` 前台服务 + MediaSession（media3-session） | 满足 Android 15/16/17 后台音频规则；将来可平滑迁到 MediaSessionService |
| 数据 | Room 2.8.5 + Preferences DataStore 1.3.0 | 真实记录用关系表（要查询/导出），设置用键值 |
| 异步/状态 | Coroutines + Flow + StateFlow（服务为唯一权威状态源） | 服务跨进程存活，ViewModel 只做订阅与命令转发 |
| DI | Hilt 1.4.0 | 需要在 Service 里注入音频引擎与仓库，编译期校验更稳 |
| 后端 | **无后端** | 见第 2 节 |
| 测试 | JUnit4 + Turbine + Compose UI Test；真机清单 | 见第 7 节 |

---

## 1. 需求如何倒逼选型（先列约束，再选技术）

从规格书抽出对技术有决定性影响的硬约束：

| # | 约束 | 技术后果 |
| --- | --- | --- |
| C1 | 切后台/锁屏后节拍**计时与声音持续**，且有持续通知 | 必须前台服务（`foregroundServiceType="mediaPlayback"`），节拍时序不能挂在 UI 上 |
| C2 | 160–220 步/分，抖动会被跑者身体感知（文献阈值约 5–10ms） | 不能用 Handler/Timer 排拍，必须由音频硬件时钟驱动（见 3.4） |
| C3 | 与音乐/微信读书**同时播放**，节拍音量独立可调 | 不能抢占 `AUDIOFOCUS_GAIN`（Android 12+ 会把正在放的 `USAGE_MEDIA` 淡出），必须用独立的 `AudioTrack.setVolume` |
| C4 | 来电时节拍**计时继续**，不要求同听，挂断后自动恢复发声 | 需要识别通话态；前台服务在 transient 焦点丢失期间不得停止（Android 17 明确允许 10 分钟内的瞬时中断保持 FGS） |
| C5 | 被杀/重启**不得自动重播**，要保留已确认时长 | 需要单调时钟 + 周期性快照；恢复时只做"意外结束"收尾 |
| C6 | 数据默认留在本机、可导出删除；**演示数据与真实记录必须隔离** | 两条互不可见的存储通道 + 包级依赖隔离；导出走系统文件选择器（SAF），不申请存储权限 |
| C7 | 离线可用；权限按需申请 | 不能有"启动即联网"的架构；后续语音播报走系统 `TextToSpeech`，并用 `KEY_FEATURE_NETWORK_SYNTHESIS=false` 强制离线合成 |

约束 C1–C4 直接指向平台深层行为。因此结论是：**这是一个平台深度依赖型 App，而不是一个 UI 型 App。** 选型的第一优先级不是"开发快"，而是"能否精确控制音频时钟与后台生命周期"。

---

## 2. "全栈"的边界：为什么第一版没有传统后端

### 2.1 先纠正一个前提

**本产品第一版不需要"前端 + 服务端 + 数据库"的经典三层全栈。** 目标用户是发起人自己、数据默认留在本机、要求离线可用、要求可导出删除 —— 这四条同时成立时，加服务端只会带来账户体系、同步冲突、隐私合规三项纯负债。

所以这里的"全栈"应理解为**端上的垂直分层栈**：

```
表现层  Compose UI  ← 只渲染，不持有真值
   ↑ StateFlow            ↓ Intent/命令
编排层  ViewModel + UseCase  ← 把用户意图翻译成命令/查询
   ↑ Flow                 ↓ 命令
能力层  MetronomeController(Service) / ClickEngine / TtsEngine / Exporter
持久层  Room（真实记录） | DemoStore（演示状态） | DataStore（设置）
平台层  Android Framework：FGS / AudioTrack / AudioFocus / Telephony / SAF
```

三条独立的数据通道：**真实记录、演示状态、设置**，彼此不交叉。这直接对应 C6。

### 2.2 什么时候才真的需要后端

| 阶段 | 场景 | 需要的后端形态 | 第一版要不要做 |
| --- | --- | --- | --- |
| v1 | 全部本地 | 无 | 不做 |
| v2 | AI 语音教练实时通话 | 实时语音网关（Realtime WebSocket/WebRTC 类 API），**免自家服务端**：App 直接连第三方 SDK，密钥走服务端换取的短期令牌 | 只留接口 `VoiceCoachGateway`，实现为 `DemoVoiceCoachGateway` |
| v2+ | 可选：换机备份 | 对象存储 or 增量同步服务 | 导出/导入文件已足够，先不做 |
| — | 账号体系 | 不需要（单用户） | 永久不做 |

结论：**服务端只以接口形式预留**。定义

```kotlin
interface VoiceCoachGateway {
    fun interface State { /* idle | connecting | inCall | error */ }
    suspend fun describeDataHandling(): DataHandlingNotice  // 开始前的联网与数据处理说明
    suspend fun start(): Flow<VoiceCoachEvent>
    suspend fun stop()
}
```

v1 的实现是本地演示版；将来换成联网实现时不触碰 UI 层与数据层。这是"预留但不建"的正确姿势。

### 2.3 跨平台方案为什么不适合这里

| 方案 | 对 C1–C4 的处理 | 判断 |
| --- | --- | --- |
| Flutter | 后台音频、采样级排拍、通话态、前台服务全部要写 Kotlin/Java 原生插件 + MethodChannel/FFI | **否决**。唯一真实功能要写原生，UI 层省下的工作量不足以抵消双端调试成本。且 Dart FFI + Oboe 的抖动排错比纯 Kotlin 更费时 |
| React Native / Expo | 同上，且后台音频生态更薄 | **否决** |
| KMP（Compose Multiplatform） | 共享业务逻辑可行，但 UI 若也走 CMP 就丢掉 Material 3 Android 适配；仅共享 domain 层收益有限 | **暂缓**。若第二版确定要 iOS，再抽：`ClickEngine` 下沉到 `commonMain` 的 expect/actual，UI 仍各自原生 |
| **Kotlin 原生 + Compose** | 直接调用全部平台 API | **选用** |

---

## 3. 推荐技术栈

### 3.1 语言与构建

| 项 | 版本 | 核实依据 / 说明 |
| --- | --- | --- |
| Kotlin（KGP） | **2.3.21** | Kotlin 官方 Gradle 兼容表：KGP `2.3.20–2.3.21` 支持 Gradle `7.6.3–9.3.0`、AGP `8.2.2–9.0.0` |
| Android Gradle Plugin | **8.13.2** | 8.x 系列的最终稳定版。官方发布说明：AGP 8.13 要求 **Gradle 8.13**、**JDK 17**，最高支持 **API 36.1**，默认 SDK Build Tools `35.0.0` |
| Gradle | **8.13** | AGP 8.13 的最低且默认版本 |
| R8 | 8.13.19（由 AGP 8.13.2 带出） | 官方说明原文：AGP 8.13.2 使用 R8 8.13.19，**该版本才支持 Kotlin 2.3** —— 这是必须锁 8.13.2 而不是 8.13.0 的原因 |
| JDK | **17** | AGP 8.x 与 9.x 均要求 JDK 17+。**当前本机尚未安装**（见 7.1） |
| compileSdk / targetSdk | **36 / 36** | 现工程为 35/35。Google Play 自 2026-08-31 起要求 target ≥ 36；即便自分发，锁 36 可提前暴露 Android 16 行为差异。AGP 8.13 最高支持 API 36.1，足够 |
| minSdk | **26**（保持） | 若日后愿意放弃 Android 8–11，提到 31 可简化通话态识别，详见 3.5 |
| Version Catalog | `gradle/libs.versions.toml` | 统一收敛版本，避免 Compose BOM 与手写版本打架 |

组合自洽性交叉核验（三个来源互相印证）：
`AGP 8.13.2 + Gradle 8.13 + Kotlin 2.3.21 + JDK 17 + 最高 API 36.1`
这一组同时出现在 AGP 官方发布说明、Kotlin 官方兼容表和社区版 8.x 工具链汇总表中，是 8.x 分支的唯一推荐终局。

> **不采用 AGP 9.x 的原因**：AGP 9.0 起内置 Kotlin 编译、禁用 `kotlin-android` 插件、kapt 不兼容（必须 KSP）。本身是正确方向，但和本次"三个月内交付可运行第一版"的目标叠加风险。列为 v1 交付后的第一项技术债。

### 3.2 UI 层

- **Jetpack Compose + Material 3**，Compose BOM `2026.09.00`（当前最新稳定）。
- `androidx.activity:activity-compose:1.13.0`；`androidx.lifecycle:lifecycle-runtime-compose:2.11.0`（`collectAsStateWithLifecycle`）。
- 单 Activity + `enableEdgeToEdge()`。
- 等宽数字：所有时长/BPM 使用 `fontFeatureSettings = "tnum"`，避免跑步中数字抖动。
- 触控目标：主操作 ≥ 72dp，次级 ≥ 48dp；破坏性操作（结束/删除）必须与主操作在视觉与位置上分离。
- 动画只用于**节拍脉冲**这一处功能性反馈；其余状态切换用短 Crossfade，杜绝跑步时的装饰动效。

### 3.3 导航

首选 **Navigation3 1.2.0**（2026-09-23 起 stable）：

- 回退栈是普通 Compose 状态（`rememberNavBackStack`），便于"演示路由组 / 真实路由组"统一前置拦截。
- `@Serializable` 路由键，编译期类型安全，无需字符串路由表。
- 与 Material adaptive 已官方集成，后续若要大屏/折叠布局不用换框架。

回退方案：**Navigation 2.10.2** 的类型安全路由 —— API 更成熟、资料更多。若开发中 Navigation3 遇到阻塞，切回成本低（路由数量约 15 个）。

路由清单见 `docs/ux-interaction-v1.md` 第 2 节。

### 3.4 音频引擎（本项目唯一的核心技术难点）

**结论：`ClickEngine` 接口 + `AudioTrack` STREAM 模式阻塞写循环实现。** 不用 MediaPlayer / SoundPool / ExoPlayer 播放器排节拍。

为什么：

1. `Handler.postDelayed` / `ScheduledExecutor` / `Timer` 的回调由 **OS 调度器投递**。即便设备空闲，5–20ms 的抖动也是常态；繁忙（GC、网络事件、界面绘制）时会更差。而对节奏的感知阈值大约就在 5–10ms：平均 BPM 看似正确，但每一拍各自偏早或偏晚，长时间跑下来就是"锁不住、感觉松散"，只是大多数人说不出原因。
2. AudioTrack STREAM 模式下 `write()` 是阻塞的，**只有当硬件环形缓冲接收完整个片段才返回**，而硬件按晶体振荡器固定速率出样。把"一拍 = 点击 + 静音"作为一个片段连续写入，节拍落地时刻就被锚定在音频时钟上，抖动约等于硬件延迟且不累积。

实现要点（Kotlin，单条专用线程，`Process.THREAD_PRIORITY_URGENT_AUDIO`）：

```kotlin
// 用定点累加器保留余数，避免每拍取整造成的累积漂移
private var phase = 0.0   // 单位：样本

private fun samplesPerBeat(): Double = sampleRate * 60.0 / currentBpm

private fun renderOneBeat(out: FloatArray) {
    Blit.click(out, clickSamples)          // 在 buffer[0..clickLen) 写入预合成的短瞬态
    val per = samplesPerBeat()
    phase += per - out.size
    if (phase >= 1.0) phase -= per         // 余量进位到下一拍，误差 < 1 样本
}
```

- **调速**：不重启 `AudioTrack`、不中断记录；只改 `currentBpm`，从**下一拍边界**生效。 `MetronomeSession.changeBpm()` 已按此语义写了测试，保持。
- **音色**：启动时预合成一段短 PCM（建议 1–2kHz 带包络的瞬态，5–10ms），全程复用，禁止每拍 new 播放器。
- **音量**：`AudioTrack.setVolume(float)` 由 App 内滑块驱动，**不动系统音量流**，这样音乐音量与节拍音量互不影响（C3）。
- **音频焦点策略**：**不申请 `AUDIOFOCUS_GAIN`**。原因：Android 12+ 上，当第二方请求 `GAIN` 时，系统会强制把第一个 `USAGE_MEDIA` 且持有 `GAIN` 的应用淡出 —— 那样一开始放节拍就会把音乐压掉，直接违反需求 3.1。改为：
  - `AudioAttributes`: `usage = USAGE_MEDIA`，`contentType = CONTENT_TYPE_SONIFICATION`（混音友好）；
  - **完全不持有焦点** → 音乐/有声书原样播放，节拍叠加混合；
  - （第一版真实节拍器没有语音播报，因此不存在"临时抢占焦点播一句话"的场景；第二版力量训练的语音指导走 `TextToSpeech` 时，才需要单句级别的 `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`，届时单独处理。）
  - 注意：target ≥ 35 时，**非顶层 App 且未跑前台服务**请求音频焦点会返回 `AUDIOFOCUS_REQUEST_FAILED`；我们跑前台服务，所以将来若确需请求焦点也满足前提。
- **是否要 Oboe/C++**：不需要。Oboe 的优势是把延迟压到 5–15ms 用于实时合成/监听；节拍器不要求低延迟，只要求**拍点在音频时钟上**，AudioTrack 阻塞写已足够。引入 NDK 会带来 ABI、构建、调试三重成本，等到真机验证发现抖动不可接受时再评估（这是要在真机上量化的验收项，见第 7 节）。

### 3.5 后台生命周期、通知与通话

| Concern | 方案 |
| --- | --- |
| 容器 | 自建 `MetronomeService`，manifest 已声明 `foregroundServiceType="mediaPlayback"` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK`，保持 |
| 启动时机 | **必须在 Activity 可见时由用户点击触发**。Android 17 起后台音频要求 FGS 具备 while-in-use 能力，而"用户发起操作时启动"正是获得 WIU 的条件；绝不能从 BOOT_COMPLETED 启动 |
| 通知 | MediaStyle + `MediaSession`（`media3-session:1.11.1`），三枚操作：暂停/继续、结束。内容：`180 步/分 · 已运行 12:34`。点击回到节拍页（singleTop） |
| 来电识别 | 双保险：① `AudioManager.addOnModeChangedListener`（API 31+，**无需权限**）捕获 `MODE_IN_CALL` / `MODE_RINGTONE`；② `TelephonyManager` + `READ_PHONE_STATE` 作为 API 26–30 的补充，权限被拒时降级到 ① |
| 来电行为 | **计时继续**；电话音频由系统侧接管（不要求节拍声与通话声同时可听）；FGS 不停止 —— Android 17 官方文档明确：瞬时中断（如电平/音频焦点短暂丢失）**10 分钟以内应保持前台服务存活**；挂断后模式回落至 `MODE_NORMAL` 再自动恢复发声 |
| 耳机断连 | `AudioManager.ACTION_AUDIO_BECOMING_NOISY`：继续计时，UI 显示"耳机已断开 · 节拍仍在计时"，声音状态如实呈现，不谎报 |
| 进程被杀/重启 | 单调时钟（`SystemClock.elapsedRealtime`）计时 + 每 5s 与状态变更时写快照；启动时扫描未正常关闭的会话 → 标记 `意外结束`，**只恢复时长、不自动发声** |
| 迁移路径 | 若后续要在更多机型上贴紧 Google 建议，把服务换成 media3 的 `MediaSessionService`，`ClickEngine` 接口不变，改动局限在服务壳层 |

> **重要**：上述"节拍在通话期间继续计时"与 Android 17 的规则并不冲突（我们始终用用户手动发起的前台服务，且在瞬时中断期间保持 FGS 存活）。但有一条红线：**"无声"不等于"故障"**。当系统静音、耳机断开、其他应用占用音频时，UI 必须如实显示声音状态，绝不把静默运行渲染成"正在正常播放"（规格第 9 节明确要求）。这是本次设计中最容易做错的一处。

### 3.6 数据层

| 用途 | 选型 | 理由 |
| --- | --- | --- |
| 真实节拍记录 | **Room 2.8.5**（KSP） | 需要按日期范围查询、删除单条、导出全量；关系表比文件更好演进。Room3 3.0.3 已 stable，但留作后续迁移项 |
| 设置 / 上一次 BPM / 音量 | **Preferences DataStore 1.3.0** | 少量键值，Flow 读取 |
| 演示状态 | 内存 ViewModel + 可选写入独立的 DataStore/Room DB（`demo.db`，与 `metronome.db` 不同文件） | 物理分库是最强的隔离证据：即使代码写错也污染不到真实记录 |
| 导出 | `ACTION_CREATE_DOCUMENT`（SAF）写 JSON；另提供 CSV 便于 Excel | 用户自己选位置，不需要任何存储权限 |

数据模型（第一版）：

```kotlin
@Entity(tableName = "session")
data class MetronomeRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startWallMs: Long,          // 展示与归档
    val endWallMs: Long?,           // null = 未正常结束
    val elapsedMs: Long,            // 已运行（排除暂停）
    val initialBpm: Int,
    val endReason: EndReason,       // USER_STOP | INTERRUPTED
    val name: String?               // 用户可改，默认空
)

@Entity(tableName = "cadence_change")
data class CadenceChange(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val elapsedMs: Long,            // 相对开始
    val newBpm: Int
)
```

**开始/结束时间与步频变化保留为原始事实，不提供逐项改写**（规格 3.3）；只有 `name` 可改。

### 3.7 依赖注入与异步

- **Hilt 1.4.0**：服务与 Compose 都要注入同一份 `ClickEngine` / repository 实例，`@AndroidEntryPoint` 对 Service 原生支持。
- Coroutines + Flow；服务暴露 `StateFlow<MetronomeState>`，页面用 `collectAsStateWithLifecycle`。
- 不用 RxJava；不引入 Ktor/OkHttp（v1 无网络）。

### 3.8 依赖清单（`gradle/libs.versions.toml` 摘要）

```toml
[versions]
agp = "8.13.2"                  # 必须 >= 8.13.2：8.13.2 带 R8 8.13.19，官方说明称其支持 Kotlin 2.3
kotlin = "2.3.21"
ksp = "2.3.10"                  # ★ 待最终核实，见 3.9
composeBom = "2026.09.00"
activityCompose = "1.13.0"
lifecycle = "2.11.0"
navigation3 = "1.2.0"
room = "2.8.5"
datastore = "1.3.0"
hilt = "1.4.0"
media3 = "1.11.1"

[libraries]
androidx-compose-bom      = { group="androidx.compose", name="compose-bom", version.ref="composeBom" }
androidx-compose-material3= { group="androidx.compose.material3", name="material3" }
androidx-compose-ui-tooling-preview = { group="androidx.compose.ui", name="ui-tooling-preview" }
androidx-activity-compose = { group="androidx.activity", name="activity-compose", version.ref="activityCompose" }
androidx-lifecycle-runtime-compose = { group="androidx.lifecycle", name="lifecycle-runtime-compose", version.ref="lifecycle" }
androidx-lifecycle-viewmodel-compose = { group="androidx.lifecycle", name="lifecycle-viewmodel-compose", version.ref="lifecycle" }
androidx-navigation3-runtime = { group="androidx.navigation3", name="navigation3-runtime", version.ref="navigation3" }
androidx-navigation3-ui = { group="androidx.navigation3", name="navigation3-ui", version.ref="navigation3" }
androidx-room-runtime = { group="androidx.room", name="room-runtime", version.ref="room" }
androidx-room-ktx = { group="androidx.room", name="room-ktx", version.ref="room" }
androidx-datastore-preferences = { group="androidx.datastore", name="datastore-preferences", version.ref="datastore" }
androidx-media3-session = { group="androidx.media3", name="media3-session", version.ref="media3" }
hilt-android = { group="com.google.dagger", name="hilt-android", version.ref="hilt" }
```

> 注：Oboe / NDK 依赖**不引入**；图标按需自绘矢量或用 `material-icons-extended`，避免包体积膨胀。

### 3.9 版本核实状态表（开工前请照此复核一次）

| 坐标 | 本文取值 | 核实状态 |
| --- | --- | --- |
| `androidx.activity:activity-compose` | 1.13.0 | ✅ 官方迁移文档中的当前版本 |
| `androidx.compose:compose-bom` | 2026.09.00 | ✅ 官方 Compose 依赖设置页明确标注"最新稳定版" |
| `androidx.lifecycle:lifecycle-runtime-compose` | 2.11.0 | ✅ Jetpack 稳定版发行清单（2026-06-17） |
| `androidx.navigation3:*` | 1.2.0 | ✅ 2026-09-23 起 stable |
| `androidx.room:room-runtime` | 2.8.5 | ✅ Jetpack 稳定版发行清单（2026-09-09） |
| `androidx.datastore:datastore-preferences` | 1.3.0 | ✅ 官方发行说明（2026-10-21） |
| `com.google.dagger:hilt-android` | 1.4.0 | ✅ Jetpack 稳定版发行清单（2026-07-01） |
| `androidx.media3:media3-session` | 1.11.1 | ✅ 官方更新页（2026-09-11） |
| **`com.google.devtools.ksp:symbol-processing-gradle-plugin`** | **2.3.10** | ⚠️ **需在建库当天复查**。Kotlin 官方文档载明"最新 KSP 2.3.10 支持 KGP 2.2.10–2.3.x"，本文 Kotlin 为 2.3.21，落在该区间内，理论可用；但 KSP 可能已发布更新版本。做法：新建版本目录时到 [KSP GitHub Releases](https://github.com/google/ksp/releases) 取与 2.3.21 匹配的那一版 |

> **给执行者的一句话**：除了 KSP 这一项，其余坐标都可以直接抄。KSP 是唯一一个"版本跟随关系最绕"的插件，值得花 30 秒查一次。

---

## 4. 被否决的候选（留痕）

| 候选 | 否决理由 |
| --- | --- |
| Oboe / NDK 音频 | 抖动已由 AudioTrack 阻塞写解决到硬件时钟级；NDK 带来构建/ABI/排错成本。列为"真机验证失败时的 Plan B" |
| ExoPlayer + 循环音频 | 播放器内部有自己的缓冲与时钟，改速要重建 MediaItem，难以保证"下一拍生效、不中断记录"；且会把 QuickSettings 媒体控制绑到假播放态 |
| MediaPlayer / SoundPool 排拍 | 每次 `start()` 都由 OS 调度投递，抖动不可控；不适合 160–220 步/分长时间连续运行 |
| Foreground service 之外的 WorkManager / Alarm 保活 | 无法连续出声；且 Android 17 会静默压制非法后台音频 |
| SharedPreferences | 已被 DataStore 取代（异步、事务、流式） |
| 服务端 + 账号同步 | 与"数据留在本机、离线可用、单人使用"直接冲突；见第 2 节 |
| Cloud TTS / 联网语音 SDK | 规格要求离线可用；第一版也没有真实语音播报。将来接 Android 系统 `TextToSpeech`，用 `KEY_FEATURE_NETWORK_SYNTHESIS=false` 强制离线合成 |
| kapt | AGP 8.13 上 Room 已支持 KSP；KSP 是唯一方向，且为将来升 AGP 9 铺路 |

---

## 5. 工程结构

v1 **单 `:app` 模块**，按特性分包。刻意不过早拆多模块 —— 三个第 2 版可能才需要的边界现在就用清晰包名划出来：

```
com.daydayup
├── MainActivity.kt                    # 单 Activity，Compose 宿主
├── nav/
│   ├── AppNav.kt                      # NavDisplay + 回退栈 + 全局拦截
│   └── Route.kt                       # @Serializable 路由键清单
├── core/
│   ├── audio/
│   │   ├── ClickEngine.kt             # interface：start/stop/setBpm/setVolume
│   │   ├── AudioTrackClickEngine.kt   # 采样级排拍实现（唯一 Dispose/Release 处）
│   │   └── AudioFocusPolicy.kt        # 不抢焦点策略 + 焦点变化观测
│   ├── time/ElapsedClock.kt           # 单调时钟 + 暂停区间扣除
│   ├── ui/theme/{Theme,Color,Type}.kt
│   └── demo/DemoScope.kt              # 演示标识与隔离断言
├── metronome/                          # ★ 真实功能
│   ├── MetronomeService.kt            # FGS，唯一权威状态源
│   ├── MetronomeSession.kt            # 纯函数状态机（已有单元测试）
│   ├── Cadence.kt                     # 160–220 校验
│   ├── MetronomeNotification.kt
│   ├── CallInterruptMonitor.kt        # MODE_IN_CALL + Telephony 双路检测
│   ├── data/{SessionRepository,Room…}.kt
│   ├── export/RecordExporter.kt
│   └── ui/{MetronomeScreen,HistoryScreen,RecordDetailScreen}.kt + MetronomeViewModel.kt
├── training/                           # 演示：课表 + 力量训练
│   ├── demo/{DemoPlanRepository,DemoState}.kt
│   ├── ui/{TrainingScreen,PlanEditorScreen,WeekScheduleScreen,WorkoutScreen}.kt
│   └── WorkoutPhaseMachine.kt         # 准备/起落/确认/休息状态机
├── coach/                              # 演示：AI 教练
│   ├── VoiceCoachGateway.kt           # ★ 预留的第二版联网接口
│   ├── DemoVoiceCoachGateway.kt
│   └── ui/CoachScreen.kt
├── report/                             # 演示：报告
└── settings/
```

**隔离硬规则**（用 Detekt 自定义规则或简单的包依赖断言守住）：
`training/`、`coach/`、`report/` **不得 import** `metronome/data`。这条规则的价值大于任何一条注释。

---

## 6. 单模块 vs 多模块：为什么现在就选择单模块

v1 的功能总量约 15 个目的地、1 个前台服务、2 张表。此时拆多模块会带来三类净成本：

| 成本 | 说明 |
| --- | --- |
| 构建配置重复 | 每个 module 都要重复 Compose/Hilt/Room 配置，且容易版本漂移 |
| 边界过早冻结 | 第二版的模块边界大概率和第一版猜的不一样，现在拆等于提前许下一次重构 |
| 编译收益有限 | 项目小，增量编译收益远低于配置成本 |

所以采用"**单模块 + 严格包名边界 + 一条自动检查**"的折中：物理上不拆，但逻辑边界用 `core/demo` 包约束固定，将来真要拆 module 时直接按包名搬家即可。

触发拆分的信号（届时才动手）：
1. 需要抽共享逻辑给第二版的多平台产物（即引入 `expect/actual`）；
2. 全量构建时间超过可接受阈值；
3. 出现第二个 product flavor 或动态功能模块。

---

## 7. 风险、环境缺口与验证计划

### 7.1 当前本机环境缺口（**必须先补齐才能编译**）

实测（`git bash` 环境）结论：

| 项 | 现状 | 需要动作 |
| --- | --- | --- |
| JDK | **未安装**（`java` 不在 PATH，Program Files 下无 JDK，`~/.gradle/jdks` 为空） | 装 JDK 17（推荐随 Android Studio 的 JBR，或 Temurin 17） |
| Android SDK | 已装在 `C:\Users\glee-\AppData\Local\Android\Sdk` | 已有 |
| SDK Platform | **只有 `android-35`** | 装 `platforms;android-36` |
| SDK Build Tools | 已有 34.0.0 / 35.0.0 / 35.0.1 | AGP 8.13 要求 Build Tools ≥ 35.0.0（默认即 35.0.0），**已满足**；若编译时 AGP 提示缺 36.x 再补装 |
| 系统镜像 | 有 `android-35 / google_apis_playstore / x86_64` | 够用；建议补一个 Android 16 镜像验证新行为 |
| Gradle | `~/.gradle/wrapper/dists` 有 8.6 / 8.11.1 | 生成 `gradlew`（当前工程缺 wrapper），或直接用已缓存版本起步 |
| Android Studio | **未安装** | 强烈建议装（含 SDK Manager、布局检查、Profiler、设备上 Compose/音频调试） |
| `local.properties` | 不存在 | 指向上述 SDK 路径 |

### 7.2 只能靠真机验证的风险（规格第 9 节要求登记）

| 风险 | 验证方式 | 兜底 |
| --- | --- | --- |
| 目标机型能否锁屏后连续出声 | 锁屏 30 分钟实测，两端各 Android 14/16 机型 | 提示用户关闭该 App 的电池优化，并在设置页给出跳转入口 |
| 与音乐/微信读书共存是否被 ROM 特殊处理 | 真机逐一验证；华为/小米/OPPO 的音频策略差异大 | 如实显示"声音状态"；给出音量调节入口，不谎报 |
| 通话期间/结束后的音频恢复 | 真实来电测试 | `OnModeChangedListener` 兜底重扫一次 `AudioManager.mode` |
| AudioTrack 排拍抖动是否可接受 | 录屏/录音后用音频软件量拍点间隔；目标：**长时间平均间隔误差 < 1ms，单拍抖动 < 5ms** | 不达标再评估 Oboe |
| minSdk 26 机型上的通话态识别 | API 26–30 机型实测 `READ_PHONE_STATE` 授权链路 | 权限被拒时降级为"界面仍显示运行中，但无法感知通话"，并在设置页明示该限制 |

### 7.3 测试策略

- **单元测试（JVM）**：`Cadence` 边界、`MetronomeSession` 暂停扣除与调速事件（已有）、`WorkoutPhaseMachine` 阶段推进、导出序列化、演示写入不外溢断言。用 Turbine 断言 Flow。
- **UI 测试（Compose）**：BPM 越界输入、开始/暂停/结束链路、记录删除、演示页恒带"演示"标识。
- **集成/插桩测试**：服务启停、通知按钮的 `PendingIntent` 能否正确回到节拍页、记录落库与导出文件写入。受开发环境限制，以模拟器 + 手动清单执行。
- **真机清单**（不可自动化，写进 README）：锁屏、切音乐/读书 App、来电、耳机断连、手动杀进程后重启、低电量模式。

---

## 8. 第二版的技术预留（现在只留接口，不实现）

| 能力 | 预留点 |
| --- | --- |
| 课表持久化与执行 | `PlanRepository` 接口，v1 实现为 `DemoPlanRepository`（内存 + demo.db） |
| 定时语音指导 | `SpeechDirector` 接口 + 系统 `TextToSpeech`（离线），事件源来自 `WorkoutPhaseMachine` |
| AI 实时语音 | `VoiceCoachGateway`（见 2.2）；开始前的联网与数据处理说明做成强制前置页 |
| 设备接入（手表/心率） | `DeviceConnector` 接口；权限只在用户主动点击时才申请（C7/规格 7） |
| 基于真实数据的报告 | 报告数据源统一走 `MetricSource` 枚举：`MANUAL_CONFIRM / METRONOME_RECORD / DEVICE / EXAMPLE`，UI 强制渲染来源标签（规格 7 的可追溯要求） |
| AGP 9 迁移 | 已用 KSP，未用 kapt；届时按官方迁移步骤执行 |

---

## 9. 待确认事项

1. **targetSdk 是否现在就提到 36**：建议提。自分发场景不强求，但提上去能提前暴露 Android 16 的后台音频差异；代价是需要同时装 SDK Platform 36。
2. **minSdk 是否提到 31**：提到 31 后，通话态识别可用 `AudioManager.OnModeChangedListener` 独立完成，不再需要 `READ_PHONE_STATE`，隐私更干净、代码更简单。代价是放弃 Android 8–11 设备。若目标机只有一台现代手机，建议直接提到 31。
3. **`MetronomeService` 是否直接用 media3 `MediaSessionService` 替换**：官方在 Android 17 后台音频强化文档中**明确推荐使用** `MediaSessionService` 来管理后台音频，因为它替开发者处理播放生命周期。若第二版的设备/多机型适配会较早开始，现在就用 media3 的壳更划算 —— `ClickEngine` 接口不变，改动局限在服务外壳。否则按第 3.5 节保持自建前台服务 + `MediaSession`。
4. **JDK / SDK 安装方式**：装 Android Studio 一并解决（推荐，还带设备调试与 Profiler），还是单独装 JDK 17 + 命令行 SDK Manager（本机已有 `platform-tools`）。

---

## 10. 版本核实来源（2026-10-31 查证）

| # | 结论 | 来源 |
| --- | --- | --- |
| 1 | AGP 8.13 要求 Gradle 8.13、JDK 17，最高支持 API 36.1，默认 SDK Build Tools 35.0.0；AGP 8.13.2 使用 R8 8.13.19 且**该版本支持 Kotlin 2.3** | Android 官方《Android Gradle plugin 8.13.0 release notes》 |
| 2 | KGP `2.3.20–2.3.21` 支持 Gradle `7.6.3–9.3.0`、AGP `8.2.2–9.0.0`；Kotlin 2.3 → AGP 最低 8.13.2、R8 8.13.19 | Kotlin 官方《Configure a Gradle project》兼容表 + Android 官方《Kotlin support》表 |
| 3 | Compose 最新稳定 BOM = `2026.09.00`；`activity-compose` = `1.13.0` | Android 官方《设置 Compose 依赖项》/《迁移至 Android Studio》 |
| 4 | Navigation3 `1.2.0` 于 2026-09-23 转 stable | Android 官方《navigation3 releases》 |
| 5 | Lifecycle `2.11.0`、Room `2.8.5`、Hilt `1.4.0`、`Media3 1.11.1` 均为稳定版 | AndroidX 稳定版发行清单 / Android 官方最新更新页 |
| 6 | DataStore `1.3.0` 于 2026-10-21 发布 | Android 官方《DataStore releases》 |
| 7 | Google Play 自 2026-08-31 起要求新应用及更新 target ≥ API 36 | Google Play 官方《目标 API 级别要求》 |
| 8 | Android 17 起对后台音频（播放/焦点/音量）强制执行限制；官方建议使用 media3 `MediaSessionService`；瞬时中断 10 分钟内应保持前台服务存活 | Android 官方《Background audio hardening》 |
| 9 | Android 12+ 起音频焦点由系统托管：当第二方请求 `AUDIOFOCUS_GAIN` 时，第一方（持有 `GAIN`、`usage` 为 `USAGE_MEDIA`、且非 `CONTENT_TYPE_SPEECH`）会被系统**强制淡出** | Android 官方《Manage audio focus》。这是第 3.4 节"绝不申请 `AUDIOFOCUS_GAIN`"的直接依据 |
| 10 | AudioTrack STREAM 模式下 `write()` 阻塞，直至硬件环形缓冲接收完整片段；以样本为单位排拍可把抖动锚定到音频硬件时钟 | 音频工程实践资料 + Android AudioTrack 文档（属工程经验，需真机量化验证，见 7.2） |
