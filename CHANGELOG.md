# 更新日志

本项目自 1.8.3 起正式维护本文件。更早的历史请查阅 `git log`。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

## [1.9.32] - 2026-10-15

### 删除
- **彻底移除 Shizuku 全链路代码与依赖**。沿用 1.9.31 对根因的判定（国产 ROM 上 Shizuku 常驻守护结构性活不长、且 `excludeFromRecents` 等手段更可靠），本次连根拔掉、不留尾巴：
  - 删除 `shizuku/` 包（`ShizukuWhitelist`、`ShizukuShellService`、`ShizukuProtocol`）、`aidl/`（`IShizukuShell.aidl`）、`ShellWakeReceiver`；
  - `AndroidManifest.xml` 移除 `API_V23` 权限声明、`ShizukuProvider` 与 `ShellWakeReceiver` 声明及大段注释；
  - `app/build.gradle.kts` 关闭 `aidl` buildFeature、移除 `shizuku.api`/`shizuku.provider` 依赖；
  - `gradle/libs.versions.toml` 删除 `shizuku` 版本与两个 library 定义；
  - `proguard-rules.pro` 删除对应 `-keep` 规则；
  - `GachaApp` / `ResinReminder` / `SettingsViewModel` / `SettingsScreen` 中所有引用、方法、注释一并清除（仅保留 1 处如实的历史说明注释，说明"曾有两触发源、现已只剩系统闹钟一条路径"，无害）。

### 新增
- **「隐藏后台」开关（设置页 → 抗清后台必做）**：新增 `AppPrefs`（`app_prefs` DataStore，与提醒链路 `resin_reminder_store` 隔离）持久化 `KEY_HIDE_FROM_RECENTS`；`MainActivity` 在 `onCreate`/`onResume` 通过 `ActivityManager.AppTask.setExcludeFromRecents(true)` 应用——注意框架的 `Activity` 本身**没有** `setExcludeFromRecents` 方法，必须拿到当前 `AppTask` 才能隐藏最近任务卡片。开启后本应用**不出现在最近任务列表**，用户划不到卡片、一键清理无从杀起，是 App 侧对抗清后台最彻底的手段。代价：只能从桌面图标进入（已通过开关即时生效，无需重启）。

### 调整
- **「允许自启动」引导保留并强化**（设置页 ①）：Android 无统一自启动授权 API，App 无法自动获取，只能跳应用详情页引导用户手动开启；本版未新增任何未经验证的授权代码，延续 1.9.31 已落地的诚实引导。
- 保留「② 任务卡片加锁 / ③ 电池无限制」系统设置三件套引导，与隐藏后台开关共同构成 App 侧抗清后台的能力边界。

### 说明
- 提醒主触发源仍为 `AlarmManager.setAlarmClock`（系统级真实闹钟），清后台会被 ROM 清掉，仍需用户完成系统设置三件套兜底——这是 App 侧能做到的诚实上限。

### 优化（性能）
- **首页 / 统计页卡池查询并发化**：`HomeViewModel.loadStats` 与 `StatsViewModel.loadStats` 原先把 6 个卡池（角色/角色-2/武器/常驻/新手/集录）的 Room 查询**串行**执行；统计页还额外串行查图鉴、日历两组。现改为用 `coroutineScope` + `async` **并发**派发，全部查询并行跑在 Room 的 IO 线程池上，摊薄 6~8 次往返延迟。大数据量账号（数万条记录）下，统计页首屏与首页统计区的加载体感明显变快。
- **去掉进首页的冗余闹钟重排**：`loadDailyNote` 在"本地已有今日快照"的缓存命中分支里，每次进首页都会 `reminderManager.reschedule()`（一次 DataStore 读 + Gson 解析 + 闹钟操作）。该重排在 `GachaApp.onCreate` 进 App 时已经做过、配置变更时也会做，属重复劳动。已移除缓存命中分支里的重排，避免每次进首页无谓的 I/O 与闹钟操作开销。

## [1.9.31] - 2026-10-14

