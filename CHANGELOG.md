# 更新日志

本项目自 1.8.3 起正式维护本文件。更早的历史请查阅 `git log`。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

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
