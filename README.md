# WatchDog - AI平台API额度监测

实时监测各大 AI 平台的 API 剩余额度，支持 **DeepSeek、Kimi（月之暗面）、智谱GLM、硅基流动（SiliconFlow）、火山方舟、Kimi Code、小米 MiMo**。

## 功能

- 📊 **一目了然的仪表盘** — 各 AI 平台额度状态实时显示 + 总余额 Hero 总览卡（含余额趋势图）；未配置平台折叠为单个"可接入"入口，首屏聚焦已接入数据
- 📈 **余额趋势图** — 记录总余额历史快照，在 Hero 卡内绘制趋势柱状图（可折叠、点击柱子查看指定时间点余额；柱高按区间相对值绘制以便看出波动）
- 📊 **Token 用量统计卡** — 展示 DeepSeek 控制台按模型 × 按天的真实 Token 消耗柱状图，支持来源/模型/指标（总Tokens/输入/输出/请求数/成本）/时间范围（近7天/近30天/全部）筛选（筛选面板默认收起），点击柱子可查看当日各模型明细
- 🔔 **余额低水位通知** — 余额/剩余配额低于阈值或耗尽时推送本地通知（阈值可配置）
- 🔄 **下拉刷新 + 自动刷新** — 支持手动下拉，自动刷新间隔可配置（仅前台运行）
- 🔑 **API Key 管理** — 各平台独立配置，使用 Android Keystore AES-GCM 加密存储
- 🌐 **内嵌网页登录抓取** — 无官方余额/用量 API 的平台（小米 MiMo 必需、DeepSeek 可选增强），在 App 内嵌的登录页中用**密码 / 短信验证码 / 扫码等任意方式**登录（App 不保存账号密码），登录后自动抓取会话凭证（Keystore 加密存储）读取控制台真实数据。WebView 登录态持久化：会话过期后重开登录页，登录态仍有效时自动重新抓取凭证，**无需再次输入账号**。DeepSeek 爬取失败自动回退 API Key 官方接口
- 📅 **本月消耗追踪** — 增量累计每次刷新的余额下降量作为月度消耗（月中充值不会把已统计的用量清零）
- 🎨 **Material 3 设计** — 品牌色区分平台，支持浅色/深色/跟随系统

## 支持的平台

| 平台 | 数据来源 | 显示内容 |
|------|----------|----------|
| **DeepSeek** | `/user/balance` + 网页控制台（可选增强） | 总余额 + 真实本月用量 + 按模型调用明细（网页登录后） |
| **Kimi (Moonshot)** | `/v1/users/me/balance` | 总余额 + 本地月度消耗追踪 |
| **智谱GLM** | `/api/biz/tokenAccounts/list/my` | 资源包 Token 余额 + 累计已用 |
| **硅基流动** | `/v1/user/info` | 总余额 + 可用余额 |
| **火山方舟** | 本地估算 | 手动填写初始余额 + 本地月度追踪（无远程余额接口） |
| **Kimi Code** | `/v1/usages` | 订阅套餐 + 配额窗口（5小时/周/月） |
| **小米 MiMo** | 网页控制台（`platform.xiaomimimo.com/api/v1/*`） | 账户真实余额 + Token Plan 订阅配额用量（需网页登录） |

> ⚠️ 说明：DeepSeek、Kimi、硅基流动的 API 不提供稳定的月度用量查询接口，本 App 通过增量累计每次刷新的余额下降量来推算月度消耗（月中充值不会把已统计的用量清零）。这类推算值在界面上以**"本月用量（估算）"**标注，与平台接口返回的真实用量（如 DeepSeek 网页控制台、GLM 累计已用）区分显示。火山方舟需手动填写初始余额。GLM 与 Kimi Code 使用官方接口获取数据。
>
> 📌 **关于"网页控制台"数据源**：部分平台没有"仅凭 API Key"的完整余额/用量接口，本 App 通过调用其网页控制台内部接口获取更完整的数据（非官方接口，可能随版本变更；平台接口位于 WAF 之后，App 已携带浏览器特征头绕过）。会话获取方式：
> 1. **内嵌网页登录（推荐）**：在设置弹窗点"打开网页登录"，App 内嵌平台真实登录页，用密码 / 短信验证码 / 扫码等任意方式登录（支持验证码登录用户，App 不保存账号密码），登录成功后自动抓取凭证（DeepSeek：localStorage userToken + Cookie；MiMo：api-platform_ph Cookie）并加密保存，返回自动刷新。
> 2. **手动粘贴会话令牌（备选）**：从 PC 浏览器开发者工具复制 Cookie / localStorage 值。
> 3. **仅 API Key**：DeepSeek 官方余额接口 + 本地月度估算（控制台爬取失败时的自动回退）。
>
> WebView 登录态（Cookie + localStorage）持久化保存：会话过期后重新打开登录页，若平台登录态仍有效则自动重新抓取凭证，无需再次输入账号。
>
> 平台差异：
> - **小米 MiMo（会话必需）**：无官方余额/用量 API；官方 Cookie 有效期 24 小时，过期后需重新进入登录页（有验证码时需人工完成）
> - **DeepSeek（会话可选增强）**：配置后可读取控制台的**真实本月用量与按模型调用明细**；未配置或会话失效时回退官方余额接口 + 本地估算