### 修复（重要认知修正）
- **明确承认：Shizuku 常驻守护无法保证「清后台也响」**。**经社区实证与日志交叉印证**：国产 ROM 把应用「划掉后台 / 一键清理」后，应用进入 `stopped` 状态，系统会在分发阶段**直接吞掉**拉起广播——即便带 `FLAG_INCLUDE_STOPPED_PACKAGES`（`am -f 0x20`）也一样（SegmentFault 等实测：三星模拟器可、小米不可）。Shizuku 守护进程本身在国产 ROM 上也"存活难超 30 分钟"（GKD 社区结论）。所以守护只作为「App 仍存活时」的额外保障，**不再承诺清后台也响**。
- **设置页去掉所有"清后台也不丢提醒 / 清后台也会响"的承诺性措辞**（Shizuku 区块正文、连接状态、Idle/AlreadyWhitelisted/Done 文案、自检区块），避免继续误导用户。
- **新增「抗清后台必做（系统设置）」引导区块**：这是真正对症、社区（GKD 官方 FAQ）验证有效的**系统设置三件套**——① 允许自启动、② 任务卡片加锁（GKD 列为首选）、③ 电池无限制。此前 App 只引导了"电池优化白名单"，**缺了自启动与卡片锁这两样关键项**，而正是它们决定"清掉后台后还活不活"。App 自身无法请求这些权限，必须引导用户在系统设置完成。

### 说明
- 闹钟主用 `setAlarmClock`（系统级真实闹钟，Doze 可唤醒、优先级最高、普通清理不重置）这一做法维持不变——它已是社区推荐的最优闹钟类型，但**扛不住"应用被清到 stopped"**，因此必须由上面的系统设置三件套兜底。
- 纯靠 App 代码已到极限：QQ/微信能收到是因为有厂商推送白名单，普通 App 复制不了。本版把"用户必须做的系统设置"讲清楚、引导到位，是 App 侧能做到的诚实上限。

### 修复
- **「后台一清、重开 App 就失效」的两个真实原因**（用户实测反馈）：
  1. **`reschedule()` 冷启动时会把守护排程清空**。旧实现开头**无条件 `cancel()`**（清系统闹钟 + 清守护进程的 `setSchedule(0)`），再计算下一次时刻；而本方法在 [GachaApp.onCreate] 每次冷启动都会跑。冷启动瞬间若快照尚未就绪（`loadLatest()` 返回 null）或算不出候选，旧代码"清完就 return"——**重开 App 反而把已经守好的提醒清成了 0**。现改为：**只有确实算出新排程（或明确不需要）时才动闹钟**，算不出来就原样保留；系统闹钟用同一个 PendingIntent 覆盖，不再需要"先 cancel 再 set"。
  2. **界面在重开后谎报"尚未启用"**。`_shizukuState` 是纯内存态，进程一重启即回到 `Idle`，界面显示"尚未启用（点上方按钮开始）"，而守护进程其实还活着、排程也在。现用持久化标记还原状态，并在自检确认守护已连接时自动纠正；界面文案也以真实连接状态兜底，**绝不再出现"已连接 / 尚未启用"同屏自相矛盾**。

### 新增
- **「上次被守护拉起」持久凭据**（`ResinReminderStore.markDaemonWake`）：[ShellWakeReceiver] 每次被守护拉起都把时刻与 action 落盘，设置页自检新增 `上次被守护拉起=…`。这是证明 **daemon → AMS → App 三跳全通**的唯一持久证据——此前这条链路只能靠"到点没响"反推，排查时无法区分"守护没发 / 发了没投到 / 投到了没发通知"。这条一旦出现，问题范围立即缩小到"发通知"一段。
- [ShellWakeReceiver] 改为对测试与真实两种 action 都走 `goAsync()` + 协程，保证落盘与通知在本进程被回收前完成。

## [1.9.29] - 2026-09-29

### 修复
- **根因确认：上一版把 `ping()` 插在了 AIDL 最前面，导致与"更新前没被杀掉的旧守护进程"事务码整体错位。** 新 App 调 `ping`(码1) 被旧守护按 `exec`(码1) 处理、读出一个 null 参数，于是自检串里出现 `NullPointerException: ... ShizukuShellService.exec, parameter command`；新 `getSchedule`(码4) 打到旧 `setTest`(码4) 返回 void，于是"探活假成功"拿到 0。→ 现把 `ping()` **移到 AIDL 末尾**，方法码 1–6 与旧守护保持一致，即使遇到旧守护也不会全面错乱
- **`SERVICE_VERSION` 升版拦不住旧守护**。查证 Shizuku 客户端库：`ShizukuServiceConnections.CACHE` 的 key **只有 `tag`，version 不参与**；服务端也按 (uid, processNameSuffix) 复用已存在进程。所以仅升 version 既不会让客户端丢弃旧连接、也不会让服务端重建进程 → 现改为**绑定后做 `proto` 握手，发现旧守护就主动销毁重建**
- **`onServiceConnected` 连环回调 8 次**：`ShizukuServiceConnection.connections` 是个 `Set`，每次用**新** `ServiceConnection` 调 `bindUserService` 都会累积一条，服务一连上就全部回调。已用 `bindMutex` 串行化绑定

