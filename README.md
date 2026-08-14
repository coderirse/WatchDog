# WatchDog - AI平台API额度监测

实时监测各大 AI 平台的 API 剩余额度，支持 **DeepSeek、Kimi（月之暗面）、智谱GLM、硅基流动（SiliconFlow）、火山方舟、Kimi Code**。

## 功能

- 📊 **一目了然的仪表盘** — 各 AI 平台额度状态实时显示 + 总余额 Hero 总览卡
- 📈 **余额趋势图** — 记录总余额历史快照，绘制 CNY 总余额随时间变化的折线趋势
- 🔔 **余额低水位通知** — 余额/剩余配额低于阈值或耗尽时推送本地通知（阈值可配置）
- 🔄 **下拉刷新 + 自动刷新** — 支持手动下拉，自动刷新间隔可配置（仅前台运行）
- 🔑 **API Key 管理** — 各平台独立配置，使用 Android Keystore AES-GCM 加密存储
- 📅 **本月消耗追踪** — 记录月初余额，计算月度 API 花费
- 🎨 **Material 3 设计** — 品牌色区分平台，支持浅色/深色/跟随系统

## 支持的平台

| 平台 | 数据来源 | 显示内容 |
|------|----------|----------|
| **DeepSeek** | `/user/balance` | 总余额 + 本地月度消耗追踪 |
| **Kimi (Moonshot)** | `/v1/users/me/balance` | 总余额 + 本地月度消耗追踪 |
| **智谱GLM** | `/api/biz/tokenAccounts/list/my` | 资源包 Token 余额 + 累计已用 |
| **硅基流动** | `/v1/user/info` | 总余额 + 可用余额 |
| **火山方舟** | 本地估算 | 手动填写初始余额 + 本地月度追踪（无远程余额接口） |
| **Kimi Code** | `/v1/usages` | 订阅套餐 + 配额窗口（5小时/周/月） |

> ⚠️ 说明：DeepSeek、Kimi、硅基流动的 API 不提供稳定的月度用量查询接口，本 App 通过记录月初余额快照来推算月度消耗量；火山方舟需手动填写初始余额。GLM 与 Kimi Code 使用官方接口获取数据。

## 技术栈

- **语言**: Kotlin
- **UI**: Jetpack Compose + Material 3（趋势图使用 Compose Canvas 绘制，无额外图表依赖）
- **网络**: Retrofit 2 + OkHttp
- **依赖注入**: 手动 `AppContainer`
- **图片**: Coil (LobeHub AI Icons CDN)
- **存储**: SharedPreferences（API Key 经 Android Keystore 加密）
- **最低版本**: Android 7.0 (API 24)
- **编译 SDK**: API 37

## 使用方式

1. 从 [Release](https://github.com/coderirse/WatchDog/releases) 下载最新 APK 安装
2. 点击右下角 ⚙️ 按钮进入 API Key 管理
3. 为各平台填入 API Key：
   - DeepSeek: [获取 API Key](https://platform.deepseek.com/api_keys)
   - Kimi: [获取 API Key](https://platform.moonshot.cn)
   - 智谱GLM: [获取 API Key](https://open.bigmodel.cn)
   - 硅基流动: [获取 API Key](https://siliconflow.cn)
   - 火山方舟: [获取 API Key](https://console.volcengine.com/ark)
   - Kimi Code: [获取 API Key](https://kimi.com)
4. 在设置页按需调整自动刷新间隔、余额预警阈值
5. 返回首页，下拉刷新查看额度与趋势

## 构建

```bash
# 单元测试
./gradlew testDebugUnitTest

# Debug APK
./gradlew assembleDebug
# APK 输出: app/build/outputs/apk/debug/app-debug.apk

# Release APK（需在根目录配置 keystore.properties，参考 keystore.properties.example）
./gradlew assembleRelease
# APK 输出: app/build/outputs/apk/release/app-release.apk
```

## 持续集成与自动发布

- `.github/workflows/android.yml`：push / PR 到 master 时执行单元测试，并构建 debug 与 release APK（校验 R8 混淆），产出 debug APK 工件。
- `.github/workflows/release.yml`：推送 `v*` 标签时自动构建**签名** release APK 并发布到 GitHub Releases。

### 发布新版本

1. 修改 `app/build.gradle.kts` 中的 `versionCode` / `versionName`
2. 提交并打标签：`git tag v1.2.0 && git push origin master --tags`
3. CI 会自动构建签名 APK 并创建对应 Release（使用 `--generate-notes` 生成变更说明）

需在仓库 Actions Secrets 中预先配置以下签名密钥：

| Secret | 说明 |
|--------|------|
| `RELEASE_KEYSTORE_BASE64` | `release.keystore` 的 Base64 编码 |
| `RELEASE_KEYSTORE_PASSWORD` | keystore 密码 |
| `RELEASE_KEY_ALIAS` | key 别名（如 `watchdog`） |
| `RELEASE_KEY_PASSWORD` | key 密码 |

## License

MIT
