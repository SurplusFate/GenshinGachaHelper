# 更新日志

本项目自 1.8.3 起正式维护本文件。更早的历史请查阅 `git log`。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### 变更
- 构建：签名凭据（keystore 路径、口令、别名）外移至 `local.properties` / 环境变量，源码中不再硬编码口令
- CI：新增 `testDebugUnitTest` 单元测试步骤；一次性签名密钥改为随机口令生成

## [1.8.3] - 2026-09-12

### 新增
- 液态玻璃（Liquid Glass）UI 全量落地：基于 `io.github.kyant0:backdrop` 的真实背景采样 + 透镜折射效果
- 每日自动签到：米游社签到改为 WorkManager 周期任务，支持开机/重启补偿

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
- release 包开启 R8 minify，APK 体积由约 13.5MB 降至约 2.3MB

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