### 新增
- **旧守护销毁重建**：握手失败时，① 向旧守护直接发 Shizuku 约定的「销毁」事务（16777115，守护 `onTransact` 里 `System.exit(0)`）；② `unbindUserService(..., remove=true)` 请 Shizuku 清记录；③ 等待后重建。若仍不匹配，则**换一套全新 `processNameSuffix` + `tag`** 强制 Shizuku 开新进程（兜底保证）
- 守护 `diag()` 增加 `proto=5` 字段（必须在最前），`ping()` 返回串也带 `proto=`；设置页自检串新增 `proto期望=5`，与 `daemon:` 行开头的 `proto=` 对照即可一眼看出"守护到底换没换成新的"
- `ShizukuProtocol`：客户端与守护共用的协议常量（描述符 / 销毁事务码 / `PROTO`），消除两处各写一份的隐患

## [1.9.28] - 2026-09-29

### 修复
- **编译期缺陷：本轮给 AIDL 加 `ping()` 探针时，在 `ShizukuWhitelist.kt` 末尾遗留了一份与 191 行重名的 `useDaemon`（326–350 行）**，Kotlin 会因「冲突的重载 / 重复声明」直接编译失败，带 `ping` 的那次构建**根本没编出来**。已删除末尾重复定义
- **编译期缺陷续：`withLock` 扩展函数漏了 import**（只 import 了 `Mutex` 没 import `withLock`），`daemon()` 里调用 `bindMutex.withLock { }` 报 `Unresolved reference 'withLock'`，并连带把 lambda 误判成非挂起上下文而报「挂起函数只能在协程体内调用」。已补 `import kotlinx.coroutines.sync.withLock`
- **SERVICE_VERSION 未随 AIDL 变更升版**：AIDL 最前面新增了 `ping()` 探针（所有方法事务码整体 +1），但 `SERVICE_VERSION` 仍是 3。不升版时 Shizuku 会复用上一版缓存在系统里的旧守护进程，旧进程不认识 `ping()`，新 App 的所有跨进程调用都会失败且报错信息看不出根因。现已升到 4
- **`ping()` 探针首次落地**：把「连接建立」与「调用可用」两件事分开验证。设置页自检串现在先打 `ping:`，再打 `daemon:`——`ping` 通而 `diag` 不通就把问题锁定在 diag 实现（如返回值超 Binder 事务缓冲区），连 `ping` 都不通就说明连接本身没起来。没有这个探针时两种病因在界面上长得一模一样

### 新增
- `ShizukuWhitelist.diagnostics()` 的 `ping:` 段：自检串能自己讲清楚断在哪一跳，无需再反复装包试

## [1.9.27] - 2026-09-29

### 修复
- **设置页出现自相矛盾的状态：「守护自检：`<守护服务未连接>`」与「常驻守护进程已就绪」同时显示**。真实情况是 bind 根本没成功，但界面把它显示成了成功 —— 这句乐观文案骗了用户也骗了我，让上一版误判成"链路本身还有问题"
- **绑定失败被完全吞掉**：`Shizuku.bindUserService()` 的调用被 `runCatching` 包着，异常既不落日志也不上报；`readSchedule` 返回 0 又被解释成"未启用 / 提醒未开启"。于是"没连上"和"没排程"两种完全不同的故障，在界面上是同一句话
- **守护是懒绑定的，而用户看到的第一个信号总发生在首次绑定之前**：以前只有真正要推时刻时才去 bind，导致设置页回读时永远读到"未连接"。现在 `GachaApp.onCreate` 就在后台主动连一次并记录全过程
- **绑定超时从 5 秒放宽到 15 秒**：拉起 UserService 需要 Shizuku fork 一个 shell 身份的 app_process，慢的时候要好几秒。原超时会让"其实起得来只是慢"被误报成"连不上"

