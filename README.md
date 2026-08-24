# GenshinGachaHelper

原神抽卡助手 — Android 原生应用，Kotlin + Jetpack Compose 开发。

## 功能特性

- **米游社扫码登录**：通过通行证 Passport API 扫码授权，无需复制链接
- **抽卡记录同步**：一键同步角色池、武器池、常驻池抽卡记录
- **UIGF 导入/导出**：支持 UIGF v3.0 / v4.0 格式的历史数据导入导出，兼容 Snap.Hutao 等工具
- **本地永久存储**：Room SQLite 数据库，所有数据保存在本地，无服务器依赖
- **接口配置可导入**：JSON 格式接口配置，支持自定义 API 地址和参数
- **统计分析**：抽卡统计、保底计算、五星抽数分析
- **设备指纹防风控**：自动生成 device_fp，规避 -3503 风控

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
扫码登录 (Passport API)
  │
  ├─ createQRLogin → 生成二维码
  ├─ queryQRLoginStatus → 轮询扫码状态
  │     └─ Confirmed → 返回 stoken
  ├─ getCookieAccountInfoBySToken → 换取 cookie_token
  ├─ getLTokenBySToken → 换取 ltoken
  │
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

## License

MIT
