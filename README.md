# GenshinGachaHelper

原神抽卡助手 — Android 原生应用，Kotlin + Jetpack Compose 开发。

## 功能特性

- **米游社扫码登录**：通过通行证 Passport API 扫码授权，无需复制链接
- **验证码/密码登录**：WebView 内置米游社登录页，自动检测登录完成
- **抽卡记录同步**：一键同步角色池、武器池、常驻池、新手池抽卡记录
- **UIGF 导入/导出**：支持 UIGF v3.0 格式的历史数据导入导出，内容指纹去重，兼容多数据源合并
- **本地永久存储**：Room SQLite 数据库，所有数据保存在本地，无服务器依赖
- **接口配置可导入**：JSON 格式接口配置，支持自定义 API 地址和参数
- **统计分析**：抽卡统计、保底计算、五星抽数分析、角色池 301/400 共享保底
- **设备指纹防风控**：自动生成 device_fp，规避 -3503 风控
- **导出到 Download**：UIGF 数据直接导出到下载目录，文件管理器可见

## 技术栈

| 技术 | 用途 |
|------|------|
| Kotlin | 开发语言 |
| Jetpack Compose | UI 框架 |
| MVVM | 架构模式 |
| Hilt | 依赖注入 |
| Room | 本地数据库 |
| DataStore | 偏好存储 |
| OkHttp | 网络请求 |
| Coroutines | 异步处理 |

## 认证流程

```
登录方式
  ├── 扫码登录（Passport API）
  │     ├─ createQRLogin → 生成二维码（分屏扫码）
  │     ├─ queryQRLoginStatus → 轮询扫码状态
  │     │     └─ Confirmed → 返回 stoken
  │     └─ savePassportCredentialsAndFetchRoles → 换取 cookie_token / ltoken
  │
  └── 验证码/密码登录（WebView）
        ├─ user.mihoyo.com/#/login → 用户在 H5 页登录
        ├─ 自动检测 URL 离开 #/login → 提取 cookie
        ├─ 方案1：有 stoken_v2 → 直接走扫码同构链路
        ├─ 方案2：有 login_ticket → 换 stoken 后走扫码链路
        └─ 方案3：有 cookie_token_v2 + ltoken_v2 → 直接使用（验证码登录场景）

  通用后续流程
  ├─ getCookieAccountInfoBySToken → 换取 cookie_token
  ├─ getLTokenBySToken → 换取 ltoken
  └─ getUserGameRolesByCookie → 获取游戏角色
        └─ genAuthKey → 生成 authkey
              └─ getGachaLog → 拉取抽卡记录
```

## 项目结构

```
app/src/main/java/com/genshin/gachahelper/
├── auth/                    # 米游社认证
│   ├── MihoyoApiService.kt  # API 服务（扫码、Token交换、角色获取）
│   ├── AuthRepository.kt    # 凭证存储管理
│   ├── DsSigner.kt          # DS 签名算法
│   ├── DeviceFpService.kt   # 设备指纹生成
│   └── QrCodeGenerator.kt   # 二维码生成
├── sync/                     # 数据同步
│   ├── GachaSyncService.kt  # 抽卡记录同步服务
│   └── GachaDataImporter.kt # UIGF 导入/导出
├── remote/                   # 远程数据
│   ├── GachaApiClient.kt    # 抽卡 API 客户端
│   └── GachaResponseParser.kt # 响应解析器
├── data/                     # 本地数据
│   ├── local/               # Room 数据库
│   ├── model/               # 数据模型
│   └── repository/          # 数据仓库
├── config/                   # 接口配置
│   ├── importer/            # 配置导入
│   ├── parser/              # 配置解析
│   └── store/               # 配置存储
├── analysis/                 # 统计分析
├── ui/                       # Compose UI
│   ├── auth/                # 授权登录页
│   ├── home/               # 首页
│   ├── history/            # 历史记录
│   ├── stats/              # 统计
│   ├── report/             # 报告
│   └── settings/           # 设置
└── di/                       # 依赖注入模块
```

## 构建

```bash
# 环境要求
- JDK 17
- Android SDK 34
- Gradle 8.5

# 构建 APK
gradle assembleDebug
```

生成的 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

## 使用说明

1. 打开 App，使用米游社扫码登录
2. 登录成功后，在首页点击「同步抽卡记录」
3. 同步完成后可查看历史记录、统计数据
4. 在设置页可导入/导出 UIGF 格式数据

## 致谢