### 新增
- `ShizukuWhitelist.diagnostics()`：一次给出完整自检报告 —— `installed` / `authorized` / `version`（Shizuku API 版本）/ `uid`（2000=shell 模式，0=root 模式）+ **绑定全过程时间线**（何时发起、上游抛了什么异常、有没有收到 `onServiceConnected`、耗时多少、是否超时）+ 守护内部 `diag()`
- 绑定全过程每条都同步写入 AppLog（`tag=ShizukuWhitelist, step=bind`），导出日志即可看到完整链路
- 设置页新增 `daemonConnected` 状态：把「守护是否已连接」和「是否持有待触发时刻」彻底分开显示。未连接时用红色明确写出「此时『清后台也不丢提醒』并不成立」

### 变更
- 版本号：`versionCode 87 → 88`，`versionName 1.9.26 → 1.9.27`

## [1.9.26] - 2026-09-29

### 修复
- **守护进程发出来的广播根本投不进本应用（用户反馈「还是无法保留后台」，附日志取证）**。这是本次唯一的主因：`ShellWakeReceiver` 声明的是 `android:exported="false"`。按 Android 组件导出规则（OWASP MASTG-BEST-0052 口径），`exported=false` 的清单组件只有「同 App / 同 sharedUserId / privileged system components」能触达，而 Shizuku User Service 跑在 **shell UID 2000，不在豁免名单内** —— 广播被 AMS 直接丢弃。用户日志里的现象完全吻合：App 侧 4 次 `守护接管=true`（说明绑定与跨进程调用都是通的），到点之后却没有任何后续痕迹
- **守护的唤醒方式不该二选一**。上一版改成"`Context#sendBroadcast` 优先、失败才降级 `am`"是错的：Shizuku 官方 README 明确写着 User Service **不是完整的 App 进程**，"standard framework features like **BroadcastReceivers** or ContentResolvers **may not function**"，即它可能调用成功却什么都没发出去；而反过来 `am` 依赖 `/system/bin/am` 脚本与 `app_process` 环境，在部分 ROM 上同样会失灵。现在两条路**无条件各发一次**，重复投递由 60 秒去抖折叠
- **测试闹钟被真实提醒的排程静默覆盖**：真实提醒与测试闹钟此前共用一个槽位，排好 10 秒测试后，App 任何一次 `reschedule()`（冷启动、拿到新便笺快照、改配置都会触发）都会把它清掉。现拆成 `real` / `test` 两个独立槽位
- **守护的调度线程是守护线程**：它就是这个进程存在的全部意义，一旦进程里再无非守护线程 JVM 会直接退出，表现为「守护默默死掉、到点完全不响」且没有任何报错。已改为 `isDaemon = false`
- **`am broadcast` 投不到 stopped 状态的包**：ROM 的「划掉后台」往往等价于 force-stop，包会被置成 stopped，AMS 会默认给 Intent 补 `FLAG_EXCLUDE_STOPPED_PACKAGES`。现 Intent 侧加 `FLAG_INCLUDE_STOPPED_PACKAGES`，`am` 侧加 `-f 0x00000020`（同一 flag 的数值）

### 新增
- **守护自检回读**（`IShizukuShell.diag()`）：这是排查「到底哪一跳断了」的唯一可信手段。此前跨进程链路全黑，只能看到 App 侧"我推过去了"、看不到守护侧发生了什么，过去几轮之所以反复改错地方正是缺这一层。回读字段：`uptime`（进程存活秒数）/ `ticks`（调度循环心跳，不涨=已死）/ `fired`（尝试唤醒次数）/ `lastFire` / `action` / `err` / `via`（走的哪条路）/ `am`（am 命令原始输出，能直接看到 Permission Denial）/ `ctx` / `real` / `test`
- 守护到点时 String.format：现在相信这个实现细节可能不该那么乐观。总之 App 侧被拉起会写 `ShellWakeReceiver.onReceive` 日志 —— 这条日志一旦出现，就证明「shell 守护 → AMS → 本 App 被拉起」三跳全通，剩下只可能在发通知这一段
- 设置页「抗杀后台」区块新增等宽字体的「守护自检」实时展示，用户无需导出日志就能自查
- `ShizukuWhitelist.pushTest` / `readTest` / `diag`：测试槽位与自检快照的接入封装

