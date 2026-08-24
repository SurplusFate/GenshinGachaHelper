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
        └─ 多域名合并读取 → stoken_v2 / login_ticket

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
- `GachaResponseParser.parseRarity` 用 `value.contains("5")` 判断星级，`"15"`/`"S5"` 会被误识别为 5 星。应精确匹配。
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