## 技术栈

- **语言**: Kotlin
- **UI**: Jetpack Compose + Material 3（余额趋势图 / Token 用量柱状图使用 Compose Canvas 绘制，无额外图表依赖）
- **网络**: Retrofit 2 + OkHttp
- **架构**: 手动 `AppContainer` 依赖注入；额度数据层为"编排 Repository + 每平台 `PlatformQuotaProvider` 策略"，新增平台只需实现一个 Provider。Provider 与 ViewModel 均依赖窄接口（`PlatformConfigSource` / `QuotaCache` / `WebSessionAccess` / `RefreshIntervalSource`），无需 Robolectric 即可用纯 JVM 单测覆盖
- **图片**: Coil (LobeHub AI Icons CDN；平台图标关闭磁盘缓存，避免缓存残留暴露已配置平台)
- **存储**: SharedPreferences（API Key / 会话凭证经 Android Keystore AES-GCM 加密，且排除出云备份）
- **包名**: `io.github.coderirse.watchdog`
- **最低版本**: Android 7.0 (API 24)
- **编译 SDK**: API 37
- **单元测试**: 158 个（`./gradlew testDebugUnitTest`）

## 使用方式

1. 从 [Release](https://github.com/coderirse/WatchDog/releases) 下载最新 APK 安装
   > ⚠️ **v1.8.0 升级注意**：自 v1.8.0 起应用包名变更为 `io.github.coderirse.watchdog`（原 `com.example.watchdog`），
   > 与 v1.7.0 及更早版本**无法覆盖升级**——请先卸载旧版再安装新版（API Key 与网页会话需重新配置）。
   >
   > 📌 **v1.8.1**：与 v1.8.0 同包名，可直接覆盖安装。本次为数据准确性与界面一致性修复，
   > 主要变化见下方"v1.8.1 修复说明"。
2. 点击右上角 ⚙️ 设置图标进入 API Key 管理
3. 为各平台填入 API Key：
   - DeepSeek: [获取 API Key](https://platform.deepseek.com/api_keys)
   - Kimi: [获取 API Key](https://platform.moonshot.cn)
   - 智谱GLM: [获取 API Key](https://open.bigmodel.cn)
   - 硅基流动: [获取 API Key](https://siliconflow.cn)
   - 火山方舟: [获取 API Key](https://console.volcengine.com/ark)
   - Kimi Code: [获取 API Key](https://kimi.com)
   - 小米 MiMo: [获取 API Key](https://platform.xiaomimimo.com)（另需按上文完成一次网页登录）
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

**版本号递增规则**（每次重新构建按改动大小递增，便于区分测试包）：

- **补丁位** `x.x.+1`：bug 修复、文案调整、小优化（versionCode +1）
- **次版本位** `x.+1.0`：新功能或功能重构（如登录抓取方案改版）（versionCode +1）
- **主版本位** `+1.0.0`：架构级变更或不兼容调整（versionCode +1）

测试期 APK 版本号在 设置 → 更多 → 当前版本 可查。

需在仓库 Actions Secrets 中预先配置以下签名密钥：

| Secret | 说明 |
|--------|------|
| `RELEASE_KEYSTORE_BASE64` | `release.keystore` 的 Base64 编码 |
| `RELEASE_KEYSTORE_PASSWORD` | keystore 密码 |
| `RELEASE_KEY_ALIAS` | key 别名（如 `watchdog`） |
| `RELEASE_KEY_PASSWORD` | key 密码 |

## v1.8.1 修复说明

本次为代码审查后的集中修复，分为"数据准确性"与"界面一致性"两类。

**数据准确性**

- **修复假 0 余额**：Kimi 在"有网页会话但凭据不完整 / 控制台解析失败"时，此前返回 `可用 + 余额 0.00`，
  该假 0 会混入 Hero 总余额、写入余额趋势快照（造成折线跳水），并让卡片仍显示"正常"且不给重登入口。
  现在这类情况如实报错并提供"重新登录"按钮。
- **修复"累计消费"冒充"本月用量"**：Kimi 只有累计消费数据时，不再把累计值填进本月用量（此前数字严重偏大），改为显示占位符。
- **修复 DeepSeek 控制台失败时显示 0.00**：控制台-only 模式下抓取失败不再伪造可用状态与 0 余额；
  失败原因按 HTTP 码翻译为可读文案（WAF 拦截 / 会话过期 / 接口变更）。
- **余额字段缺失不再按 0 计入总额**：`sumCnyBalance()` 跳过无法解析的余额（真实的 `0.00` 仍计入），避免总额静默偏低。
- **"本月用量"口径可视**：本地增量推算的用量标注为"本月用量（估算）"，与接口真实值区分。
- **余额趋势图回归**：v1.8.0 改版时 UI 移除了趋势图，但记录/加载链路仍在（数据只写不读）。现已在 Hero 卡内恢复为可折叠柱状图，柱高按区间相对值绘制以便看出波动。
- **Hero 卡时间口径**：由"最新数据时间"改为"最早数据时间（数据时间）"，避免某平台仍在用旧缓存却显示"刚刚更新"。

**界面、无障碍与可用性**

- **轴标签不再叠印**：图表日期标签改为按实测文字宽度抽稀（此前固定步长导致真机出现 `09092630` 这类重叠），跨年自动补年份。
- **进度条语义消歧**：同一进度条此前承载"剩余占比/已用占比/占本卡最大占比"三种相反含义，现在一律带文字前缀。
- **Hero 卡改为主题中性卡**：全彩渐变只保留给平台品牌卡，消除两个渐变卡争夺注意力的问题；同时修复其次要文字对比度不足（约 3.2:1 → 达 WCAG AA）。
- **触控热区达标**：Token 用量筛选 chip 改用 Material 3 `FilterChip`，小文字链接改 `TextButton`，"调用明细"展开行补 48dp 最小高度。
- **筛选面板默认收起**：Token 用量卡的 4 行筛选条改为一行摘要 + 展开面板；无用量数据时整卡不渲染。
- **设置页可点性**：平台卡增加 chevron 与读屏标签，开关独立成行，不再与整卡点击区嵌套。
- **通知权限提示**：余额预警已开启但系统通知权限被拒时，设置页明确提示并提供系统设置入口（此前通知被静默丢弃）。
- **登录页视觉统一**：颜色/尺寸抽到 `colors.xml` / `dimens.xml`（深色由 `values-night` 接管），填充按钮由硬编码紫色改为主题主色；纯 View 实现保留（避免 Compose 包装 WebView 时的键盘重影问题）。
- **图表读屏支持**：Canvas 柱状图补充逐日数值文字列表。

**安全与工程**

- **修复额度缓存反序列化崩溃隐患**：`QuotaCacheStore` 用 Gson 反射反序列化 `QuotaInfo`，
  而 Gson 会绕过 Kotlin 默认值向缺失字段注入 `null`——`modelUsages` 等非空集合一旦为 null，
  UI 读取即 NPE，而这份缓存正是断网时唯一的兜底数据源。现新增 `QuotaInfoDeserializer`
  在读取侧统一规范化（集合→空列表、字符串→默认值、枚举→SERVER），并有测试守着这条防线。
- **修复密钥降级信封**：Keystore 不可用时明文会带 `plain:v1:` 前缀，避免明文恰好以 `enc:v1:` 开头导致永久无法解密；降级标记改为按本次写入结果判定，消除多平台并行保存时的竞态。
- **清理过期缓存**：启动时清除历史 versionCode 的额度缓存键，避免无界增长。
- **平台图标关闭磁盘缓存**：避免缓存残留暴露已配置平台。
- **登录页诊断脚本仅在 debug 构建注入**：release 下不再向用户登录页注入多余 JS。
- **测试补齐**：新增 `DashboardViewModel` 关键分支（三态推导 / 趋势写入条件 / 刷新去重）、图表轴标签抽稀、以及**额度缓存反序列化防线**测试，共 158 个单测。

## License

MIT