### 变更
- UserService 版本号 2 → 3（AIDL 新增 `setTest` / `getTest` / `diag`）。不升版时 Shizuku 会把新 App 连到系统里缓存的旧服务进程上，调用新方法必然失败且报错毫无指向性
- 版本号：`versionCode 86 → 87`，`versionName 1.9.25 → 1.9.26`

### 安全取舍
- `ShellWakeReceiver` 被迫改为 `exported=true`，攻击面扩大：第三方 App 可伪造该广播。**评估后接受**：该接收器只做「发一条通知 / 跑一次判定」，既不导出数据也不执行敏感操作，最坏后果是一次骚扰性通知；相比「清后台后提醒彻底失灵」，这是明显划算的一边。

## [1.9.25] - 2026-09-29

### 修复
- **`1.9.24` 引入的严重回归：「10 秒后测试闹钟」在前台也不响**（用户当场反馈）。1.9.24 在检测到 Shizuku 已授权时，把原本走 `AlarmManager` 的系统闹钟**整个撤掉**、改为只交给守护进程；守护那条链路一旦静默失败就**没有任何兜底**，于是连前台都不响。这是典型的"用未验证路径替换已验证路径"
- 测试闹钟恢复为**双链路并存**：系统闹钟始终排在（保证进程活着时一定能响），Shizuku 守护作为**附加的一层**仅在被清后台杀掉后起效，失败不影响前者
- 守护进程推送失败丢失线索：`pushSchedule` 原本被 `runCatching` 静默吞掉，界面仍报"已排入"。现已写入 AppLog（`守护接管=true/false`），并延迟 1.5 秒回读真实状态
- 守护进程的唤醒方式改为优先用自身 `Context#sendBroadcast`（并加 `FLAG_INCLUDE_STOPPED_PACKAGES`），失败才降级到 shell 执行 `am broadcast`。后者依赖 `/system/bin/am` 脚本与 `app_process` 环境，部分 ROM 上会毫无征兆地失败且无 stderr 可查
- 测试闹钟通知去抖：双链路并存时若不折叠，用户会收到两条重复通知，故 `notifyTestAlarm` 增加 1 分钟窗口

### 变更
- 版本号：`versionCode 85 → 86`，`versionName 1.9.24 → 1.9.25`

### 说明
- `1.9.24` 已发布即失效，请直接使用 `1.9.25`

## [1.9.24] - 2026-09-28

### 修复
- **树脂提醒「清后台后不响」**：这是本次的核心问题。此前提醒只依赖 `AlarmManager.setAlarmClock`，而国产 ROM 的「一键清理 / 划掉后台」会**强杀本应用进程、并连带取消它排下的全部闹钟**——用户实测「10 秒测试闹钟被清后台后一样没有」即为此因。先前把本应用加入 `deviceidle` 白名单也无效：白名单只管省电（Doze）策略，拦不住「杀进程 + 清闹钟」这条路径
- 引入与 GKD 同款的 **Shizuku 常驻守护**：授权后启动一个以 shell UID(2000) 常驻的 UserService（关键开关 `UserServiceArgs.daemon(true)`，使本应用进程被杀后该进程仍存活）。它不在 ROM 的清理名单里，由它替我们守着触发时刻，到点执行 `am broadcast` 把（即便已被杀的）App 拉起来发通知
- R8 混淆会把上述广播链路打断：守护进程拼的这个类名此前会拖垮整条链路，已显式 keep `ShellWakeReceiver`
- 同一时刻重复通知：进程活着时系统闹钟与守护进程会几乎同时到点，`onAlarmFired` 增加 1 分钟去抖窗口，折叠掉双触发
- 授权后需重启 App 才生效：守护进程绑定完成时还未握住任何触发时刻。现在授权成功即立即重排一次并推送，无需重启
- 「10 秒后测试闹钟」的反馈文案此前未区分链路，导致用户按提示清后台却没响时以为链路坏了；现在会如实说明本次走的是「Shizuku 常驻守护」还是「系统闹钟」，并提示后者的已知限制

