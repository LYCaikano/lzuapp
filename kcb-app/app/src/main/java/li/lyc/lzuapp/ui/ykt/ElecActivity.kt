package li.lyc.lzuapp.ui.ykt

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.Lifecycle
import li.lyc.lzuapp.KcbApplication
import li.lyc.lzuapp.data.repo.YktRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "ElecWV"

/**
 * 白屏修复注入（已实测定位）：本机 WebView 对 `translateZ(0)`/`matrix3d` 这类
 * 强制 GPU 合成层的 transform 渲染失败——合成层内容不上屏，校付宝 H5 的 iScroll
 * 滚动容器正好用它，导致整页只剩背景。注入 MutationObserver 把内联样式里的
 * translateZ 剥掉（matrix3d 退化为等价 2D matrix），iScroll 滚动时反复重设
 * transform 也会被持续清理。stylesheet !important 压不住内联样式，只能这么干。
 */
internal val ANTI_GPU_LAYER_JS = """
    (function(){
      if (window.__lzuNoZ) return; window.__lzuNoZ = true;
      var strip = function(el){
        try {
          if (!el || !el.style || !el.style.transform) return;
          var t = el.style.transform;
          if (t.indexOf('translateZ') < 0 && t.indexOf('matrix3d') < 0) return;
          var nt = t.replace(/,?\s*translateZ\([^)]*\)/g, '');
          if (nt.indexOf('matrix3d') >= 0) {
            nt = nt.replace(/matrix3d\(([^)]*)\)/g, function(m, g){
              var p = g.split(',');
              if (p.length !== 16) return 'none';
              return 'matrix(' + [p[0], p[1], p[4], p[5], p[12], p[13]].join(',') + ')';
            });
          }
          el.style.transform = nt;
        } catch (e) {}
      };
      try {
        new MutationObserver(function(ms){
          for (var i = 0; i < ms.length; i++) strip(ms[i].target);
        }).observe(document.documentElement, {attributes: true, attributeFilter: ['style'], subtree: true});
      } catch (e) {}
      var all = document.querySelectorAll('[style]');
      for (var i = 0; i < all.length; i++) strip(all[i]);
    })();
""".trimIndent()

data class ElecUiState(
    /** 正在静默校验登录态 + 签 ST */
    val loading: Boolean = true,
    /** 入口准备失败（致命，整页错误 + 重试） */
    val error: String? = null,
    /** 校付宝入口 URL（含一次性 ST） */
    val url: String? = null,
    /** WebView 主框架加载失败（页内错误覆盖层，可重试：重新签 ST 加载） */
    val pageError: String? = null,
)

class ElecViewModelFactory(private val repo: YktRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return ElecViewModel(repo) as T
    }
}

class ElecViewModel(private val repo: YktRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(ElecUiState())
    val uiState: StateFlow<ElecUiState> = _uiState.asStateFlow()

    private var loadJob: kotlinx.coroutines.Job? = null

    /**
     * 进入页面：已签好 URL 则不重签（ST 一次性，且校付宝会话靠 WebView Cookie 维持，
     * 二次进页原 URL 仍可用）；否则静默校验登录态并签 ST 组入口 URL。
     */
    fun enter() {
        val s = _uiState.value
        if (s.url != null) {
            _uiState.value = s.copy(loading = false, error = null, pageError = null)
            return
        }
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            _uiState.value = ElecUiState(loading = true)
            when (val r = repo.elecPortalUrl()) {
                is YktRepository.YktResult.Ok ->
                    _uiState.value = ElecUiState(loading = false, url = r.data)
                is YktRepository.YktResult.Err ->
                    _uiState.value = ElecUiState(loading = false, error = r.message)
            }
        }
    }

    /** 页面加载失败重试：清掉旧 URL 重新签 ST（旧 ST 可能已被消费） */
    fun retry() {
        _uiState.value = ElecUiState(loading = true)
        enter()
    }

    fun onPageError(msg: String) {
        _uiState.value = _uiState.value.copy(pageError = msg)
    }
}

/**
 * 宿舍电费页：内嵌校付宝官方 H5（与官方 App 同一入口、同一会话机制）。
 * 官方的「5.6.7.8.14研究生及本科26.27.28公寓」「其他楼栋」两个板块都在该页内。
 *
 * 注意：本页特意用**纯 View 的独立 Activity**（而非 Compose AndroidView）——
 * 实测本机（OPlus CPH2723 + Chrome 153 WebView）Compose 内嵌的 WebView 加载
 * 远程页面时合成器一帧都不上屏（邮箱那种 loadData 静态页除外），同一进程里
 * 纯 View 树加载同 URL 完全正常；官方 App 也是独立 H5Activity，同理。
 * 首次使用页面会自动跳统一身份认证登录一次，之后凭 WebView Cookie 静默。
 */
