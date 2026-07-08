# WatchDog - AI平台API额度监测

实时监测各大AI平台的API剩余额度，支持 DeepSeek、Kimi（月之暗面）、智谱GLM、硅基流动（SiliconFlow）。

## 功能

- 📊 **一目了然的仪表盘** — 四大AI平台额度状态实时显示
- 🔄 **下拉刷新 + 自动刷新** — 每5分钟自动更新，也可手动下拉
- 🔑 **API Key管理** — 各平台独立配置，安全保存在本地
- 📈 **本月消耗追踪** — 记录月初余额，计算月度API花费
- 🎨 **Material 3设计** — 品牌色区分平台

## 支持的平台

| 平台 | 数据来源 | 显示内容 |
|------|----------|----------|
| **DeepSeek** | `/user/balance` | 总余额 + 本地月度消耗追踪 |
| **Kimi (Moonshot)** | `/v1/users/me/balance` | 总余额 + 本地月度消耗追踪 |
| **智谱GLM** | `/api/biz/tokenAccounts/list/my` | 资源包Token余额 + 累计已用 |
| **硅基流动** | `/v1/user/info` + `/v1/dashboard/billing/usage` | 总余额 + 官方月度用量 |

> ⚠️  说明：DeepSeek 和 Kimi 的API不提供月度用量查询接口，本App通过记录月初余额快照来推算月度消耗量。初始使用时会显示 0，随着API调用消耗，数据会逐渐准确。GLM 和 硅基流动 使用官方接口获取用量数据。

## 技术栈

- **语言**: Kotlin
- **UI**: Jetpack Compose + Material 3
- **网络**: Retrofit 2 + OkHttp
- **图片**: Coil (LobeHub AI Icons CDN)
- **存储**: SharedPreferences
- **最低版本**: Android 7.0 (API 24)
- **编译SDK**: Android 15 (API 37)

## 使用方式

1. 从 [Release](https://github.com/你的用户名/WatchDog/releases) 下载最新 APK 安装
2. 点击右下角 ⚙️ 按钮进入 API Key 管理
3. 为各平台填入 API Key：
   - DeepSeek: [获取API Key](https://platform.deepseek.com/api_keys)
   - Kimi: [获取API Key](https://platform.moonshot.cn)
   - 智谱GLM: [获取API Key](https://open.bigmodel.cn)
   - 硅基流动: [获取API Key](https://siliconflow.cn)
4. 返回首页，下拉刷新查看额度

## 构建

```bash
./gradlew assembleDebug
# APK 输出: app/build/outputs/apk/debug/app-debug.apk
```

## License

MIT