### 新增
- `ShellWakeReceiver`：守护进程的唤醒落点，分别处理真实提醒（跑完整判定→通知→重排）与测试闹钟（仅发通知）
- `ShizukuWhitelist.pushSchedule` / `clearSchedule` / `readSchedule`：把触发时刻交给守护进程、解除武装、回读当前持有的时刻
- 设置页「抗杀后台」区块改造为「Shizuku 常驻守护」语义，并新增守护进程持有时刻的回读展示与「回读守护」按钮——这是判断「清后台会不会漏提醒」唯一可信的信号

### 变更
- `IShizukuShell` 增加 `setSchedule` / `getSchedule`，并移除危险的 `destroy()`（改由 `onTransact` 拦截事务码 16777115）
- `ResinReminderManager.reschedule()` 在排完系统闹钟后同步给守护进程；`cancel()` 同时解除二者武装，避免「提醒已关、到点仍被拉起」的幽灵通知
- UserService 版本号 1 → 2，并显式设置稳定 `tag`（此前依赖类名，在开启 R8 后不稳定，可能拖出孤儿进程）
- 版本号：`versionCode 84 → 85`，`versionName 1.9.23 → 1.9.24`

## [1.9.15] - 2026-09-23

### 修复
- **设置页滑动卡顿**：状态读取全部下沉到各区块自己的 Composable。此前所有 `collectAsState` 都挂在页面顶层，在 WebDAV 输入框敲一个字、拖动阈值滑杆的每一帧，都会触发**整页**重组，7 张液态玻璃卡片的实时背景模糊采样链被整体重建
- 阈值滑杆的拖动草稿下沉到滑杆自身：拖动过程不再重组所在区块（含开关、档位按钮行）
- 长列表由 `Column + verticalScroll` 改为 `LazyColumn`：液态玻璃卡片要做实时背景采样模糊，原先视口外区块也一并参与组合与绘制，同屏数量越多滚动越吃 GPU
- 提醒自检不再被触发两遍：原先界面 `LaunchedEffect` 与 `updateReminder` 双路刷新，一次改动检测两轮；现统一为配置变更后去抖 120ms 刷新一次，且检测整体移到 IO 线程（含 DataStore 读取与权限/闹钟/电池优化跨进程查询）

### 变更
- 版本号：`versionCode 75 → 76`，`versionName 1.9.14 → 1.9.15`

## [1.9.14] - 2026-09-23

### 新增
- 设置页「提醒自检」区块：逐环节展示提醒链路状态——通知权限、通知渠道、精确闹钟授权、电池优化白名单、本地便笺快照、已排下次提醒时刻；每项异常时给一键跳系统设置（通知授权 / 渠道开关 / 「闹钟和提醒」 / 关闭电池优化）
- 设置页「发送测试通知」：直接验证「权限 → 渠道 → 发送」链路，区分"链路坏了"与"根本没到点"

### 修复
- 阈值改动后当天不再提醒：去重标记原按「资源」记账，只要当天提醒过一次，之后把阈值从 120 改成 200、树脂真满了也不会再响；现在阈值 / 开关一变即清除当日标记，按新条件重新排程
- 提醒静默失败：`notifyResin` / `notifyHomeCoin` 改为返回发送结果（权限缺失、渠道被关闭均不再无声吞掉），便于自检面板暴露真实原因

### 变更
- `ResinReminderStore` 增加 `nextTriggerAt` 持久化，用于展示已排提醒时刻；`ResinReminderManager.cancel()` 改为挂起函数并同步清空排程记录
- 版本号：`versionCode 74 → 75`，`versionName 1.9.13 → 1.9.14`

## [1.9.13] - 2026-09-22

> 1.9.8 ~ 1.9.12 为内部测试构建，未单独打 tag 与发布，改动一并并入本条。

### 修复
- 便笺倒计时：修复 `project()` 输出的 `nextPointInSeconds` / `fullInSeconds` 直接塞入毫秒值导致倒计时被放大 1000 倍（实测宝钱显示「回满还需 1167531:21:11」），现运算全程毫秒、出口再折回秒
- 洞天宝钱恢复速率：此前误用 3600 秒 / 个（游戏内不存在该档位），「回满还需」被高估数十倍；现按「洞天仙力」档位 4~30 个 / 小时推算
- 洞天宝钱倒计时口径：由「连续恢复」改为**每小时整批结算**，消除同一时刻与米游社倒计时相差半个多小时（实测 38 分钟）的问题
- 页面顶部重复避让：9/20 移除顶栏后，首页 / 设置 / 抽卡记录 / 统计各自又叠了一层状态栏高度，与 `GachaAppNavHost` 中 Scaffold 的 `innerPadding` 撞车，页面顶部凭空多空一条
- 统计页吸顶导航条与内容重叠：吸顶条由浮层改为参与布局的纵向排列，不再与列表顶部 padding 在 52~88dp 区间撞车

