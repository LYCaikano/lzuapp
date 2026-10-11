package li.lyc.lzuapp.ui.ykt

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import li.lyc.lzuapp.KcbApplication
import li.lyc.lzuapp.data.repo.YktRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 收银台（校园卡充值/缴费支付页）：纯 View 独立 Activity。
 * 与 ElecActivity 同因：Compose 内嵌 WebView 加载远程页面在本机不上屏（白屏）。
 * 逻辑与原 RechargeWebScreen 一致：拦截回调地址取 out_trade_no 后轮询查单，
 * 结果覆盖层提示成功/失败。
 */
@SuppressLint("SetJavaScriptEnabled")
class CashierActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URL = "cashier_url"
        private const val TAG = "CashierWV"
    }

    private sealed interface State {
        data object Loading : State
        data object Polling : State
        data class Success(val info: String) : State
        data class Failed(val message: String) : State
    }

    private val repo by lazy { (application as KcbApplication).container.yktRepository }

    /** 跟随 App 内主题设置（我的-深色模式），而非系统 uiMode */
    private var night: Boolean = false

    private var webView: WebView? = null
    private lateinit var webContainer: FrameLayout
    private lateinit var overlay: LinearLayout
    private lateinit var overlayProgress: ProgressBar
    private lateinit var overlayText: TextView
    private lateinit var overlayButton: Button

    private var state: State = State.Loading
    private var pollJob: Job? = null
    private var expectedOrderNum: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrEmpty()) {
            finish()
            return
        }
        expectedOrderNum = android.net.Uri.parse(url).getQueryParameter("out_trade_no")
        // AuthStore 是进程级单例且 MainActivity 已读过，first() 走内存缓存立即可得
        val mode = li.lyc.lzuapp.theme.ThemeMode.fromStorage(
            kotlinx.coroutines.runBlocking {
                (application as KcbApplication).container.authStore.current().themeMode
            },
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
                if (state is State.Loading && wv?.canGoBack() == true) wv.goBack() else finish()
            }
        })
        loadCashier(url)
    }

    override fun onDestroy() {
        pollJob?.cancel()
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
        val night = night
        val bg = if (night) 0xFF0F1419.toInt() else 0xFFF8FAFC.toInt()
        val textColor = if (night) 0xFFE6E8EB.toInt() else 0xFF1A1A1A.toInt()

        val root = FrameLayout(this)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

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
            text = "收银台"
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

        webContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        column.addView(webContainer)
        root.addView(column)

        // 结果覆盖层（轮询中/成功/失败）
        overlayText = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(textColor)
        }
        overlayProgress = ProgressBar(this)
        overlayButton = Button(this).apply {
            setOnClickListener { finish() }
        }
        overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(bg)
            visibility = View.GONE
            addView(overlayProgress, LinearLayout.LayoutParams(dp(32), dp(32)))
            addView(overlayText, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
            addView(overlayButton, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
        }
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))

        setContentView(root)

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
    }

    private fun render() {
        val s = state
        when (s) {
            is State.Loading -> overlay.visibility = View.GONE
            is State.Polling -> {
                overlayText.text = "正在确认支付结果…"
                overlayProgress.visibility = View.VISIBLE
                overlayButton.visibility = View.GONE
                overlay.visibility = View.VISIBLE
            }
            is State.Success -> {
                overlayText.text = s.info
                overlayProgress.visibility = View.GONE
                overlayButton.text = "完成"
                overlayButton.visibility = View.VISIBLE
                overlay.visibility = View.VISIBLE
            }
            is State.Failed -> {
                overlayText.text = s.message
                overlayProgress.visibility = View.GONE
                overlayButton.text = "返回"
                overlayButton.visibility = View.VISIBLE
                overlay.visibility = View.VISIBLE
            }
        }
    }

    // ---------- 查单轮询（与原 RechargeWebScreen 一致） ----------

    private fun pollOrder(orderNum: String) {
        // 与本次下单订单号比对，不一致不采信（防把别的订单结果套到本单）；
        // cashierUrl 未带订单号时无法比对，按原样采信
        if (expectedOrderNum != null && orderNum != expectedOrderNum) return
        pollJob?.cancel()
        state = State.Polling
        render()
        pollJob = lifecycleScope.launch {
            repeat(30) {
                when (val r = repo.rechargeResult(orderNum)) {
                    is YktRepository.YktResult.Ok -> when (r.data.field("Status")) {
                        "2" -> {
                            val money = r.data.field("WalletMoney")
                            state = State.Success(
                                if (money != null) "充值成功，当前余额 $money 元" else "充值成功",
                            )
                            render()
                            return@launch
                        }
                        "3" -> {
                            state = State.Failed("充值失败或已取消")
                            render()
                            return@launch
                        }
                    }
                    is YktRepository.YktResult.Err -> { /* 轮询继续 */ }
                }
                delay(1000)
            }
            state = State.Failed("暂未查询到支付结果，请稍后在账单中确认")
            render()
        }
    }

    // ---------- WebView ----------

    private fun loadCashier(url: String) {
        // 收银台地址来自下单响应：仅允许白名单域，防任意 URL 在 JS 开启的 WebView 里加载
        if (!isAllowedCashierUrl(android.net.Uri.parse(url))) {
            Toast.makeText(this, "收银台地址异常", Toast.LENGTH_SHORT).show()
            state = State.Failed("收银台地址异常，请返回重试")
            render()
            return
        }
        val wv = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView, request: WebResourceRequest,
                ): Boolean {
                    val u = request.url
                    // 拦截充值回调：取 out_trade_no 查单
                    if (u.toString().startsWith(RECHARGE_CALLBACK)) {
                        val orderNum = u.getQueryParameter("out_trade_no")
                        if (!orderNum.isNullOrEmpty()) pollOrder(orderNum)
                        else {
                            state = State.Failed("未能获取支付订单号")
                            render()
                        }
                        return true
                    }
                    // 微信/支付宝 scheme 尝试跳起对应 App
                    if (u.scheme == "weixin" || u.scheme == "alipays") {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, u)) }
                        return true
                    }
                    // 其它非 http(s) scheme（intent://、tel: 等）：外部兜底打开，
                    // 防 ERR_UNKNOWN_URL_SCHEME 白屏
                    if (u.scheme != "http" && u.scheme != "https") {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, u)) }
                        return true
                    }
                    // 微信 MWEB 收银台：必须带校付宝 Referer 加载，否则微信侧直接报错页
                    if (u.toString().startsWith(WX_MWEB_PREFIX)) {
                        view.loadUrl(u.toString(), WX_MWEB_HEADERS)
                        return true
                    }
                    // 非白名单 host 一律拦截（JS 开启的 WebView 不放行任意 URL）
                    if (!isAllowedCashierUrl(u)) {
                        Toast.makeText(this@CashierActivity, "已拦截非支付相关页面", Toast.LENGTH_SHORT).show()
                        return true
                    }
                    return false
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    // 部分收银台直接 302 到回调地址
                    if (url?.startsWith(RECHARGE_CALLBACK) == true) {
                        val orderNum = android.net.Uri.parse(url).getQueryParameter("out_trade_no")
                        if (!orderNum.isNullOrEmpty()) pollOrder(orderNum)
                    }
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    // 校付宝系页面 translateZ 合成层在本机渲染失败（见 ElecActivity 注释）
                    view.evaluateJavascript(ANTI_GPU_LAYER_JS, null)
                }
            }
        }
        webContainer.addView(wv, FrameLayout.LayoutParams(-1, -1))
        webView = wv
        // 下单直接返回微信 MWEB 地址时，首次加载同样要带 Referer
        if (url.startsWith(WX_MWEB_PREFIX)) {
            wv.loadUrl(url, WX_MWEB_HEADERS)
        } else {
            wv.loadUrl(url)
        }
        Log.i(TAG, "cashier loaded: ${url.take(80)}")
    }
}