- [UIGF-org/mihoyo-api-collect](https://github.com/UIGF-org/mihoyo-api-collect) — 米游社 API 文档
- [Genshin-bots/gsuid_core](https://github.com/Genshin-bots/gsuid_core) — 扫码登录参考实现
- [BTMuli/TeyvatGuide](https://github.com/BTMuli/TeyvatGuide) — Token 管理参考

## 迭代记录

记录本仓库的代码审查与修复轮次，便于回溯演进过程。

### 2026-08-25 · v1.4.4 修复v1.4.3引入的多个回归问题（tag `v1.4.4`）

**版本信息**

- `versionCode = 13`，`versionName = "1.4.4"`
- 修复 v1.4.3 中将共享保底池所有统计指标改为合并记录后引入的多个回归问题

**Bug 1：新手池20抽变成19抽**

- **根因**：`currentPity` 钳制到 `pityCeiling - 1 = 19`，但新手池的 `pityCeiling=20` 是「池总抽数」不是五星保底阈值，20/20 表示池已关闭，不应减1
- **修复**：新手池 `currentPity` 钳制到 `pityCeiling`（20），其他池仍钳制到 `pityCeiling - 1`

**Bug 2：最非变成95抽（超过保底上限90）**

- **根因**：数据不完整时（缺少五星记录），`calculateFiveStarIntervals` 计算的间隔可能超过保底上限（如第一条间隔=数据起始到第一个五星的位置），产生物理上不可能的值
- **修复**：`calculateFiveStarIntervals` 新增 `pityCeiling` 参数，过滤超过保底上限的间隔；`HistoryViewModel` 和 `HomeViewModel` 的间隔计算同步加入过滤

**Bug 3：首页保底进度所有池子进度条都满**

- **根因**：v1.4.3 将 `totalPulls`/`fiveStarCount` 也改为合并记录，导致角色池显示的抽数/五星数与历史页不一致；同时数据不完整时 `currentPity` 被钳制到上限附近，进度条显示95%+
- **修复**：`totalPulls`/`fiveStarCount`/`fourStarCount`/`threeStarCount` 回退为单池记录（与历史页记录数一致），`currentPity`/`fiveStarIntervals`/`avgPullsPerFiveStar`/`lastFiveStar` 保持用合并记录（保底相关指标）

**Bug 4：角色池统计与历史页不一致**

- **根因**：v1.4.3 合并后角色池-2 显示 totalPulls=2651 fiveStarCount=48（合并值），但历史页只有535条记录、5个五星（单池值），用户质疑数据不对
- **修复**：回退为单池显示，角色池-2 显示535抽5金（与历史页一致），保底进度/间隔/平均出金仍用合并值（共享保底正确）

**影响文件**：
- `GachaStatsCalculator.kt`：`calculatePoolStats` + `calculateFiveStarIntervals` + `generateReport`
- `HomeScreen.kt`：`HeroLuckCard` + `LuckDetailCard`
- `HomeViewModel.kt`：`computeIntervals`
- `HistoryViewModel.kt`：`computeFiveStarIntervals` + `computeStats`

### 2026-08-25 · v1.4.3 修复共享保底池统计一致性（tag `v1.4.3`，已被v1.4.4取代）

**版本信息**

- `versionCode = 12`，`versionName = "1.4.3"`
- 修复 v1.4.2 中共享保底池（角色池301+400）统计数据不一致的问题

**Bug：共享保底池统计数据不一致（角色池2 显示 265抽1金）**

- **根因**：`calculatePoolStats` 中，`currentPity` 和 `fiveStarIntervals` 已改为从合并记录计算（v1.4.2修复），但 `totalPulls`、`fiveStarCount`、`fourStarCount`、`threeStarCount`、`avgPullsPerFiveStar`、`lastFiveStar` 仍用单池记录，导致：
  - 角色池2显示 535抽5金（单池），但平均出金 55.2抽（合并2651/48），数据自相矛盾
  - 用户质疑"角色池265抽只有1金"——单池统计与共享保底机制不符
- **修复**：统一用 `intervalRecords`（合并后的记录）计算**所有**统计指标（totalPulls、fiveStarCount、fourStarCount、threeStarCount、currentPity、lastFiveStar、fiveStarIntervals、avgPullsPerFiveStar、upFiveStarCount、upRate），确保共享保底池的所有统计一致
- **附带修复**：
  - `generateReport` 中 UP 率计算：改为取任意一个非空角色池的 `upRate`（两池已合并，叠加会重复计数）
  - `HomeScreen` 的 `HeroLuckCard` 和 `LuckDetailCard`：汇总各池总和时只取一个角色池（两池统计已合并为同一份数据，叠加会导致总抽数翻倍）
- **影响文件**：
  - `GachaStatsCalculator.kt`：`calculatePoolStats` 方法 + `generateReport` 方法
  - `HomeScreen.kt`：`HeroLuckCard` + `LuckDetailCard`

### 2026-08-25 · v1.4.2 修复首页最非/最欧数据错误（tag `v1.4.2`）

**版本信息**

- `versionCode = 11`，`versionName = "1.4.2"`
- 修复 v1.4.1 中首页"运气拆解"卡片最非/最欧显示异常值的问题

**Bug：首页"最非147抽 最欧0抽"异常值**

- **根因 1（最非147抽，超过保底上限90）**：`GachaStatsCalculator.calculatePoolStats` 计算五星间隔时只用单池记录，未合并共享保底的 301+400 池。角色池 301 单独算间隔时，400 池里的五星不算"重置保底"，导致间隔可超过 90
  - **修复**：当 `sharedPityRecords` 非空时，间隔计算也用合并后的 301+400 记录
- **根因 2（最欧0抽，不可能值）**：没有五星记录的池，`intervals.minOrNull() ?: 0` 返回 0；`LuckDetailCard` 的 `minOfOrNull` 跨池取最小值时取到了这个 0
  - **修复**：`LuckDetailCard` 过滤掉 `fiveStarCount == 0` 的池再取最非/最欧；所有值为 0 时显示 "—" 而非 "0 抽"
- **影响文件**：
  - `GachaStatsCalculator.kt`：`calculatePoolStats` 的间隔计算部分
  - `HomeScreen.kt`：`LuckDetailCard` 的最非/最欧计算和显示

### 2026-08-25 · v1.4.1 修复排序与筛选数据错误（tag `v1.4.1`）

**版本信息**

- `versionCode = 10`，`versionName = "1.4.1"`
- 修复 v1.4.0 中首页数据错误和历史页筛选结果错误的问题

**Bug 1：首页数据不对 — orderNumber 跨池排序错误**

- **根因**：`orderNumber` 是按池独立编号的（角色池 301 和 400 各自从 1 开始），不是全局唯一序号。当合并 301+400 计算保底/间隔时，按 `orderNumber` 排序导致两个池的记录错误交错，间隔和垫抽计算全部错误
- **修复**：所有排序统一改为按 `time`（时间戳）排序，因为 `time` 是全局唯一的时间顺序指标
- **影响文件**：
  - `GachaStatsCalculator.kt`：`calculatePoolStats` / `calculateCurrentPity` / `calculateFiveStarIntervals` 三个方法
  - `HomeViewModel.kt`：`computeIntervals` 方法
  - `StatsViewModel.kt`：`addPoolTimeline` 方法
  - `GachaRecordDao.kt`：全部 11 条查询的 `ORDER BY` 从 `CAST(orderNumber AS INTEGER) DESC` 改为 `time DESC`

**Bug 2：历史页筛选结果不对 — 间隔/垫抽使用了被筛选后的记录**

- **根因**：`HistoryViewModel.computeStats` 中五星间隔和垫抽计算使用了被稀有度筛选后的记录。例如筛选"五星"后，列表里只剩五星记录，间隔全部变成 1，垫抽始终为 0
- **修复**：
  - 五星间隔：改用未过滤的原始分池记录计算，只按 `poolType` 限定参与计算的池
  - 垫抽：同样改用未过滤的原始记录，保证 `calculateCurrentPity` 能看到完整池历史
  - 摘要栏的 `totalPulls` / `fiveStarCount` / `fourStarCount` 仍使用筛选后的记录（符合用户预期：筛选五星时只看到五星数量）
- **影响文件**：`HistoryViewModel.kt` 的 `computeStats` 方法

**附带修复**

- 移除 `HistoryViewModel` 中 `StateFlow` 上的 `distinctUntilChanged()` 调用（`StateFlow` 本身只发送 distinct 值，加上会导致编译错误）

### 2026-08-25 · v1.4.0 三页面UI重构 + 代码清理与修复（commit `ac0a015`..`f46ad0d`，tag `v1.4.0`）

**版本信息**

- `versionCode = 9`，`versionName = "1.4.0"`
- 基于 v1.3.2 修复版，对首页/历史/统计三个核心页面进行 UI 重构，同时做代码清理与 bug 修复

**UI 重构（commit `f46ad0d`）**

1. **首页 (HomeScreen)** — ABC 三方案融合设计：
   - Hero 运气环卡片：左侧大数字总抽数 + 三栏统计（五星数/平均出金/UP率），右侧 Canvas 绘制运气评分环（0-100 分，基于平均出金抽数计算）
   - 最近出金横滑：LazyRow 展示最近五星记录，金色边框卡片
   - 保底进度网格：2 列布局，胶囊形进度条，颜色随垫抽数变化（金色→橙色→红色）
   - 运气拆解：平均出金/最近五星/最非/最欧/UP成功率/总抽数六行数据

2. **统计页 (StatsScreen)** — 从 Tab 布局改为上下滑动单页：
   - 四个区块连续排列：概览 → 时间轴 → 图鉴 → 日历
   - 因当前 Compose 版本（BOM 2024.02.00）不含 `stickyHeader` API，改用**浮层吸顶导航栏**方案：`Box` 叠加 `LazyColumn` + 始终可见的 `Surface` 导航条
   - 点击导航跳转 + 滚动自动高亮当前区块（`derivedStateOf` 追踪 `firstVisibleItemIndex`）
   - 导航栏增加 `shadowElevation` 投影，`NavTab` 补上 `clickable` 修复点击无效

3. **历史页 (HistoryScreen)** — 统一视觉风格：
   - 搜索栏：`surfaceVariant` 半透明背景 + `outlineVariant` 边框
   - 筛选 Chip：卡池行 + 星级行双行布局，星级选中态用对应颜色浅色背景
   - 摘要栏：统一 `RoundedCornerShape(12)` + `surfaceVariant` 卡片，四格间加分隔线
   - 日期头：带圆点指示器，有五星的日子用金色背景高亮
   - 记录项：星级徽章加背景色和边框；五星名称用金色；间隔标签用浅色胶囊样式

**代码清理与修复（commit `ac0a015`）**

1. **五星间隔排序统一**（C1）：Home/History/Stats 三个 ViewModel 中五星间隔计算全部改为按 `orderNumber` 排序，与 `GachaStatsCalculator` 保持一致，消除 `time` 字符串排序导致的秒级相同记录顺序不稳定问题

2. **移除验证码登录入口**（W5）：删除 AuthScreen 的验证码登录 TabRow，仅保留米游社扫码登录入口，文字提示统一改为"米游社 App 扫一扫"

3. **统一稀有度解析**（W3）：抽取 `parseRarity` / `parseItemType` 为 `GachaEnums.kt` 顶层纯函数，API 响应解析和 UIGF 导入两处 `rank_type` / `item_type` 统一调用，消除 `contains("5")` 导致的 "15" / "S5" 误判

4. **路由常量化**（N6）：新增 `Screen.Report` 路由常量，`GachaAppNavHost` / `StatsScreen` 中 "report" 硬编码改为 `Screen.Report.route`

5. **删除死代码**（W1）：移除 `GachaRecordDao.getLatestOrderNumber` 及 Repository 包装（字典序排序错误 + 零调用）

6. **预留接口**：`MihoyoApiService` 新增 `getStokenByCookieToken` 接口实现，为将来修复验证码登录保留后端入口

### 2026-08-25 · v1.3.2 修复五星间隔排序错误和垫抽计算逻辑（commit `0e41b0b`，tag `v1.3.2`）

**版本信息**

- `versionCode = 8`，`versionName = "1.3.2"`
- 对 v1.3.1 重构引入的三个页面进行代码审查后修复 5 个问题

**修复内容**

1. **五星间隔排序错误**（严重）：`HistoryViewModel`、`StatsViewModel`、`GachaStatsCalculator` 中五星间隔计算使用 `orderNumber` 排序，但 `orderNumber`（API 的 `id` 字段）是每个池内独立的序列，跨池排序不正确。改为按 `time` 字段排序，确保时间顺序正确。

2. **垫抽计算语义错误**（严重）：`HistoryViewModel.refreshStats()` 把所有池的记录合并后调用 `calculateCurrentPity(allRecords)`，跨池合并算出的"总垫抽"无实际意义。改为取各池中垫抽的最大值——用户最关心的是最接近保底的池。

3. **五星间隔算法不一致**（重要）：三个页面用三种不同算法算同一个"五星间隔"概念——HomeViewModel 每池单独算、StatsViewModel 角色池 301+400 合并算、HistoryViewModel 全部合并算。统一为：角色池 301+400 合并算（与 `GachaStatsCalculator.generateReport` 一致），其他池单独算。

4. **StatsScreen 非空断言**（次要）：`uiState.report!!` 改为 `uiState.report?.let { ... }` 安全调用，避免潜在的 NPE。

5. **stickyHeader 兼容性**（commit `c146e63`）：`stickyHeader` 改为 `item`，兼容当前 Compose 版本。

### 2026-08-25 · v1.3.1 重构首页/历史/统计三个页面

**改动**：对首页、历史记录、统计三个核心页面进行重构优化，提升代码可维护性和用户体验。

**背景**：v1.3.0 新增了历史记录按日期分组、沉浸式状态栏、手动导入数据显示分析等功能后，三个核心页面的代码结构需要同步重构，以更好地支撑后续功能迭代。

### 2026-08-25 · v1.3.0 正式版：历史记录按日期分组展示 + keystore 入仓统一签名（commit `45a62c6`，tag `v1.3.0`）

**版本信息**

- `versionCode = 6`，`versionName = "1.3.0"`
- `release.keystore` 纳入版本控制（`git add -f`，不受 `.gitignore` 的 `*.keystore` 规则影响），任何机器 clone 后 `./gradlew assembleRelease` 即可用同一证书签名
- `app/build.gradle.kts` 的 `storeFile` 从绝对路径改为相对路径 `file("$rootDir/release.keystore")`
- 证书指纹（本次起新证书，后续版本复用）：
  - SHA-1: `0F:86:65:32:AC:6D:87:86:0B:0E:DE:F3:35:43:37:70:11:14:5C:71`
  - SHA-256: `FE:1A:1D:95:B1:C3:9B:77:3D:A2:A4:8C:41:6A:3B:90:61:DF:97:B4:1E:3F:F3:3E:D5:F5:9E:46:69:09:4F:DB`

**历史记录页改为按日期分组列表**

原先历史记录页是扁平列表（一条 Card 一条记录），长列表翻几屏后不知道在哪个日期。
改为按日期分组：LazyColumn 渲染时，当前记录的日期（`time.take(10)`，即 `yyyy-MM-dd`）与前一条不同时，在该条上方插入一个分组 Header。

Header 用 `java.time.LocalDate` 做人性化显示：
- 今天 → `今天`
- 昨天 → `昨天`
- 今年其他日期 → `MM-dd EEE`（如 `08-24 周一`）
- 跨年 → `yyyy-MM-dd`
- 解析失败兜底原字符串

纯 UI 层改动，**数据层 / Paging / ViewModel 全部不动**（保持性能与现有刷新逻辑）。
Paging 是流式加载（每页 20 条），header 渲染时当天可能只加载了部分，所以 header 只显示日期不显示当天抽数（避免跳变误导）。

### 2026-08-25 · v1.2.1 验证码登录修复（commit `758b0fa`，tag `v1.2.1`）

**问题**：验证码登录后提示「检测到账号但没有可用于换取凭证的 login_ticket」，无法完成登录。

**根因**：米哈游 H5 登录页（user.mihoyo.com）在验证码登录后，cookie 中不再下发 `stoken` 或 `login_ticket`，仅下发 `cookie_token_v2` 和 `ltoken_v2`。原有代码只认 `stoken`/`login_ticket` 两种凭证，导致登录失败。

**修复**：
- 新增「方案 3」登录路径：WebView cookie 中有 `cookie_token_v2` + `ltoken_v2` + `ltuid` 时，直接使用这些凭证登录，无需经过 stoken 兑换
- `AuthRepository.buildCookieString()` 不再强制要求 stoken，只要有 `cookie_token` 或 `ltoken` 配合 `ltuid` 即可构建有效 cookie
- 新增 `saveWebViewCredentials()` 方法支持无 stoken 的凭证保存
- `isLoggedIn()` 判断逻辑同步更新，支持多种凭证组合判定登录状态

**原理**：`stoken` 只是换取 `cookie_token`/`ltoken` 的中间凭证，最终调用 `getUserGameRolesByCookie`（需 cookie_token）和 `genAuthKey`（需 ltoken）并不需要 stoken。验证码登录直接拿到最终 token，跳过兑换环节。

### 2026-08-24 · v1.2.0 正式版：删除失效接口配置 + 白天/夜间主题 + 统一签名发行（commit `9218540`，tag `v1.2.0`）

**版本信息**

- `versionCode = 4`，`versionName = "1.2.0"`
- `app/build.gradle.kts` 中 `debug` / `release` 两个 buildType 统一使用同一个 release
  签名密钥（`/data/user/work/release.keystore`，`gacha-release`），避免 debug / release
  包签名不一致导致无法覆盖安装。

**本轮改动包含**

1. **删除未生效的接口配置功能**（commit `a0e1ea9`）：确认配置对 Mihoyo 官方抽卡 API
   请求无任何可观察效果，整个 `config/` 模块、`default_api_config.json`、
   `GachaApiClient`/`GachaResponseParser` 的 ApiConfig 形参、以及 SettingsScreen
   「接口配置」Section 全部移除。
2. **修复 `parseRarity` 5 星误判 bug**（commit `a0e1ea9`）：`value.contains("5")`
   会把 `"15"` / `"S5"` 判为 5 星 → 改为 S/A/B 优先 → 纯数字精确匹配（coerceIn 3..5）
   → contains 兜底。
3. **新增白天/夜间模式主题**（commit `a0e1ea9`）：
   - `ThemeMode` 枚举（随系统 / 白天 / 夜间）
   - `ThemeRepository` 用独立的 `settings_store` DataStore 持久化
   - `GenshinGachaHelperTheme(themeMode, systemDark)` 应用到全局 MaterialTheme
   - `MainActivity` 通过 `collectAsStateWithLifecycle` 订阅 Flow，
     用户在「设置 → 主题设置」切换后实时全局生效，无需退出重进
   - SettingsScreen 原「接口配置」位置替换为三选一 RadioGroup
4. **签名统一**（本 commit）：debug / release 均使用同一 release keystore，
   保证发布 APK 与日常构建 APK 可互相覆盖安装。

### 2026-08-24 · 删除接口配置功能 + 新增白天/夜间主题（commit `a0e1ea9`）

**删除接口配置功能**

原先代码存在一套"可自定义 API 配置"的链路：`default_api_config.json`
→ `ConfigStore` / `ConfigParser` / `ConfigImporter` / `ApiConfig` model →
SettingsScreen 的「导入配置 / 恢复默认」按钮。而实际同步链路中 Mihoyo 官方抽卡
API（URL/参数/响应 mapping）是固定的，`GachaApiClient`、`GachaResponseParser`
虽然把 config 当作形参，但配置里的字段（`url = getGachaLog`、`pageSize=20`、
`listPath = data.list`、`item_name = name` 等）是硬编码值，用户导入自定义 JSON
也不会影响对米游社官方接口的请求，所以这套功能**没有任何可观察效果**。

本次改动：
- 删除 `app/src/main/assets/default_api_config.json`
- 删除整个 `config/` 模块（store / importer / parser / model 共 5 个文件）
- `GachaApiClient.fetchGachaPage(...)` 不再接收 `ApiConfig` 形参，URL/参数直接
  按米游社官方 API 写死：`BASE_URL = public-operation-hk4e.mihoyo.com/.../getGachaLog`，
  `PAGE_SIZE = 20`，GET，query 参数按官方命名（`authkey`/`region`/`gacha_type`/
  `page`/`size`/`end_id` + 固定项 `authkey_ver=1 sign_type=2 auth_appid=webview_gacha`
  `lang=zh-cn device_type=mobile plat_type=android`），`authkey` 做 URL 编码
- `GachaResponseParser.parseResponse(...)` 不再接收 config 形参，mapping 直接写死：
  `data.list` 路径下取 `name / item_type / rank_type / time / id`；
  `parseRarity` 同步修复旧 bug（`value.contains("5")` 会误判 `"15"`/`"S5"` 为 5 星）：
  改为 S/A/B 优先 → 纯数字精确匹配（并 coerceIn 3..5）→ 最后走 contains 兜底
- `GachaSyncService` 去掉 `ConfigStore` 注入，`pageSize` 改 `GachaApiClient.PAGE_SIZE`
- `SettingsViewModel` 去掉 `configStore` / `configImporter` 注入，删除
  `importConfig` / `resetConfig` 方法，`SettingsUiState` 去掉 `configVersion` 与
  `configUrl` 字段
- `SettingsScreen` 删除「接口配置」Section、配置文件 Picker 与"恢复默认"对话框

审查清单"重要"组中 `parseRarity contains("5")` 误判 bug 也一并修复 ✓。

**新增白天/夜间模式主题适配**

新增"跟随系统 / 白天 / 夜间"三选一主题，用户选择持久化并**实时生效**（无需退出重进）：

- `ThemeMode` 枚举（`FOLLOW_SYSTEM(0) / LIGHT(1) / DARK(2)`）
- `ThemeRepository`：通过独立的 `Context.settingsDataStore`（`settings_store`）
  读写用户偏好，暴露 `themeModeFlow` 与 `suspend setThemeMode(...)`
- `GenshinGachaHelperTheme(themeMode, systemDark)`：`FOLLOW_SYSTEM` 时用
  Compose 提供的 `isSystemInDarkTheme()`，`LIGHT/DARK` 则强制选对应的 ColorScheme
- `MainActivity`：注入 `ThemeRepository`，通过 `collectAsStateWithLifecycle`
  把 Flow 交给 `GenshinGachaHelperTheme`，用户点击切换主题即通过 Recomposition
  自动全局应用
- `SettingsScreen`：在原来「接口配置」的位置替换为「主题设置」三选一 RadioGroup，
  `viewModel.themeMode` 读取当前值，`viewModel.setThemeMode(...)` 写回 DataStore

未做：AppCompat delegate night mode（非 Compose 场景），但目前 App 所有页面均
Jetpack Compose，MaterialTheme 的 colorScheme 已能覆盖全部 UI。

### 2026-08-24 · v1.1.1 导出文件命名规范化（commit `4d4d8cb`）

**问题**：导出文件命名为 `UIGF_<uid>_<毫秒时间戳>.json`，时间戳不直观，无法一眼看出导出时间。

**修复**：改为 `UIGF_v3.0_<uid>_<yyyyMMdd_HHmmss>.json` 格式，例如 `UIGF_v3.0_100681784_20260824_230833.json`，文件名包含 UIGF 版本号、用户 UID、可读的日期时间。

### 2026-08-24 · v1.1.0 正式发布（commit `9754c5e`..`6959f94`）

**本轮集中修复了数据统计、登录认证、数据导入、UI 体验四个方面的问题，并发布首个正式签名 Release 版本。**

#### 1. 角色池 301/400 共享保底修复（commit `c30dd95`）

**现象**：五星间隔统计出现 184 抽，远超 90 抽保底上限。

**根因**：角色活动祈愿（301）和角色活动祈愿-2（400）在游戏内共享保底计数，但代码将两者视为独立池分别计算间隔，导致一池出金记录被遗漏，间隔虚高。

**修复**：`GachaStatsCalculator` 合并 301+400 记录后计算五星间隔和当前垫抽；`HomeViewModel`、`ReportViewModel`、`StatsViewModel` 均传递 `sharedPityRecords` 参数实现共享保底计算。

#### 2. 验证码登录 retcode -100 修复（commit `6959f94`）

**现象**：WebView 验证码/密码登录后，`generateAuthKey` 报 retcode -100「登录状态失效」。

**根因**：`CookieManager.flush()` 后仅等待 500ms，部分 cookie 未落盘；且只从单一域名读取 cookie，遗漏了 `stoken_v2` 等关键凭证。

**修复**：
- flush 延时从 500ms 增至 1500ms
- 从 4 个域名合并读取 cookie（`user.mihoyo.com`、`.mihoyo.com`、`mihoyo.com`、`api-takumi.mihoyo.com`）
- WebView 自动检测 URL 离开 `#/login` 页面时触发登录完成，无需手动点按钮

#### 3. 扫码登录分屏提示（commit `6959f94`）

**问题**：原提示让用户「打开原神扫码」，但截图扫码无法识别，只能分屏操作。

**修复**：二维码页面提示改为分屏操作指引，加注「截图扫码无法识别」。

#### 4. UIGF 导入内容指纹去重（commit `94f088f`）

**问题**：不同来源（API 同步、提瓦特小组小程序导出、早期本地备份）的记录 `orderNumber`(ID) 格式不同，仅按 ID 去重会导致重复记录。

**修复**：插入前查询已有记录，构建 `(卡池, 时间, 物品名)` 多重集（multiset），内容匹配的记录跳过。支持同一时间抽出相同物品的正确处理（多重集计数递减）。

#### 5. 移除千星奇域池（commit `6959f94`）

移除 `STELLAR(600)` 相关的全部代码（枚举、保底、统计、UI、同步、导入导出），共 11 个文件，搜索 `STELLAR`/`stellar`/`千星` 零残留。

#### 6. 导出路径改到 Download 目录（commit `9754c5e`）

**问题**：导出文件保存在 `Android/data/com.genshin.gachahelper/files/`，用户难找。

**修复**：改用 MediaStore API 写入 Download 目录，兼容 Android 10+ Scoped Storage，文件管理器「下载」中直接可见。

#### 7. 正式签名 Release 构建

生成 RSA 2048 位、有效期 100 年的正式签名 keystore，配置到 `build.gradle.kts` 的 `signingConfigs.release`，版本号升至 `1.1.0 (versionCode=2)`。

### 2026-08-24 · 修复验证码/密码登录：与扫码凭证等价（commit `93a5875`）

**现象**：验证码 / 密码登录（WEBVIEW 路径）时而能登录但后续 `getGameRoles` / `generateAuthKey`
报 -100/401，表现为一种登录方式可用另一种不可用，或换账号后必须重启 App。

**根因**：`AuthViewModel.onWebViewLoginComplete` 有 4 个 fallback 方案：
- 方案 1（stoken 直存）/ 方案 4（login_ticket → multiToken 换 stoken）是正确的；
- **方案 2/3 会把 `ltoken_v2` 或 `cookie_token_v2` 冒充 `stoken` 存进 DataStore**，导致
  后续所有需要 `stoken` 的 API（`getCookieTokenByStoken`、`getLTokenByStoken`、
  `buildCookieString` 中 `stoken` 字段、`generateAuthKey` DS 签名链路）都带错值。
  看起来"登录成功"，但后续全链接口随机出错，和扫码路径不等价。

**修复**：
1. `onWebViewLoginComplete` 删掉方案 2/3，只剩两种合法路径：
   - 有 `stoken_v2` + `ltuid` → 调用 `savePassportCredentialsAndFetchRoles(stoken, mid, aid)`，
     与扫码 `Confirmed` 分支完全同构（存 stoken → 换 cookie_token → 换 ltoken →
     fetchGameRoles → selectRole → generateAuthKey）。
   - 否则只要有 `login_ticket` + `ltuid` → 走 `getMultiTokenByLoginTicket` 换真正 stoken，
     再沿用扫码同构的 cookie_token/ltoken 补换流程。
   - 两条都不满足：直接报错并引导改用扫码（不再冒充 stoken），错误文案给出 ltuid，方便定位。
2. `exchangeTokenByLoginTicket` 拿到 stoken 后，补足和扫码一致的
   `getCookieTokenByStoken` + `getLTokenByStoken` 兑换步骤（WebView cookie 中已带的就跳过，避免重复请求），最终 `saveLoginCredentials(...)`
   存入完整 5 件套，与扫码产出完全一致，后续接口不再分化。
3. 全量审查清单"重要"组第 12 条（AuthViewModel 方案 2/3 把 ltoken/cookie_token 当 stoken）
   标记 ✓。

**约束**：验证码与密码登录本身 APP 不参与短信/密码校验，都走同一个 WebView 容器
（`user.mihoyo.com/#/login`），用户在米游社 H5 页选方式。修复后两者的最终凭证集与
扫码登录完全同构，能取得 stoken 就全部一致通过；取不到 login_ticket（米游社近年 H5
登录页有调整）就明确报错并引导扫码，不再假成功。

### 2026-08-24 · 修复登录后页面刷新不及时（commit `49b3cef`）

**现象**：APP 登录后部分页面（尤其 Settings）刷新不及时，需退出 App 重进才能看到新登录态/UID/昵称。

**根因**：`SettingsViewModel` 已注入 `SessionEventBus`，但 `init` 只调用 `loadSettings()`，**从未启动事件 collector**。底部导航的 ViewModel 在 saveState 模式下被长期保留，一旦用户先访问过 Settings，后续登录/退出后切回 Settings，SettingsViewModel 仍是同一个实例且没收到任何事件，UI 停留在旧的"未登录/旧 UID"状态，必须重启 App 触发 ViewModel 重建与 `init { loadSettings() }` 才刷新。

**修复**：
1. `SettingsViewModel.init` 新增 `sessionEventBus.events` collector，收到
   `LoginCompleted`/`LogoutCompleted`/`DataCleared`/`DataImported` 时 `loadSettings()`
   刷新登录态与配置显示；`DataSynced` 走 `else` 不响应（同步不改配置）。
2. `ReportViewModel` 注入 `SessionEventBus` 并订阅全部 5 类事件 → `loadReport()`，
   与 Home/Stats/History 行为一致；并加 `Mutex` 串行化 `loadReport`，避免事件并发
   触发时多次重叠写 `_uiState`。原 ReportViewModel 完全没订阅事件，靠每次进入页面
   重建 ViewModel 才刷新，停在 Report 页期间同步/导入数据不会更新。
3. `SettingsViewModel.logout` 补发 `DataCleared`：logout 同时删除了账号与全部抽卡数据，
   只发 `LogoutCompleted` 会让仅监听 `DataCleared` 的逻辑无法被触发。现两个事件都发，
   Home/Stats/History 对两个事件的处理都是幂等重置，无副作用。

**对照**：本轮把"不及时"的根因（Settings 漏订阅事件）补齐，并把 Report 也对齐到事件驱动。
审查清单中"重要"组关于 `GachaSyncService.syncAll` 并发非原子的待办仍未处理。

### 2026-08-24 · 性能优化（commit `b5b07be`）

针对全量审查中标注为"重要/一般"且可低风险落地的性能项做集中优化。

**优化内容**：
1. **共享 OkHttpClient**：新建 `di/NetworkModule` 通过 Hilt 提供单一 `@Singleton OkHttpClient`，
   `MihoyoApiService` / `DeviceFpService` / `GachaApiClient` 三处各自 `OkHttpClient.Builder()` 改为构造注入。
   复用连接池与 dispatcher 线程池，减少内存与连接开销。
2. **`AuthRepository.buildCookieString` 批量读 DataStore**：原先调用 5 个 getter，每个 `data.first()`
   串行读一次 Preferences，现在改为一次性 `data.first()` 取出 prefs 后复用。每个带 Cookie 的
   API 请求路径都受益。
3. **`GachaSyncService` 用 `delay` 替换 `Thread.sleep`**：原先在 `Dispatchers.IO` 协程里
   `Thread.sleep(300)` 阻塞 IO 线程，分页同步每页都阻塞；改为挂起式 `delay(300)` 释放线程。
4. **`HomeViewModel.loadData` / `StatsViewModel.loadStats` 加 Mutex 串行化**：事件总线可能在
   `init { loadData() }` 还没跑完时就投递 `LoginCompleted`/`DataSynced` 等事件，导致多个加载
   协程重叠写 `_uiState`，last-write-wins 可能回退到旧数据。加 `Mutex.withLock` 后强制串行。

**审查清单对照**：本轮修复了"一般"组的"两个 OkHttpClient 不共享连接池"与
"AuthRepository buildCookieString 连读 5 次"，以及"重要"组的"Thread.sleep 阻塞 IO 线程"和
"HomeViewModel/StatsViewModel 并发竞态"。其余项（明文 token、destructive migration、
minify 关闭、salt 裸露、JSON 拼接未转义等）仍待办。

### 2026-08-24 · 全局刷新机制接线修复（commit `f7442cb`）

**问题**：上一提交 `d1f266c` 引入 `SessionEventBus`，但 `DataSynced` 事件从未被 emit，三个 ViewModel 中的对应 `when` 分支是死代码；实际刷新依赖各 ViewModel 直接 `collect { syncService.syncState }` 的旁路，与提交描述不符。

**修复**：
1. `GachaSyncService` 注入 `SessionEventBus`，同步成功时 `emit(DataSynced)`
2. `SessionEventBus.emit` 改为 `suspend`，避免 `extraBufferCapacity=4` 溢出时静默丢弃 `LogoutCompleted`/`DataCleared` 等关键事件
3. `HomeViewModel` 移除 `SyncState.Success` 旁路 `loadData()`，`syncState.collect` 仅保留用于 UI 同步进度展示
4. `StatsViewModel` 移除 `syncService` 注入与 `syncState.collect`，同步刷新完全由事件总线驱动
5. 删除从未被 emit 的 `SessionEvent.Refresh` 及所有引用它的分支

### 2026-08-24 · 全量代码审查（未提交，仅记录待办）

针对全代码库做了一次完整审查，记录需要后续处理的问题，按严重程度分组：

**严重（安全 / 数据丢失）**
- `AndroidManifest.xml` 设 `allowBackup="true"`，且 `AuthRepository` 把 `stoken`/`cookie_token`/`ltoken` 明文存进 DataStore Preferences —— root 设备或 `adb backup` 可读出全部米游社凭证。应改 `allowBackup="false"` 并迁移到 EncryptedDataStore / Tink。
- `DatabaseModule` 使用 `fallbackToDestructiveMigration()`，未来 schema 变更会静默清空全部抽卡历史；且 `exportSchema=false` 无法审计演进。应提前定义 `Migration` 路径并设 `exportSchema=true`。
- release 构建 `isMinifyEnabled=false`，`DsSigner` 中 `LK2`/`X4`/`X6`/`K2`/`PROD` 等 salt 直接硬编码在源码，反编译即可见。release 应开启 R8 shrink/obfuscation，并考虑把 salt 移到 native。
- `MainActivity` 的 `content:// + application/json` VIEW intent-filter 完全开放，任意应用可塞 JSON 触发 UIGF 导入，存在伪造数据风险。建议移除或仅走 `ACTION_OPEN_DOCUMENT`。

**重要（正确性 / 稳健性）**
- `AuthViewModel` 把 token 片段、原始响应、stack trace 直接塞进 `debugInfo`，截图或崩溃采集会泄露凭证。生产构建应关闭或脱敏。
- `GachaApiClient.logError` 把含 `authkey`/`uid` 的响应体写入 `filesDir/errors/`，无大小上限、无清理机制。
- ~~`GachaSyncService` 用 `Thread.sleep(300)` 而非 `delay`，阻塞 IO 线程。~~ ✓ 已在性能优化轮修复
- `GachaApiClient.buildPostBody` 与 `MihoyoApiService` 多处字符串模板拼 JSON / URL 未转义，存在 JSON 破坏与字段注入风险。应统一用 `JsonObject` + `HttpUrl.Builder`。
- `MihoyoApiService` 的 OkHttpClient `followRedirects(true)`，认证请求带 Cookie 时可能跨域泄露。应设 `false`。
- ~~`GachaResponseParser.parseRarity` 用 `value.contains("5")` 判断星级，`"15"`/`"S5"` 会被误识别为 5 星。~~ ✓ 已在"删除接口配置 + 新增主题"轮修复：精确匹配优先 + coerceIn(3..5) + contains 兜底
- ~~`AuthViewModel` 方案 2/3 把 `ltoken`/`cookie_token` 当作 `stoken` 存储，后续请求会带错误值。~~ ✓ 已在"验证码/密码登录等价修复"轮修复：删除方案 2/3，只保留 stoken 直存 与 login_ticket→multiToken 换 stoken 两条路径
- 二维码轮询 `while (isActive) { delay(2000) }` 无最大次数 / 超时上限，仅依赖服务端返回 `-106`。
- `GachaSyncService.syncAll` 的"读-判断-写"非原子，并发同步可能双进。应改 `Mutex.tryLock()`。

**一般（架构 / 可维护性）**
- ~~两个 `OkHttpClient` 不共享连接池，应抽成 Hilt 单一 `@Singleton`。~~ ✓ 已在性能优化轮修复
- ~~`AuthRepository` 每个字段单独 `data.first()`，`buildCookieString` 连读 5 次 DataStore。~~ ✓ 已在性能优化轮修复（其余 getter 仍未批量化，调用频率低，暂不动）
- `DsSigner` 既是 `object` 又标 `@Singleton` 冗余；`APP_VERSION="2.71.1"` 硬编码，过期会让全部请求失败且无运行时检测。
- `ConfigStore` 配置解析失败静默回退默认，用户无感知。应 log 并暴露 error state。
- 引入 `kotlinx-serialization-json` 但全程用 Gson，多余依赖。

**构建 / 测试**
- release 无签名配置，无法发布。
- `proguard-rules.pro` 未 keep `GachaRecordEntity`，一旦开启 minify 会立即出问题。
- 完全无单元测试，`GachaStatsCalculator` 等纯逻辑模块尤其值得补测。
- `targetSdk = 34` 在 2026 年偏旧。

> 说明：本轮审查仅记录问题清单，未做代码改动。后续可按"严重"组优先级逐项修复并单独提交。

## License

MIT