### 变更
- 洞天宝钱产量改为设置项：设置页新增产量档位按钮行（4 / 8 / 12 / 16 / 20 / 22 / 24 / 26 / 28 / 30 个 / 小时，默认满仙力 30），接口不返回洞天仙力等级，需按游戏内档位手动选择；改档位立即刷新便笺推算并重排闹钟
- 首页便笺卡片：移除阈值提醒快捷 chip 与就地配置弹窗，提醒规则统一收归「设置 → 树脂提醒」；底部「每日委托 / 周本减半」与「快照时间」合并为左右对齐的一行
- 版本号：`versionCode 68 → 74`，`versionName 1.9.7 → 1.9.13`

## [1.9.7] - 2026-09-22

> 1.9.5 / 1.9.6 为内部测试构建，未单独打 tag 与发布，改动一并并入本条。

### 新增
- 树脂 / 洞天宝钱阈值提醒：设置页新增「树脂提醒」区块（总开关、树脂阈值滑杆 1-200、洞天宝钱阈值、每日仅提醒一次），首页便笺卡片新增状态 chip 与就地修改阈值的弹窗，弹窗内可跳转设置页
  - 基于 `AlarmManager.setExactAndAllowWhileIdle` 的精确闹钟；Android 12+ 未授予 `SCHEDULE_EXACT_ALARM` 时降级为非精确闹钟
  - 新增 `ResinReminderReceiver`：闹钟到点拉起后重排下一次提醒；开机 / 应用更新（`SignInBootReceiver`）后重排；退出登录时取消已排闹钟
- WebDAV 抽卡记录备份：设置页新增「备份」区块，支持群晖 / Alist / 威联通等自建网盘配置，抽卡同步完成后自动备份
  - 每账号写入 `gacha_<uid>_<时间戳>.json`（归档）与 `gacha_<uid>_latest.json`（latest 覆盖），只增不删
- 每日便笺：首页便笺卡片展示树脂 / 洞天宝钱实时状态，并与米游社每日签到入口合卡

### 修复
- 便笺本地推算：修复 `project()` 中秒 / 毫秒单位混用导致外推速度被放大 1000 倍（打开首页很快显示满值）的问题，现全程毫秒运算，距下一点按余数回绕
- 满树脂上限由 160 修正为 200，洞天宝钱上限 2400
- 便笺 / 验证接口 `retcode 5003`（设备验证失败）：补齐 `device_fp`（`public-data-api.mihoyo.com/device-fp/api/getFp`）上报

### 变更
- 便笺数据改为本地推算：每天首次启动拉取一次快照存盘，其余时间按树脂 480s / 点、洞天宝钱 3600s / 个本地外推，显著降低接口请求频率
- 网络：`network_security_config` 的 base-config 放行明文流量，以兼容用户自建的局域网 WebDAV 端点（`http://192.168.x.x:端口`）；App 自身既有请求仍全部走 HTTPS
- 版本号：`versionCode 65 → 68`，`versionName 1.9.4 → 1.9.7`

## [1.9.4] - 2026-09-20

### 新增
- 设置页新增「关于」区块：版本号（versionName + versionCode）、项目仓库地址、隐私说明
- 日志导出诊断功能迁入「设置 → 关于」，测试期诊断入口收编（原首页右上角浮动按钮移除）
- 米游社每日签到入口迁至首页便笺卡片：签到开关 + 手动签到按钮与树脂/洞天宝钱实时状态合卡展示

