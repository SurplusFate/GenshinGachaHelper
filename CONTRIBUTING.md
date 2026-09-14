# 贡献指南

感谢你对本项目的兴趣！提交改动前请先阅读以下说明。

## 环境要求

| 组件 | 版本 |
|---|---|
| JDK | 17（Temurin 17.0.20.1 已验证） |
| Android SDK | platform-36 + build-tools 36.0.0 |
| Gradle | 8.14.5（仓库自带 wrapper，可直接用 `./gradlew`） |
| AGP / Kotlin | 8.13.2 / 2.3.21 |

## 本地构建

```bash
# 1. 配置 SDK 路径
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# 2. 配置签名凭据（local.properties 已在 .gitignore 中，切勿提交）
cat >> local.properties <<'EOF'
keystore.file=/abs/path/to/release.keystore
keystore.storePassword=你的口令
keystore.keyAlias=gacha-release
keystore.keyPassword=你的口令
EOF

# 3. 构建 release 包
./gradlew assembleRelease --no-daemon

# 4. 跑单元测试
./gradlew testDebugUnitTest --no-daemon
```

> 首次构建若无网络，需先联网拉齐依赖；离线模式下 `--offline` 只能使用已缓存的依赖。

也可用环境变量替代 `local.properties`：

```
KEYSTORE_FILE / KEYSTORE_STORE_PASSWORD / KEYSTORE_KEY_ALIAS / KEYSTORE_KEY_PASSWORD
```

## 提交规范

- 提交信息使用 `type(scope): 描述` 格式，type 取值：`feat` / `fix` / `refactor` / `perf` / `docs` / `chore` / `ci` / `test`。
- 一个提交只做一件事，避免把格式化与逻辑改动混在一起。
- **严禁**提交以下内容：`*.keystore`、`local.properties`、构建日志、真实的 Cookie / `authkey` / `stoken` / token。

## 发版流程

1. 更新 `app/build.gradle.kts` 中的 `versionCode` 与 `versionName`（`versionCode` 必须递增）。
2. 同步更新 `CHANGELOG.md`。
3. 打 annotated tag：`git tag -a vX.Y.Z -m "vX.Y.Z"`。
4. 推送分支与 tag，并在 GitHub Release 中附加 release APK，资产命名格式：`GenshinGachaHelper-vX.Y.Z-<主题>.apk`。
5. 确认 CI 通过（构建 + 单元测试）。

## 代码风格

- 遵循官方 Kotlin 编码规范（`kotlin.code.style=official`）。
- UI 相关改动请同时检查浅色 / 深色两种模式。
- 涉及抽卡统计逻辑的改动**必须**补充或更新 `app/src/test` 下的单元测试。

## 提 Issue

请说明：应用版本号、设备与系统版本、复现步骤、预期结果与实际结果。日志或截图请先打码，去掉 UID 等个人信息。
