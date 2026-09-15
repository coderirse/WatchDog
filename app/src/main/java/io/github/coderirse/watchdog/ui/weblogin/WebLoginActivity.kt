package io.github.coderirse.watchdog.ui.weblogin

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.coderirse.watchdog.BuildConfig
import io.github.coderirse.watchdog.MainActivity
import io.github.coderirse.watchdog.R
import io.github.coderirse.watchdog.WatchDogApplication
import io.github.coderirse.watchdog.data.model.PlatformType
import io.github.coderirse.watchdog.data.repository.MiMoSessionVerifier
import io.github.coderirse.watchdog.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 内嵌网页登录页——"网页控制台"数据源的凭证获取通道。
 *
 * 核心理念：不替用户登录，只陪用户登录。
 * - WebView 就是浏览器本身（Chrome Mobile UA 绕过平台 WAF），用户在平台真实页面上
 *   用任何方式登录：密码 / 短信验证码 / 扫码——App 不感知、不干预、不保存账号密码；
 * - WebView 的 Cookie 与 localStorage 均持久化（绝不清除）：token 过期后再次进入，
 *   若平台会话仍有效，页面直接是登录态，自动重新抓取凭证，用户无需再输入账号；
 * - 凭证抓取为"先验证再采用"：在页面上下文内 fetch 平台真实接口验证候选凭证
 *   真实可用后才保存返回（页面内请求天然携带 Cookie 与浏览器指纹，不受 WAF 影响），
 *   杜绝保存无效凭证导致主界面静默失败；
 * - 抓取脚本完全自研（未沿用任何参考工程写法）：
 *   · DeepSeek 候选来源三路扫描：localStorage + sessionStorage + document.cookie，
 *     兼容 JSON 包装 {"value":...} 与裸 token 字符串两种格式；
 *   · evaluateJavascript 回调只收同步返回值（Promise 不支持），异步验证结果经
 *     window.__wdResult 中转，两拍轮询读取；
 *   · 被 API 拒绝的 token 记入页内黑名单、网络异常 30s 退避，避免轮询高频打接口。
 */
class WebLoginActivity : ComponentActivity() {

    private var loginPlatform: PlatformType = PlatformType.MIMO
    private var finished = false

    /** WebView 就绪状态：null → 显示加载中，创建完成后挂载。 */
    private var webViewReady: WebView? = null
    private var webViewContainer: LinearLayout? = null
    private var statusView: TextView? = null
    private var statusText: String = ""

    /** 已被平台 API 拒绝的 MiMo Cookie 值（避免轮询反复用失效凭证打接口）。 */
    private var mimoRejectedToken: String? = null

    /** MiMo App 侧校验进行中标志：避免 1.5s 轮询叠加并发请求。 */
    private var mimoVerifying = false

    /** Kimi 方案 A：捕获到凭证后暂存内存，停留页面，待用户确认(完成并返回)再存储+跳转。 */
    private var kimiToken: String? = null
    private var kimiCookie: String? = null
    private var captureButton: Button? = null