### 修复
- 风控验证：verify/dailyNote 请求头改为 hybrid-v2 缝合形态——保留旧版 21 头设备形态、仅新增 `x-rpc-challenge_game: 2`，修复 official-form 纯浏览器形态在真机上被设备验证层识破（TLS 指纹为 OkHttp 而非浏览器）导致的 5003 拒绝
- 日志导出：修复「复制文件路径」实际复制日志全文的问题（旧实现误调 `copyToClipboard(label)`），现直接写剪贴板复制路径信息
- 修复 `AuthViewModel` 调试信息（含 stoken / cookie_token 片段）改为仅 debug 构建保留，release 一律剥离
- 修复 `GachaApiClient` 错误日志写盘前脱敏（authkey / cookie_token / ltoken / stoken 全部打码），并限制最多保留 10 份
- 修复 `CrashCatcher` 不再向公共「下载」目录写崩溃堆栈，改为仅写应用内部存储，并对 authkey / token 兜底脱敏
- 修复 `OkHttpClient` 关闭自动跟随重定向，避免携带 Cookie 的请求被跨域转发到第三方主机
- 修复 `allowBackup` 关闭；移除已无实际用途的 `WRITE_EXTERNAL_STORAGE` 权限；删除 `MainActivity` 上无处理逻辑的 `content/json` VIEW intent-filter

### 变更
- UI：移除四个主 tab（便笺/历史/统计/设置）顶部的标题栏，内容区自行避让系统状态栏，界面更沉浸
- UI：首页背景与其他页面统一——删除首页纯渐变覆盖层，透出全局背景光斑，深色模式保留星空画布（四页唯一差异只剩首页有星星）
- 构建：debug 构建回归 Android 默认 debug keystore（修复 debug 绑定 release 签名导致全新 clone 后 `assembleDebug` 失败）
- 构建：`proguard-rules.pro` 移除 3 条指向不存在包的无效 keep 规则；移除无引用依赖（coil / kotlinx-serialization / okhttp logging-interceptor）
- 构建：Room 开启 `exportSchema`（schema 输出至 `app/schemas/`）；`gradle-wrapper.properties` 补 `distributionSha256Sum`；版本目录统一收口
- CI：新增 `lintDebug` 与 `assembleRelease` 步骤，补齐 `permissions` / `concurrency`，action 全部按 commit SHA 固定

### 文档
- 新增 `.editorconfig`
- README 修正签名说明，标注 keystore 入仓等已失效描述
- `.gitignore` 去除冗余条目

## [1.8.3] - 2026-09-12

### 新增
- 液态玻璃（Liquid Glass）UI 全量落地：基于 `io.github.kyant0:backdrop` 的真实背景采样 + 透镜折射效果
- 每日自动签到：米游社签到改为 WorkManager 周期任务，支持开机/重启补偿
- 单元测试：新增 `DsSignerTest`（12 项），覆盖 DS1 / DS2 摘要自洽性、`b`/`q` 拼接规则、`randomDeviceId` UUID v4 格式等；单测总数 56 项
- 文档：新增 `CHANGELOG.md`、`SECURITY.md`、`CONTRIBUTING.md`

### 修复
- 修复 `MainActivity.onCreate` 未调用 `super.onCreate()` 导致的 `SuperNotCalledException` 崩溃
- 修复 `RectangleShape` 传入 lens 效果抛 `UnsupportedOperationException` 导致的统计页闪退
- 修复首页运气环流光越界扫空轨道的问题
- 退出登录未清除历史页 / 统计页遗留数据
- 未登录状态下仍弹出签到通知
- 分屏 / 小窗场景下的扫码登录引导文案（改为提示使用米游社 App 登录）

### 变更
- 登录方式精简：移除 WebView 登录，仅保留米游社扫码登录
- 构建链升级：AGP 8.13.2 / Kotlin 2.3.21 / KSP 2.3.12 / Compose BOM 2026.03.01 / Hilt 2.60.1 / Room 2.8.5，compileSdk & targetSdk 升至 36
- release 包开启 R8 minify（此前 1.7.x 为 `isMinifyEnabled = false`），APK 体积由约 12.9MB 降至约 2.3MB
- 构建：签名凭据（keystore 路径、口令、别名）外移至 `local.properties` / 环境变量，源码中不再硬编码口令
- CI：新增 `testDebugUnitTest` 单元测试步骤；一次性签名密钥改为随机口令生成

### 安全
- 新增 MIT LICENSE
- 新增 GitHub Actions CI（构建 + 单测）
- 清理入库的构建日志 `temp_build.log`，并在 `.gitignore` 中屏蔽构建日志

## [1.7.5] - 2026-09

### 修复
- 运气环流光限定在进度弧内滑动，不再越界
- 重构首页运气环动画（入场弧长生长 + 流光绕环）

## [1.7.4] 及更早

早期版本的变更记录请查阅 `git log`。
