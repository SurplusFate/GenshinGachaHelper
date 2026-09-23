# 更新日志

本项目自 1.8.3 起正式维护本文件。更早的历史请查阅 `git log`。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

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