    /**
     * 调试日志（仅 debug 构建）：统一走 [DebugLog]——同时写 logcat 与
     * `cacheDir/watchdog_debug.log`。落盘的意义是真机诊断时设备可能掉线、
     * logcat 缓冲区会被冲掉，而这类"抓不到数据"的问题必须在现场取到痕迹。
     */
    private fun flog(msg: String) {
        if (!BuildConfig.DEBUG) return
        DebugLog.i("WatchDogLogin", msg)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loginPlatform = intent.getStringExtra(EXTRA_PLATFORM)
            ?.let { runCatching { PlatformType.valueOf(it) }.getOrNull() }
            ?: PlatformType.MIMO

        setStatus(getString(R.string.weblogin_status_loading, loginPlatform.displayName))
        setContentView(buildLayout())
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = webViewReady
                if (wv?.canGoBack() == true) wv.goBack() else finish()
            }
        })

        createWebView()
        startCapturePolling()
    }

    /**
     * 捕获按钮点击：
     * - Kimi：若已捕获凭证(方案A停留页面)则"完成并返回"，否则手动触发捕获；
     * - 其他平台：手动触发捕获。
     */
    private fun onCaptureButtonClick() {
        if (loginPlatform == PlatformType.KIMI && kimiToken != null) {
            finishWithSession(kimiToken!!, kimiCookie)
        } else {
            tryCapture(manual = true)
        }
    }

    /** Kimi 捕获到凭证后把按钮文字改为"完成并返回"。 */
    private fun updateCaptureButton() {
        if (loginPlatform == PlatformType.KIMI && kimiToken != null) {
            captureButton?.text = getString(R.string.weblogin_done_kimi)
        }
    }

    /** 更新状态行文字（纯 View：直接改 TextView 文本）。 */
    private fun setStatus(msg: String) {
        statusText = msg
        statusView?.text = msg
    }

    /**
     * 构建纯 View 布局（LinearLayout）：顶部标题区 + WebView(weight=1) + 底部按钮行。
     * 不使用 Compose——AndroidView 包装 WebView 在键盘弹出/窗口 resize 时会被 Compose
     * 重组移动视图，与 WebView 内部 HTML5 输入合成叠加导致字符重影/镜像（真机实测）。
     * 纯 View 布局下 WebView 作为直接子视图，窗口缩放仅平移/缩放整棵 View 树，不干扰
     * WebView 内部渲染。
     */
    private fun buildLayout(): LinearLayout {
        // 深色模式适配：标题区/按钮行随系统主题，WebView 内容仍走白底（平台登录页为浅色）。
        // 颜色一律取自资源（令牌与 Compose 主题同源）：深色值由 values-night/colors.xml
        // 自动接管，代码不再判断 uiMode、不再硬编码色值。
        val chromeBg = ContextCompat.getColor(this, R.color.wd_login_chrome_bg)
        val titleColor = ContextCompat.getColor(this, R.color.wd_login_title_text)
        val subColor = ContextCompat.getColor(this, R.color.wd_login_sub_text)
        val cancelColor = ContextCompat.getColor(this, R.color.wd_login_outlined_bg)
        val cancelTextColor = ContextCompat.getColor(this, R.color.wd_login_outlined_text)
        val primaryColor = ContextCompat.getColor(this, R.color.wd_login_primary)

        // 尺寸一律取自 @dimen/wd_login_*（按当前屏幕密度换算为像素）
        val contentPadding = resources.getDimensionPixelSize(R.dimen.wd_login_content_padding)
        val infoPaddingTop = resources.getDimensionPixelSize(R.dimen.wd_login_info_padding_top)
        val infoPaddingBottom = resources.getDimensionPixelSize(R.dimen.wd_login_info_padding_bottom)
        val buttonsPaddingTop = resources.getDimensionPixelSize(R.dimen.wd_login_buttons_padding_top)
        val buttonsPaddingBottom = resources.getDimensionPixelSize(R.dimen.wd_login_buttons_padding_bottom)
        val buttonHeight = resources.getDimensionPixelSize(R.dimen.wd_login_button_height)
        // GradientDrawable.cornerRadius 需要 Float，故这里用 getDimension 而非 getDimensionPixelSize
        val buttonCorner = resources.getDimension(R.dimen.wd_login_button_corner)

        // 全屏适配：根布局不设左右 padding，WebView 铺满（修复"白边不适配"）
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(chromeBg)
        }

        // 标题区左右留边距（仅文字区，不作用于 WebView）
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(contentPadding, infoPaddingTop, contentPadding, infoPaddingBottom)
            setBackgroundColor(chromeBg)
        }
        val title = TextView(this).apply {
            text = getString(R.string.weblogin_title, loginPlatform.displayName)
            textSize = 18f
            setTextColor(titleColor)
        }
        val hint = TextView(this).apply {
            text = getString(R.string.weblogin_hint)
            textSize = 12f
            setTextColor(subColor)
        }
        val status = TextView(this).apply {
            text = statusText
            textSize = 11f
            setTextColor(subColor)
            maxLines = 4
        }
        statusView = status
        info.addView(title)
        info.addView(hint)
        info.addView(status)
        root.addView(info)

        // WebView 铺满剩余空间（无左右边距，全屏适配）
        val webViewContainer = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        this.webViewContainer = webViewContainer
        root.addView(webViewContainer)

        // 底部按钮：Material 观感（圆角 + 主色填充 / 次要色描边），非复古系统按钮
        val cancelBg = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = buttonCorner
            setColor(cancelColor)
        }
        val captureBg = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = buttonCorner
            setColor(primaryColor)
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(contentPadding, buttonsPaddingTop, contentPadding, buttonsPaddingBottom)
            setBackgroundColor(chromeBg)
        }
        // 按钮间距
        val gap = resources.getDimensionPixelSize(R.dimen.wd_login_button_gap)
        val cancel = Button(this).apply {
            text = getString(R.string.action_cancel)
            background = cancelBg
            setTextColor(cancelTextColor)
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, buttonHeight, 1f)
            setOnClickListener { finish() }
        }
        val capture = Button(this).apply {
            text = getString(R.string.weblogin_done)
            background = captureBg
            setTextColor(Color.WHITE)
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, buttonHeight, 1f)
            setOnClickListener { onCaptureButtonClick() }
        }
        captureButton = capture
        // 复用布局参数：给 gap 用 margin
        val cancelLp = LinearLayout.LayoutParams(0, buttonHeight, 1f)
        cancelLp.marginEnd = gap
        cancel.layoutParams = cancelLp
        buttons.addView(cancel)
        buttons.addView(capture)
        root.addView(buttons)

        return root
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() {
        val platform = loginPlatform
        // 仅 debug 构建开启 WebView 远程调试（便于 DevTools 观察平台接口请求）。
        // release 严禁开启：否则任何拿到设备的人可通过 USB DevTools 读取登录页
        // localStorage/Cookie 中的会话凭证（P0 安全）。
        if (io.github.coderirse.watchdog.BuildConfig.DEBUG) {
            android.webkit.WebView.setWebContentsDebuggingEnabled(true)
        }
        val startUrl = when (platform) {
            PlatformType.MIMO -> "https://platform.xiaomimimo.com/"
            PlatformType.DEEPSEEK -> "https://platform.deepseek.com/"
            PlatformType.KIMI -> "https://platform.kimi.com/"
            else -> return
        }

        val webView = WebView(this)
        webView.settings.apply {
            javaScriptEnabled = true
            // localStorage 持久化：平台登录态保存其中，重开页面免登录的关键（绝不清除）
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            // Chrome 移动端 UA：绕过平台外层 WAF 的浏览器校验，且与 OkHttp 请求头保持一致
            userAgentString = CHROME_MOBILE_UA
            // 修复 MiMo 登录页"黑屏"：系统深色下 WebView 暗色化(forceDark)会把页面
            // 强制渲染成纯黑背景（小米账号页尤其明显，视觉等同黑屏）。按 API 分段关闭：
            // API 33+ 用 algorithmicDarkeningAllowed，API 29-32 用 forceDark。
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                isAlgorithmicDarkeningAllowed = false
            } else if (android.os.Build.VERSION.SDK_INT >= 29) {
                @Suppress("DEPRECATION")
                forceDark = WebSettings.FORCE_DARK_OFF
            }
        }
        // 画布白底兜底：页面未设背景时露 View 白色而非黑
        webView.setBackgroundColor(android.graphics.Color.WHITE)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                flog("nav -> ${request.url}")
                return false
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                flog("start $url")
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                flog("finish $url")
                // Kimi：探测页面实际发起的 API 请求（找出控制台用量接口路径）
                // 仅 debug 构建执行：这是纯诊断脚本，release 下既无日志出口也无收益，
                // 不应在用户登录页里注入并运行多余 JS
                if (BuildConfig.DEBUG && loginPlatform == PlatformType.KIMI) {
                    view.evaluateJavascript(
                        "(function(){try{var es=performance.getEntriesByType('resource');" +
                            "return JSON.stringify(es.map(function(e){return e.name.substring(0,120)})" +
                            ".filter(function(n){return /api|usage|bill|consume|quota|stat/i.test(n)}).slice(0,30));}catch(e){return 'ERR'}})()"
                    ) { r -> flog("KIMI_PERF $r") }
                }
                // SPA 登录成功后可能不发生导航（原地写入 token），轮询兜底；这里再加一拍
                tryCapture(manual = false)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                super.onReceivedError(view, request, error)
                android.util.Log.w(
                    "WatchDogLogin",
                    "resource-error ${request.url} ${error.description} (code=${error.errorCode}) main=${request.isForMainFrame}"
                )
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: android.webkit.WebResourceResponse
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                android.util.Log.w(
                    "WatchDogLogin",
                    "http-error ${request.url} status=${errorResponse.statusCode} main=${request.isForMainFrame}"
                )
            }
        }
        webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage): Boolean {
                flog("console[${msg.messageLevel()}] ${msg.message()} @ ${msg.sourceId()}:${msg.lineNumber()}")
                return true
            }
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                flog("progress $newProgress")
            }
        }
        webView.loadUrl(startUrl)
        webViewReady = webView
        // 挂载到布局容器（buildLayout 已先行创建容器）
        webViewContainer?.apply {
            val lp = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            removeAllViews()
            addView(webView, lp)
        }
    }

    /** 每 1.5s 检测一次凭证；验证通过即保存并关闭页面。 */
    private fun startCapturePolling() {
        lifecycleScope.launch {
            while (isActive && !finished && !isFinishing) {
                delay(1500)
                tryCapture(manual = false)
            }
        }
    }

    private fun tryCapture(manual: Boolean) {
        if (finished || isFinishing) return
        when (loginPlatform) {
            PlatformType.DEEPSEEK -> captureDeepSeek(manual)
            PlatformType.MIMO -> captureMimo(manual)
            PlatformType.KIMI -> captureKimi(manual)
            else -> Unit
        }
    }

    // ===== Kimi (Moonshot)：控制台会话凭证未知形态，复用存储扫描 JS 探测，
    // 页面内 fetch 官方 balance 端点验证会话有效性（控制台 userToken 与 API Key 不同）=====

    // ===== Kimi (Moonshot)：控制台实际域为 platform.kimi.com（非 moonshot.cn）。
    // 登录后 localStorage 有 token 键（截图实测），控制台数据靠 Cookie 会话，
    // 因此不做 Bearer 验证——命中页面 token/cookie 即视为会话有效 =====

    private fun captureKimi(manual: Boolean) {
        val wv = webViewReady ?: run { if (manual) toastNoSession(); return }
        runCatching {
            wv.evaluateJavascript(kimiCaptureScript) { raw ->
                flog("kimi capture raw=${raw?.take(120)} url=${wv.url}")
                val obj = parseJsObject(raw)
                flog("kimi parsed obj=${obj?.toString()?.take(120)} tokLen=${obj?.optString("token")?.length}")
                // 每次轮询都尝试保存快照：Kimi 首页金额是 SPA 异步渲染，
                // 首次采集可能为 null，页面渲染完成后才有值，故持续采到非空即覆盖
                if (obj != null) {
                    val b = obj.optString("balance").ifBlank { null }
                    val m = obj.optString("month").ifBlank { null }
                    val t = obj.optString("total").ifBlank { null }
                    if (b != null || m != null || t != null) {
                        flog("kimi snapshot b=$b m=$m t=$t")
                        lifecycleScope.launch(Dispatchers.IO) {
                            runCatching {
                                (application as WatchDogApplication).appContainer.webSessionStore
                                    .saveKimiSnapshot(b, m, t)
                            }
                        }
                    }
                }
                // token 未抓到则存储并提示；已抓到则提示停留
                if (kimiToken == null && obj != null && obj.optString("token").length > 10) {
                    kimiToken = obj.optString("token")
                    kimiCookie = runCatching {
                        CookieManager.getInstance().getCookie("https://platform.kimi.com")
                    }.getOrNull()
                    setStatus(getString(R.string.weblogin_status_captured_kimi))
                    updateCaptureButton()
                }
                when {
                    kimiToken == null && obj?.optBoolean("pending") == true ->
                        setStatus(getString(R.string.weblogin_status_verifying))
                    kimiToken == null && manual -> {
                        toastNoSession()
                        runDiagnostics(wv)
                    }
                    kimiToken != null ->
                        setStatus(getString(R.string.weblogin_status_captured_kimi))
                }
            }
        }.onFailure { if (manual) toastNoSession() }
    }

    /** Kimi 抓取：platform.kimi.com 域下扫 localStorage/sessionStorage/cookie 找 token。 */
    private val kimiCaptureScript = """
        (function(){
          try{
            if (location.host !== 'platform.kimi.com' && location.host !== 'platform.moonshot.cn') return null;
            function ok(t){ return (t && typeof t === 'string' && t.length > 10 && t !== 'null') ? t : null; }
            function pick(x, allowRaw){
              if(!x || typeof x !== 'string') return null;
              try{
                var v = JSON.parse(x);
                if(v && typeof v.value === 'string') return ok(v.value);
                if(typeof v === 'string') return ok(v);
                return null;
              }catch(e){
                return allowRaw ? ok(x) : null;
              }
            }
            function fromStorage(st){
              try{
                var t = pick(st.getItem('token'), true);
                if(t) return t;
                for(var i=0;i<st.length;i++){
                  var k = st.key(i), s = st.getItem(k);
                  if(!s) continue;
                  var t2 = pick(s, /token|credential|auth/i.test(k || '') || s.length > 20);
                  if(t2) return t2;
                }
              }catch(e){}
              return null;
            }
            function fromCookie(){
              try{
                var parts = document.cookie.split(';');
                for(var i=0;i<parts.length;i++){
                  var seg = parts[i].trim();
                  var eq = seg.indexOf('=');
                  if(eq <= 0) continue;
                  var k = seg.substring(0, eq);
                  var v = decodeURIComponent(seg.substring(eq + 1));
                  var t = pick(v, /token|credential|auth|session/i.test(k) || v.length > 20);
                  if(t) return t;
                }
              }catch(e){}
              return null;
            }
            var token = fromStorage(localStorage) || fromStorage(sessionStorage) || fromCookie();
            // 采集结构化金额：定位「标签文本」元素，取其所在行的容器（标签+紧邻数值）。
            // 不用整棵父级拍平文本——那样会跨指标串位（today 误匹配到 balance）。
            var balance = null, today = null, month = null, total = null;
            function grabNumber(container){
              if(!container) return null;
              // 在容器里优先取「￥/¥ 后或独立数字」形如 7.56019 / 1.73004 的值（含小数）
              var txt = (container.textContent||'').replace(/[￥¥]/g, ' ');
              var m = txt.match(/\d{1,3}(?:,\d{3})*(?:\.\d{1,6})/);
              return m ? m[0] : null;
            }
            try{
              var all = document.querySelectorAll('body *');
              for(var i=0;i<all.length;i++){
                var el = all[i];
                if(el.children && el.children.length>0) continue;
                var t = (el.textContent||'').trim();
                if(!t) continue;
                var container = el.parentElement;
                if(/余额|账户余额|可用余额/.test(t) && !balance) {
                  balance = grabNumber(container);
                } else if(/今日消费|今日消耗|今日已用/.test(t) && !today) {
                  today = grabNumber(container);
                } else if(/本月消费|本月消耗|本月已用|本月支出/.test(t) && !month) {
                  month = grabNumber(container);
                } else if(/总消费|累计消费|总消耗|累计消耗/.test(t) && !total) {
                  total = grabNumber(container);
                }
                if(balance && today && month && total) break;
              }
            }catch(e){}
            return token ? {token: token, balance: balance, today: today, month: month, total: total} : null;
          }catch(e){ return null; }
        })()
    """.trimIndent()

    // ===== DeepSeek：页面内扫描候选 token → fetch 验证 → 验证通过才采用 =====

    private fun captureDeepSeek(manual: Boolean) {
        val wv = webViewReady ?: run { if (manual) toastNoSession(); return }
        runCatching {
            wv.evaluateJavascript(
                buildStorageCaptureScript("platform.deepseek.com", "/api/v0/users/get_user_summary")
            ) { raw ->
                if (io.github.coderirse.watchdog.BuildConfig.DEBUG) {
                    android.util.Log.d(
                        "WatchDogLogin",
                        "deepseek capture raw=${raw?.take(80)} url=${wv.url}"
                    )
                }
                val obj = parseJsObject(raw)
                when {
                    // 验证通过的凭证：保存整串 Cookie（WAF 指纹）并回主界面
                    obj != null && obj.optString("token").length > 20 && obj.optBoolean("valid") -> {
                        val cookie = runCatching {
                            CookieManager.getInstance().getCookie("https://platform.deepseek.com")
                        }.getOrNull()
                        finishWithSession(obj.optString("token"), cookie)
                    }
                    // 凭证被平台 API 拒绝：页内黑名单已记录，等待用户在页面重新登录
                    obj != null && obj.optString("token").length > 20 && !obj.optBoolean("valid") -> {
                        if (manual) toastInvalidSession()
                    }
                    // 异步验证进行中（fetch 结果经 window.__wdResult 两拍中转）
                    obj?.optBoolean("pending") == true -> {
                        setStatus(getString(R.string.weblogin_status_verifying))
                    }
                    else -> {
                        if (manual) {
                            toastNoSession()
                            runDiagnostics(wv)
                        }
                    }
                }
            }
        }.onFailure { if (manual) toastNoSession() }
    }

    // ===== MiMo：CookieManager 取 api-platform_ph → **App 侧**请求验证 → 保存 =====

    /**
     * MiMo 凭证抓取。
     *
     * 修复的关键点（原实现会"登录成功却抓不到"）：
     * 1. **不再用 `WebView.url` 决定是否校验**。原代码在"url 不含平台域"时直接
     *    `finishWithSession`（注释写着"出现即视为有效"），而 OAuth 回跳瞬间
     *    `WebView.url` 往往还是账号域（甚至为空），于是把未经验证的凭证当成有效保存 ——
     *    随后 Provider 侧请求被网关 401，用户看到的就是"登录成功了但抓不到数据"。
     *    现在改为：不在平台域就**继续轮询等待**，不保存。
     * 2. **校验改到 App 侧**（[MiMoSessionVerifier]）：用与抓取数据完全相同的
     *    OkHttp 请求 + 同一份 Cookie 判断有效性。页面内 fetch 自动带该源全部 Cookie
     *    与浏览器指纹，通过并不代表 App 侧能通过，原方案是在用另一种传输方式做判断。
     * 3. 校验结果区分「明确被拒」（401/403）与「无法判定」（网络异常），后者继续轮询重试，
     *    不再把瞬时网络问题当成"凭证无效"。
     */
    private fun captureMimo(manual: Boolean) {
        val wv = webViewReady
        val cookies = runCatching {
            CookieManager.getInstance().getCookie("https://platform.xiaomimimo.com")
        }.getOrNull()
        val token = extractPhCookie(cookies)
        if (token == null || token == mimoRejectedToken) {
            if (manual) {
                toastNoSession()
                wv?.let { runDiagnostics(it) }
            }
            return
        }
        // 未回到平台域（仍在小米账号 OAuth 页）时不保存，等待回跳后 Cookie 集齐再校验。
        // 注意这里刻意"失败即等待"而不是"当作有效"：宁可不保存，也不能保存坏凭证。
        if (wv == null) {
            if (manual) toastNoSession()
            return
        }
        val onPlatformHost = wv.url.orEmpty().contains("platform.xiaomimimo.com")
        if (!onPlatformHost) {
            if (manual) {
                toastNoSession()
                runDiagnostics(wv)
            }
            return
        }
        verifyMimoSessionThenSave(token, cookies, manual, wv)
    }

    /** App 侧校验 MiMo 会话，结果决定保存 / 继续等待 / 明确报错。 */
    private fun verifyMimoSessionThenSave(
        token: String,
        cookies: String?,
        manual: Boolean,
        wv: WebView
    ) {
        if (mimoVerifying) return
        mimoVerifying = true
        val verifier = (application as WatchDogApplication).appContainer.miMoSessionVerifier
        lifecycleScope.launch {
            val result = runCatching { verifier.verify(token, cookies) }.getOrNull()
            withContext(Dispatchers.Main) {
                mimoVerifying = false
                when (result) {
                    is MiMoSessionVerifier.Result.Valid -> {
                        flog("mimo app-side verify PASS (tokenLen=${token.length} cookieLen=${cookies?.length ?: 0})")
                        finishWithSession(token, cookies)
                    }
                    is MiMoSessionVerifier.Result.Rejected -> {
                        mimoRejectedToken = token
                        flog("mimo app-side verify REJECTED (401/403), tokenLen=${token.length}")
                        if (manual) toastInvalidSession()
                    }
                    is MiMoSessionVerifier.Result.Inconclusive -> {
                        // 网络问题不等于凭证失效：不拉黑，下一轮轮询会重试
                        flog("mimo verify inconclusive: ${result.message}")
                        if (manual) setStatus("校验未完成：${result.message ?: "网络异常"}，请稍后重试")
                    }
                    else -> flog("mimo verify abnormal result")
                }
            }
        }
    }

    private fun extractPhCookie(cookies: String?): String? =
        cookies?.split(";")
            ?.map(String::trim)
            ?.firstOrNull { it.startsWith("api-platform_ph=") }
            ?.substringAfter("api-platform_ph=")
            ?.takeIf { it.length > 5 }

    /**
     * 诊断：Cookie 的**名字**清单（绝不记录值）。
     *
     * MiMo 网页端可能同时依赖多个 Cookie（不止 api-platform_ph）。
     * 若服务端对仅带 api-platform_ph 的请求返回 401，这份名字清单就是判断
     * "是否需要补带其它 Cookie"的直接依据。同时输出 platform.xiaomimimo.com 之外的
     * 域是否持有会话 Cookie（HttpOnly 的可能对 getCookie 不可见）。
     */
    private fun describeCookies(cookies: String?): String {
        if (cookies.isNullOrBlank()) return "none"
        val names = runCatching {
            cookies.split(";").mapNotNull { seg ->
                val eq = seg.trim().indexOf('=')
                if (eq > 0) seg.trim().substring(0, eq) else null
            }
        }.getOrDefault(emptyList())
        return "count=${names.size} names=[${names.joinToString(",")}]"
    }

    /**
     * 解析 evaluateJavascript 回调。
     *
     * ⚠️ 回调参数是 JS 返回值的 JSON 编码：JS 返回对象时 raw 即 '{"k":"v"}'；
     * JS 返回字符串时 raw 是两端带引号的字符串字面量（历史 bug 根源——
     * JSONObject(raw) 对其抛异常）。用 JSONTokener 同时兼容两种形态。
     */
    private fun parseJsObject(raw: String?): JSONObject? {
        if (raw.isNullOrEmpty() || raw == "null") return null
        return runCatching {
            when (val value = org.json.JSONTokener(raw).nextValue()) {
                is JSONObject -> value
                is String -> runCatching { JSONObject(value) }.getOrNull()
                else -> null
            }
        }.getOrNull()
    }

    /** 手动读取失败时输出诊断：页面域 + 三处存储的键名（不含值，脱敏）。 */
    private fun runDiagnostics(wv: WebView) {
        runCatching {
            wv.evaluateJavascript(JS_DIAG_KEYS) { raw ->
                val obj = parseJsObject(raw) ?: return@evaluateJavascript
                setStatus(getString(
                    R.string.weblogin_diag_keys,
                    obj.optString("host").ifBlank { "-" },
                    obj.optString("ls").ifBlank { "-" },
                    obj.optString("ss").ifBlank { "-" },
                    obj.optString("ck").ifBlank { "-" }
                ))
            }
        }
    }

    private fun finishWithSession(token: String, cookie: String?) {
        if (finished || isFinishing) return
        finished = true
        if (BuildConfig.DEBUG) {
            DebugLog.d(
                "WatchDogLogin",
                "session captured: token=${token.length}c cookie=${cookie?.length ?: 0}c " +
                    "platform=$loginPlatform cookies=${describeCookies(cookie)} url=${webViewReady?.url}"
            )
            if (loginPlatform == PlatformType.MIMO) {
                // 关键判断：抓到的是不是 api-platform_ph，以及它的前缀形态
                DebugLog.d(
                    "WatchDogLogin",
                    "mimo phToken=" +
                        (extractPhCookie(cookie)?.let { "len=${it.length} prefix=${it.take(6)}" }
                            ?: "NOT FOUND")
                )
            }
        }
        setStatus(getString(R.string.weblogin_status_captured))
        val app = application as WatchDogApplication
        lifecycleScope.launch(Dispatchers.IO) {
            // 诊断：saveWebSession 抛异常时 runCatching 只给出 false，看不到原因，
            // 而"登录成功但数据抓不到"往往就卡在这一步，故把异常也记下来
            val saveResult = runCatching {
                app.appContainer.webSessionStore.saveWebSession(loginPlatform, token, cookie)
            }
            DebugLog.d(
                "WatchDogLogin",
                "finishWithSession save: platform=$loginPlatform ok=${saveResult.isSuccess} " +
                    "err=${saveResult.exceptionOrNull()?.let { it::class.java.simpleName + ": " + it.message }}"
            )
            withContext(Dispatchers.Main) {
                if (saveResult.isFailure) {
                    // 存储失败（加密/磁盘异常）：如实提示，不跳转，允许用户重试
                    finished = false
                    setStatus(getString(R.string.weblogin_status_save_failed))
                    Toast.makeText(
                        this@WebLoginActivity,
                        getString(R.string.weblogin_save_failed_toast),
                        Toast.LENGTH_LONG
                    ).show()
                    return@withContext
                }
                Toast.makeText(
                    this@WebLoginActivity,
                    getString(R.string.weblogin_success_toast, loginPlatform.displayName),
                    Toast.LENGTH_SHORT
                ).show()
                // 仪表盘用 rememberLauncherForActivityResult 拉起本页并依赖 RESULT_OK 触发刷新；
                // 不回传结果码时，从仪表盘进来的用户会看到"会话已保存"但数据不刷新。
                setResult(RESULT_OK)
                // 清除登录页之上的界面（如设置页），回到主界面并导航到仪表盘
                val main = Intent(this@WebLoginActivity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(EXTRA_GOTO_DASHBOARD, true)
                }
                startActivity(main)
                finish()
            }
        }
    }

    private fun toastNoSession() {
        // 带上当前页面 host：常见原因是还停留在账号域（如小米 OAuth 页）未跳回平台域
        val host = runCatching {
            webViewReady?.url?.let { android.net.Uri.parse(it).host }
        }.getOrNull()
        val msg = if (!host.isNullOrEmpty()) {
            getString(R.string.weblogin_no_session_toast_host, host)
        } else {
            getString(R.string.weblogin_no_session_toast)
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun toastInvalidSession() {
        Toast.makeText(this, R.string.weblogin_invalid_toast, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        finished = true
        webViewReady?.apply {
            runCatching {
                stopLoading()
                clearHistory()
                (parent as? android.view.ViewGroup)?.removeView(this)
                destroy()
            }
        }
        webViewReady = null
        super.onDestroy()
    }

    /**
     * 通用控制台凭证抓取脚本（DeepSeek / Kimi 复用）：
     * - 三路扫描候选 token（localStorage / sessionStorage / document.cookie），
     *   兼容 {"value":"..."} JSON 包装与裸字符串；key 含 "token" 或值以 eyJ 开头才收裸串；
     * - 找到候选后页面内 fetch [verifyPath] 验证（页面内请求天然带 cookie 与浏览器指纹）；
     *   验证通过才返回 {token, valid:true}。
     * - 异步 fetch 结果经 window.__wdResult 两拍中转；失败 network 走 30s 退避；
     *   被 API 拒绝的 token 记入 __wdBad 黑名单。
     */
    private fun buildStorageCaptureScript(host: String, verifyPath: String): String {
        return """
        (function(){
          try{
            if (window.__wdResult) {
              var r = window.__wdResult;
              window.__wdResult = null;
              return r;
            }
            if (window.__wdBusy) return {pending: true};
            if (window.__wdWait && Date.now() < window.__wdWait) return null;
            if (location.host !== '$host') return null;

            function ok(t){ return (t && typeof t === 'string' && t.length > 20) ? t : null; }
            function pick(x, allowRaw){
              if(!x || typeof x !== 'string') return null;
              try{
                var v = JSON.parse(x);
                if(v && typeof v.value === 'string') return ok(v.value);
                if(typeof v === 'string') return ok(v);
                return null;
              }catch(e){
                return allowRaw ? ok(x) : null;
              }
            }
            function fromStorage(st){
              try{
                var t = pick(st.getItem('userToken'), true);
                if(t) return t;
                for(var i=0;i<st.length;i++){
                  var k = st.key(i), s = st.getItem(k);
                  if(!s) continue;
                  var t2 = pick(s, /token/i.test(k || '') || s.indexOf('eyJ') === 0);
                  if(t2) return t2;
                }
              }catch(e){}
              return null;
            }
            function fromCookie(){
              try{
                var parts = document.cookie.split(';');
                for(var i=0;i<parts.length;i++){
                  var seg = parts[i].trim();
                  var eq = seg.indexOf('=');
                  if(eq <= 0) continue;
                  var k = seg.substring(0, eq);
                  var v = decodeURIComponent(seg.substring(eq + 1));
                  var t = pick(v, /token/i.test(k) || v.indexOf('eyJ') === 0);
                  if(t) return t;
                }
              }catch(e){}
              return null;
            }

            if (!window.__wdBad) window.__wdBad = {};
            var token = fromStorage(localStorage) || fromStorage(sessionStorage) || fromCookie();
            if (!token || window.__wdBad[token]) return null;

            window.__wdBusy = true;
            fetch('$verifyPath', {headers: {'Authorization': 'Bearer ' + token}})
              .then(function(r){ return r.json(); })
              .then(function(j){
                window.__wdBusy = false;
                var valid = !!(j && (j.code === 0 || j.data || j.balance_infos));
                if (!valid) window.__wdBad[token] = 1;
                window.__wdResult = {token: token, valid: valid};
              })
              .catch(function(){
                window.__wdBusy = false;
                window.__wdWait = Date.now() + 30000;
              });
            return {pending: true};
          }catch(e){ return null; }
        })()
        """.trimIndent()
    }

    companion object {
        const val EXTRA_PLATFORM = "platform"

        /** 登录成功跳回 MainActivity 时携带：要求导航到仪表盘并刷新。 */
        const val EXTRA_GOTO_DASHBOARD = "goto_dashboard"

        /** 与 PlatformApiService 中 @Headers 的 UA 保持一致（WAF 指纹关联）。 */
        const val CHROME_MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 15; Pixel) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        /**
         * 界面入口（设置页 / 仪表盘重新登录按钮共用）。
         *
         * 注意：会话有效时进页面即自动抓取并返回——这是刻意行为（免二次输入账号）。
         * 早期曾让 debug 构建走"停在页面手动抓取"的诊断模式，结果挡住了真实保存路径，
         * 排查"会话没落盘"时反而看不到问题，故统一为正常模式。
         */
        fun uiIntent(context: Context, platform: PlatformType): Intent =
            intent(context, platform)

        fun intent(context: Context, platform: PlatformType): Intent =
            Intent(context, WebLoginActivity::class.java).apply {
                putExtra(EXTRA_PLATFORM, platform.name)
            }
    }

    // ===== UI =====

    /** 诊断脚本：输出页面域 + localStorage / sessionStorage / cookie 三处键名（不含值）。 */
    private val JS_DIAG_KEYS = """        (function(){
          try{
            function keysOf(st){
              var a = [];
              try{ for(var i=0;i<st.length;i++) a.push(st.key(i)); }catch(e){}
              return a.join(', ');
            }
            var ck = [];
            try{
              var parts = document.cookie.split(';');
              for(var i=0;i<parts.length;i++){
                var eq = parts[i].trim().indexOf('=');
                if(eq > 0) ck.push(parts[i].trim().substring(0, eq));
              }
            }catch(e){}
            return {host: location.host, ls: keysOf(localStorage), ss: keysOf(sessionStorage), ck: ck.join(', ')};
          }catch(e){
            return {host: location.host, ls: 'ERROR', ss: '', ck: ''};
          }
        })()
    """.trimIndent()
}
