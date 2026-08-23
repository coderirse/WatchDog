package com.example.watchdog.ui.weblogin

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.example.watchdog.MainActivity
import com.example.watchdog.R
import com.example.watchdog.WatchDogApplication
import com.example.watchdog.data.model.PlatformType
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

    /** WebView 就绪状态：null → 显示加载中，创建完成后重组挂载。 */
    private var webViewReady by mutableStateOf<WebView?>(null)
    private var statusText by mutableStateOf("")

    /** 已被平台 API 拒绝的 MiMo Cookie 值（避免轮询反复用失效凭证打接口）。 */
    private var mimoRejectedToken: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loginPlatform = intent.getStringExtra(EXTRA_PLATFORM)
            ?.let { runCatching { PlatformType.valueOf(it) }.getOrNull() }
            ?: PlatformType.MIMO

        statusText = getString(R.string.weblogin_status_loading, loginPlatform.displayName)

        setContent {
            MaterialTheme {
                WebLoginContent(
                    platform = loginPlatform,
                    status = statusText,
                    onManualCapture = { tryCapture(manual = true) },
                    onCancel = { finish() }
                )
            }
        }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = webViewReady
                if (wv?.canGoBack() == true) wv.goBack() else finish()
            }
        })

        createWebView()
        startCapturePolling()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() {
        val platform = loginPlatform
        val startUrl = when (platform) {
            PlatformType.MIMO -> "https://platform.xiaomimimo.com/"
            PlatformType.DEEPSEEK -> "https://platform.deepseek.com/"
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
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                // SPA 登录成功后可能不发生导航（原地写入 token），轮询兜底；这里再加一拍
                tryCapture(manual = false)
            }
        }
        webView.loadUrl(startUrl)
        webViewReady = webView
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
            else -> Unit
        }
    }

    // ===== DeepSeek：页面内扫描候选 token → fetch 验证 → 验证通过才采用 =====

    private fun captureDeepSeek(manual: Boolean) {
        val wv = webViewReady ?: run { if (manual) toastNoSession(); return }
        runCatching {
            wv.evaluateJavascript(JS_DEEPSEEK_CAPTURE) { raw ->
                android.util.Log.d(
                    "WatchDogLogin",
                    "deepseek capture raw=${raw?.take(80)} url=${wv.url}"
                )
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
                        statusText = getString(R.string.weblogin_status_verifying)
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

    // ===== MiMo：CookieManager 取 api-platform_ph（HttpOnly 可见）→ 页面内 fetch 验证 =====

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
        // 页面不在平台域（仍在小米账号 OAuth 页）时无法发起同源验证：
        // api-platform_ph 仅在登录回跳后由平台设置，出现即视为有效，直接采用
        val onPlatformHost = wv?.url.orEmpty().contains("platform.xiaomimimo.com")
        if (wv == null || !onPlatformHost) {
            finishWithSession(token, cookies)
            return
        }
        runCatching {
            wv.evaluateJavascript(mimoVerifyScript(token)) { raw ->
                val obj = parseJsObject(raw)
                when {
                    obj?.optBoolean("valid") == true -> finishWithSession(token, cookies)
                    obj != null && obj.has("valid") && !obj.optBoolean("valid") -> {
                        mimoRejectedToken = token
                        if (manual) toastInvalidSession()
                    }
                    obj?.optBoolean("pending") == true -> {
                        statusText = getString(R.string.weblogin_status_verifying)
                    }
                    else -> if (manual) {
                        toastNoSession()
                        runDiagnostics(wv)
                    }
                }
            }
        }.onFailure { if (manual) toastNoSession() }
    }

    private fun extractPhCookie(cookies: String?): String? =
        cookies?.split(";")
            ?.map(String::trim)
            ?.firstOrNull { it.startsWith("api-platform_ph=") }
            ?.substringAfter("api-platform_ph=")
            ?.takeIf { it.length > 5 }

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
                statusText = getString(
                    R.string.weblogin_diag_keys,
                    obj.optString("host").ifBlank { "-" },
                    obj.optString("ls").ifBlank { "-" },
                    obj.optString("ss").ifBlank { "-" },
                    obj.optString("ck").ifBlank { "-" }
                )
            }
        }
    }

    private fun finishWithSession(token: String, cookie: String?) {
        if (finished || isFinishing) return
        finished = true
        android.util.Log.d(
            "WatchDogLogin",
            "session captured: token=${token.length}c cookie=${cookie?.length ?: 0}c platform=$loginPlatform"
        )
        statusText = getString(R.string.weblogin_status_captured)
        val app = application as WatchDogApplication
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { app.appContainer.webSessionStore.saveWebSession(loginPlatform, token, cookie) }
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@WebLoginActivity,
                    getString(R.string.weblogin_success_toast, loginPlatform.displayName),
                    Toast.LENGTH_SHORT
                ).show()
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

    companion object {
        const val EXTRA_PLATFORM = "platform"

        /** 登录成功跳回 MainActivity 时携带：要求导航到仪表盘并刷新。 */
        const val EXTRA_GOTO_DASHBOARD = "goto_dashboard"

        /** 与 PlatformApiService 中 @Headers 的 UA 保持一致（WAF 指纹关联）。 */
        const val CHROME_MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 15; Pixel) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        fun intent(context: Context, platform: PlatformType): Intent =
            Intent(context, WebLoginActivity::class.java).apply {
                putExtra(EXTRA_PLATFORM, platform.name)
            }
    }

    // ===== UI =====

    @Composable
    private fun WebLoginContent(
        platform: PlatformType,
        status: String,
        onManualCapture: () -> Unit,
        onCancel: () -> Unit
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // edge-to-edge 下避开系统栏；软键盘弹出时整体收缩（配合 adjustResize），
                // 避免 WebView 被键盘挤压/页面跳动错位
                .statusBarsPadding()
                .imePadding()
                .navigationBarsPadding()
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(
                    text = stringResource(R.string.weblogin_title, platform.displayName),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.weblogin_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (status.isNotBlank()) {
                    Text(
                        text = status,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val wv = webViewReady
                if (wv != null) {
                    AndroidView(factory = { wv }, modifier = Modifier.fillMaxSize())
                } else {
                    CircularProgressIndicator()
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.action_cancel)) }
                Button(
                    onClick = onManualCapture,
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.weblogin_done)) }
            }
        }
    }

    // ===== 抓取脚本（自研，无参考工程依赖）=====

    /**
     * DeepSeek 凭证抓取（每拍执行一次，幂等状态机）：
     * - 有已完成的验证结果（window.__wdResult）→ 消费并返回 {token, valid}；
     * - 退避期（网络异常后 30s 内）→ 返回 null 不发请求；
     * - 三路扫描候选 token（localStorage / sessionStorage / document.cookie），
     *   兼容 {"value":"..."} JSON 包装与裸字符串；key 含 "token" 或值 eyJ 开头才收裸串；
     * - 找到未被拉黑的新候选 → 页面内 fetch get_user_summary 验证（异步，Promise 不可
     *   直接回传，结果写 __wdResult 由下一拍读取），本拍返回 {pending:true}；
     * - 验证失败（code!=0）→ token 记入页内黑名单 __wdBad，本拍返回 {token, valid:false}。
     */
    private val JS_DEEPSEEK_CAPTURE = """
        (function(){
          try{
            if (window.__wdResult) {
              var r = window.__wdResult;
              window.__wdResult = null;
              return r;
            }
            if (window.__wdBusy) return {pending: true};
            if (window.__wdWait && Date.now() < window.__wdWait) return null;
            if (location.host !== 'platform.deepseek.com') return null;

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
            fetch('/api/v0/users/get_user_summary', {headers: {'Authorization': 'Bearer ' + token}})
              .then(function(r){ return r.json(); })
              .then(function(j){
                window.__wdBusy = false;
                var valid = !!(j && (j.code === 0 || j.data));
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

    /**
     * MiMo 凭证验证（Cookie 值由 Kotlin 侧从 CookieManager 取得后注入）：
     * 页面内 fetch tokenPlan/detail 验证（status 200 即有效），结果经 __wdResult 两拍中转。
     */
    private fun mimoVerifyScript(token: String): String {
        val t = JSONObject.quote(token)
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
            if (location.host !== 'platform.xiaomimimo.com') return null;
            window.__wdBusy = true;
            fetch('/api/v1/tokenPlan/detail', {headers: {'api-platform_ph': $t}})
              .then(function(r){
                window.__wdBusy = false;
                window.__wdResult = {valid: r.status === 200};
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

    /** 诊断脚本：输出页面域 + localStorage / sessionStorage / cookie 三处键名（不含值）。 */
    private val JS_DIAG_KEYS = """
        (function(){
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