class ElecActivity : ComponentActivity() {

    private val vm: ElecViewModel by viewModels {
        ElecViewModelFactory((application as KcbApplication).container.yktRepository)
    }
    private val authStore by lazy { (application as KcbApplication).container.authStore }

    /** 跟随 App 内主题设置（设置-深色模式），而非系统 uiMode */
    private var night: Boolean = false

    private var webView: WebView? = null
    private lateinit var webContainer: FrameLayout
    private lateinit var loadingView: LinearLayout
    private lateinit var errorView: LinearLayout
    private lateinit var errorText: TextView
    private lateinit var pageErrorView: LinearLayout
    private lateinit var pageErrorText: TextView
    private var loadedUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // AuthStore 是进程级单例且 MainActivity 已读过，first() 走内存缓存立即可得
        val mode = li.lyc.lzuapp.theme.ThemeMode.fromStorage(
            kotlinx.coroutines.runBlocking { authStore.current().themeMode },
        )
        night = when (mode) {
            li.lyc.lzuapp.theme.ThemeMode.SYSTEM ->
                resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
            li.lyc.lzuapp.theme.ThemeMode.LIGHT -> false
            li.lyc.lzuapp.theme.ThemeMode.DARK -> true
        }
        buildContent()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = webView
                if (pageErrorView.visibility != View.VISIBLE && wv?.canGoBack() == true) {
                    wv.goBack()
                } else {
                    finish()
                }
            }
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.uiState.collect { render(it) }
            }
        }
        vm.enter()
    }

    override fun onDestroy() {
        webView?.let { wv ->
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.stopLoading()
            wv.loadUrl("about:blank")
            wv.destroy()
        }
        webView = null
        super.onDestroy()
    }

    // ---------- UI ----------

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics,
    ).toInt()

    private fun buildContent() {
        // 主题色：跟随系统深浅色（App 内自定义主题色不影响 H5 容器页，用中性色）
        val bg = if (night) 0xFF0F1419.toInt() else 0xFFF8FAFC.toInt()
        val textColor = if (night) 0xFFE6E8EB.toInt() else 0xFF1A1A1A.toInt()
        val subTextColor = if (night) 0xFF9AA0A6.toInt() else 0xFF5F6368.toInt()

        val root = FrameLayout(this)

        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // 顶栏：返回 + 标题（与 Compose 页的观感对齐）
        val topBar = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56))
            setBackgroundColor(bg)
        }
        val backBtn = ImageButton(this).apply {
            setImageResource(li.lyc.lzuapp.R.drawable.ic_arrow_back_24)
            setColorFilter(textColor)
            contentDescription = "返回"
            background = null
            layoutParams = FrameLayout.LayoutParams(dp(48), dp(48), Gravity.START or Gravity.CENTER_VERTICAL)
            setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        val title = TextView(this).apply {
            text = "宿舍电费"
            textSize = 20f
            setTextColor(textColor)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL,
            ).apply { marginStart = dp(56) }
        }
        topBar.addView(backBtn)
        topBar.addView(title)
        column.addView(topBar)

        val webContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        this.webContainer = webContainer
        column.addView(webContainer)
        root.addView(column)

        // 加载中覆盖层
        loadingView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(bg)
            val pb = ProgressBar(this@ElecActivity)
            addView(pb, LinearLayout.LayoutParams(dp(32), dp(32)))
            val tv = TextView(this@ElecActivity).apply {
                text = "正在校验登录状态…"
                textSize = 13f
                setTextColor(subTextColor)
            }
            addView(tv, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) })
        }
        root.addView(loadingView, FrameLayout.LayoutParams(-1, -1))

        // 致命错误覆盖层（登录态失效等）
        errorText = TextView(this)
        errorView = makeErrorOverlay(errorText, bg) { vm.retry() }
        root.addView(errorView, FrameLayout.LayoutParams(-1, -1))

        // 页内错误覆盖层（主框架加载失败；WebView 保留在背景）
        pageErrorText = TextView(this)
        pageErrorView = makeErrorOverlay(pageErrorText, bg) { vm.retry() }
        root.addView(pageErrorView, FrameLayout.LayoutParams(-1, -1))

        setContentView(root)

        // 状态栏/导航栏 insets（targetSdk 36 强制 edge-to-edge）+ 系统栏图标深浅
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowInsetsControllerCompat(window, root).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
        ViewCompat.setOnApplyWindowInsetsListener(topBar) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            v.setPadding(0, bars.top, 0, 0)
            v.layoutParams = v.layoutParams.apply { height = dp(56) + bars.top }
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(0, 0, 0, bars.bottom)
            insets
        }

        loadingView.visibility = View.VISIBLE
        errorView.visibility = View.GONE
        pageErrorView.visibility = View.GONE
    }

    private fun makeErrorOverlay(tv: TextView, bg: Int, onRetry: () -> Unit): LinearLayout {
        val textColor = if (night) 0xFFE6E8EB.toInt() else 0xFF1A1A1A.toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(bg)
            tv.setTextColor(textColor)
            tv.gravity = Gravity.CENTER
            addView(tv, LinearLayout.LayoutParams(-2, -2))
            val btn = Button(this@ElecActivity).apply {
                text = "重试"
                setOnClickListener { onRetry() }
            }
            addView(btn, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
        }
    }

    private fun render(state: ElecUiState) {
        loadingView.visibility = if (state.loading) View.VISIBLE else View.GONE
        if (state.error != null) {
            errorText.text = state.error
            errorView.visibility = View.VISIBLE
        } else {
            errorView.visibility = View.GONE
        }
        if (state.pageError != null) {
            pageErrorText.text = state.pageError
            pageErrorView.visibility = View.VISIBLE
        } else {
            pageErrorView.visibility = View.GONE
        }
        val url = state.url
        if (url != null && url != loadedUrl) {
            loadedUrl = url
            ensureWebView(url)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(url: String) {
        webView?.let { wv ->
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.destroy()
        }
        val wv = WebView(this).apply {
            // 对齐官方 H5Activity（com.hzsun.utility.t0.f + H5Activity 初始化）：
            // 官方配置在该手机上已验证可完整跑通校付宝链路，逐项照搬
            settings.apply {
                @Suppress("DEPRECATION")
                savePassword = false
                javaScriptEnabled = true
                javaScriptCanOpenWindowsAutomatically = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                useWideViewPort = true
                loadWithOverviewMode = true
                domStorageEnabled = true
                allowFileAccess = true
                allowFileAccessFromFileURLs = false
                allowUniversalAccessFromFileURLs = false
                cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                @Suppress("DEPRECATION")
                databaseEnabled = true
                // 官方 UA 后缀：校付宝/瑞数可能按 UA 判别宿主环境
                userAgentString = userAgentString + " lzdx_ua JHZF_LZDXAPP"
            }
            clearCache(true)
            clearFormData()
            clearHistory()
            // 校付宝会话 + CAS 登录链都靠 Cookie；跨域重定向链需要第三方 Cookie
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(this, true)
            webChromeClient = object : android.webkit.WebChromeClient() {
                // 校付宝/瑞数的 JS 报错、未捕获异常会以 console ERROR 走这里；INFO/LOG 太吵不收
                override fun onConsoleMessage(msg: android.webkit.ConsoleMessage): Boolean {
                    if (msg.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.WARNING ||
                        msg.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR
                    ) {
                        val src = msg.sourceId()?.substringAfterLast('/')
                        Log.w(TAG, "console[${msg.messageLevel()}] ${msg.message()} (@$src:${msg.lineNumber()})")
                    }
                    return true
                }

                override fun onReceivedTitle(view: WebView, title: String?) {
                    Log.d(TAG, "onReceivedTitle: $title url=${view.url}")
                }
            }
            webViewClient = object : WebViewClient() {
                // 瑞数挑战死循环检测：按 host+path（去 query）计主框架连续 4xx。
                // 挑战页 reload 常换时间戳参数，按完整 URL 计数会被 onPageStarted 清零而永不触发
                private var lastHttpErrorKey: String? = null
                private var httpErrorCount = 0

                private fun hostPath(url: String?): String? = url?.let {
                    runCatching {
                        val u = android.net.Uri.parse(it)
                        "${u.host.orEmpty()}${u.path.orEmpty()}"
                    }.getOrNull()
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView, request: WebResourceRequest,
                ): Boolean {
                    val u = request.url
                    val str = u.toString()
                    // 对齐官方 u2.shouldOverrideUrlLoading：支付/唤起类 scheme 跳外部 App
                    if (str.startsWith("weixin://") || str.startsWith("alipays://") ||
                        str.startsWith("pay.xiaofubao.com://")
                    ) {
                        Log.i(TAG, "shouldOverride→外部App: $str")
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, u)) }
                        return true
                    }
                    // 微信 MWEB 收银台：必须带校付宝 Referer，否则微信侧直接报错页
                    if (str.startsWith(WX_MWEB_PREFIX)) {
                        Log.i(TAG, "shouldOverride→MWEB带Referer: $str")
                        view.loadUrl(str, WX_MWEB_HEADERS)
                        return true
                    }
                    // 电话拨号盘
                    if (u.scheme == "tel") {
                        Log.i(TAG, "shouldOverride→拨号: $str")
                        runCatching { startActivity(Intent(Intent.ACTION_DIAL, u)) }
                        return true
                    }
                    // 其余非 http(s) scheme（intent:// 等）：外部兜底打开，防 ERR_UNKNOWN_URL_SCHEME 白屏
                    if (u.scheme != "http" && u.scheme != "https") {
                        Log.w(TAG, "shouldOverride→非http(s)外部兜底: $str")
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, u)) }
                        return true
                    }
                    // http/https 一律在 WebView 内加载（官方无域名白名单；
                    // 自建白名单 + 非 https 劫持外部打开会导致中间跳被吞后白屏）
                    Log.d(TAG, "shouldOverride→页内加载: $str")
                    return false
                }

                override fun shouldInterceptRequest(
                    view: WebView, request: WebResourceRequest,
                ): WebResourceResponse? {
                    // res.wx.qq.com 的 jweixin 在本机网络下连接挂死（20s+ 才超时），阻塞整页渲染；
                    // 它只在微信环境内有用（QHIT_EDU 平台 executeWxJssdkConfig 直接 return），置空安全
                    if (request.url.host == "res.wx.qq.com" &&
                        request.url.path.orEmpty().startsWith("/open/js/jweixin")
                    ) {
                        Log.d(TAG, "拦截 jweixin: ${request.url}")
                        return WebResourceResponse(
                            "text/javascript", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)),
                        )
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageStarted(
                    view: WebView, url: String?, favicon: android.graphics.Bitmap?,
                ) {
                    Log.d(TAG, "onPageStarted: $url")
                    // 主框架换了页面（host+path 不同）才清零；同页 reload 换 query 参数不清零
                    if (hostPath(url) != lastHttpErrorKey) {
                        lastHttpErrorKey = null
                        httpErrorCount = 0
                    }
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    Log.d(TAG, "onPageFinished: $url title=${view.title}")
                    // 剥离 GPU 合成层 transform，否则本机白屏（见 ANTI_GPU_LAYER_JS 注释）
                    view.evaluateJavascript(ANTI_GPU_LAYER_JS, null)
                }

                override fun onReceivedError(
                    view: WebView, request: WebResourceRequest, error: WebResourceError,
                ) {
                    Log.w(TAG, "onReceivedError main=${request.isForMainFrame} code=${error.errorCode} desc=${error.description} url=${request.url}")
                    if (request.isForMainFrame) {
                        vm.onPageError("页面加载失败，请检查网络后重试")
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView, request: WebResourceRequest,
                    errorResponse: android.webkit.WebResourceResponse,
                ) {
                    val code = errorResponse.statusCode
                    Log.w(TAG, "onReceivedHttpError main=${request.isForMainFrame} status=$code url=${request.url}")
                    // 瑞数防护挑战（412 等 4xx）正常每页只出现一次，页面 JS 设 cookie 后
                    // 自动 reload 即恢复；同一页面（host+path）连续 ≥3 次 4xx = 挑战死循环，
                    // 页面只剩永久白屏，须弹错误覆盖层给用户重试出口
                    if (!request.isForMainFrame) return
                    if (code in 400..499) {
                        val key = hostPath(request.url.toString())
                        if (key == lastHttpErrorKey) {
                            httpErrorCount++
                        } else {
                            lastHttpErrorKey = key
                            httpErrorCount = 1
                        }
                        if (httpErrorCount >= 3) {
                            httpErrorCount = 0
                            Log.e(TAG, "主框架 4xx 死循环: $key")
                            vm.onPageError("页面防护校验未通过，请重试")
                        }
                    }
                }

                override fun onReceivedSslError(
                    view: WebView,
                    handler: android.webkit.SslErrorHandler,
                    error: android.net.http.SslError,
                ) {
                    // 官方 u2 对 SSL 错误直接 proceed（瑞数防护会换证书链中间跳）
                    Log.w(TAG, "onReceivedSslError→proceed: ${error.url} primary=${error.primaryError}")
                    handler.proceed()
                }
            }
            Log.i(TAG, "WebView created UA=${settings.userAgentString}")
        }
        webContainer.addView(wv, FrameLayout.LayoutParams(-1, -1))
        webView = wv
        Log.i(TAG, "loadUrl: $url")
        wv.loadUrl(url)
    }
}
