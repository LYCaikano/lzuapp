package li.lyc.lzuapp.ui.ykt

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import li.lyc.lzuapp.data.repo.YktChannel
import li.lyc.lzuapp.data.repo.YktRepository
import li.lyc.lzuapp.data.repo.YktWallet
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 充值回调占位地址（WebView 拦截，不会真实加载） */
const val RECHARGE_CALLBACK = "https://kcb.local/rechargeResult"

/**
 * 收银台 URL 白名单（下单响应的 cashierUrl 与 WebView 内跳转共用）：
 * - 官方域：http 仅放行 app.lzu.edu.cn（network_security_config.xml 只对它开了 cleartext，
 *   其他 http 子域会被系统层拦截白屏）；lzu.edu.cn 及其余子域要求 https
 * - 支付平台域（校付宝收银台网关 / 支付宝 / 微信支付相关）：仅 https
 *   xiaofubao.com = 下单接口实际返回的第一跳收银台域（对齐原 app H5Activity）
 * 其余一律拒绝（拦截并提示）。
 */
internal fun isAllowedCashierUrl(uri: android.net.Uri): Boolean {
    val scheme = uri.scheme?.lowercase() ?: return false
    if (scheme != "http" && scheme != "https") return false
    val host = uri.host?.lowercase() ?: return false
    val official = if (scheme == "http") {
        host == "app.lzu.edu.cn"
    } else {
        host == "lzu.edu.cn" || host.endsWith(".lzu.edu.cn")
    }
    val payPlatform = listOf(
        "xiaofubao.com", "alipay.com", "alipayobjects.com", "tenpay.com", "weixin.qq.com",
    ).any { host == it || host.endsWith(".$it") }
    return official || (payPlatform && scheme == "https")
}

/**
 * 微信 MWEB 收银台前缀：微信侧强制校验 Referer，须以校付宝域为 Referer 加载。
 * 对齐官方 H5Activity.u2：HashMap 两次 put 同键，生效值为 paygray。
 */
internal const val WX_MWEB_PREFIX = "https://wx.tenpay.com/cgi-bin/mmpayweb-bin/checkmweb"
internal val WX_MWEB_HEADERS = mapOf("Referer" to "https://paygray.xiaofubao.com")

data class RechargeUiState(
    val loading: Boolean = true,
    val wallet: YktWallet? = null,
    val channels: List<YktChannel> = emptyList(),
    val selectedChannel: YktChannel? = null,
    val amount: String = "",
    val submitting: Boolean = false,
    val error: String? = null,
    /** 下单成功后的收银台地址（非空 → 跳转 WebView） */
    val cashierUrl: String? = null,
)

class RechargeViewModelFactory(private val repo: YktRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return RechargeViewModel(repo) as T
    }
}

class RechargeViewModel(private val repo: YktRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(RechargeUiState())
    val uiState: StateFlow<RechargeUiState> = _uiState.asStateFlow()

    private var lastActionAt = 0L

    init {
        // 进页先静默校验登录态（仓库层 withYktRetry 内建），再加载钱包与渠道
        viewModelScope.launch {
            var wallet: YktWallet? = null
            var channels: List<YktChannel> = emptyList()
            var error: String? = null
            when (val r = repo.walletMoney()) {
                is YktRepository.YktResult.Ok -> wallet = r.data.firstOrNull()
                is YktRepository.YktResult.Err -> error = r.message
            }
            if (error == null) {
                when (val r = repo.rechargeChannels()) {
                    is YktRepository.YktResult.Ok -> channels = r.data
                    is YktRepository.YktResult.Err -> error = r.message
                }
            }
            _uiState.value = _uiState.value.copy(
                loading = false, wallet = wallet, channels = channels,
                selectedChannel = channels.firstOrNull(), error = error,
            )
        }
    }

    /** 静默重拉钱包余额（页面每次进入组合时调用：充值返回后余额即时更新） */
    fun refreshWallet() {
        if (_uiState.value.loading) return
        viewModelScope.launch {
            if (repo.checkLoginState() is YktRepository.YktResult.Err) return@launch
            when (val r = repo.walletMoney()) {
                is YktRepository.YktResult.Ok ->
                    _uiState.value = _uiState.value.copy(wallet = r.data.firstOrNull())
                is YktRepository.YktResult.Err -> { /* 静默失败，保留旧值 */ }
            }
        }
    }

    /**
     * 进入页面时重置上次残留的金额/错误/提交态（VM 是 Activity 作用域，二次进页复用同一实例）。
     * 不清钱包/渠道（避免二次进页重新 loading）。
     */
    fun reset() {
        _uiState.value = _uiState.value.copy(
            amount = "", error = null, submitting = false, cashierUrl = null,
        )
    }

    fun onAmountChange(v: String) {
        // 只允许数字与一个小数点，最多两位小数
        val filtered = v.filter { it.isDigit() || it == '.' }
        val dot = filtered.indexOf('.')
        val normalized = if (dot >= 0) {
            filtered.substring(0, dot + 1) + filtered.substring(dot + 1).replace(".", "").take(2)
        } else filtered
        _uiState.value = _uiState.value.copy(amount = normalized, error = null)
    }

    fun setAmount(v: String) = _uiState.value.let { _uiState.value = it.copy(amount = v, error = null) }
    fun setChannel(c: YktChannel) = _uiState.value.let { _uiState.value = it.copy(selectedChannel = c) }

    fun submit() {
        val now = System.currentTimeMillis()
        if (now - lastActionAt < 500) return
        lastActionAt = now
        val s = _uiState.value
        if (s.submitting) return
        val wallet = s.wallet ?: run {
            _uiState.value = s.copy(error = "未获取到可充值钱包"); return
        }
        val channel = s.selectedChannel ?: run {
            _uiState.value = s.copy(error = "请选择支付渠道"); return
        }
        val amount = s.amount.toDoubleOrNull()
        if (amount == null || amount <= 0) {
            _uiState.value = s.copy(error = "请输入正确的充值金额"); return
        }
        // 提交前规范化（"10." 这类输入原样提交会被服务端拒绝或金额错位）
        val money = "%.2f".format(java.util.Locale.US, amount)
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(submitting = true, error = null)
            when (val r = repo.rechargeOrder(money, wallet, channel, RECHARGE_CALLBACK)) {
                is YktRepository.YktResult.Ok ->
                    _uiState.value = _uiState.value.copy(submitting = false, cashierUrl = r.data)
                is YktRepository.YktResult.Err ->
                    _uiState.value = _uiState.value.copy(submitting = false, error = r.message)
            }
        }
    }

    fun consumeCashierUrl() {
        _uiState.value = _uiState.value.copy(cashierUrl = null)
    }
}

/** 充值表单页：金额 + 渠道 → 下单跳收银台 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RechargeScreen(
    repository: YktRepository,
    onBack: () -> Unit,
    onOpenCashier: (String) -> Unit,
    modifier: Modifier = Modifier,
    vm: RechargeViewModel = viewModel(factory = RechargeViewModelFactory(repository)),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()

    // 页面每次进入组合（含收银台返回）都静默重拉余额；先清掉上次残留的金额/错误
    androidx.compose.runtime.LaunchedEffect(Unit) {
        vm.reset()
        vm.refreshWallet()
    }

    // 下单成功 → 打开收银台 WebView
    androidx.compose.runtime.LaunchedEffect(state.cashierUrl) {
        state.cashierUrl?.let {
            vm.consumeCashierUrl()
            onOpenCashier(it)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("校园卡充值") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        if (state.loading) {
            Box(modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
            }
        } else {
            Column(
                modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                // 充值目标钱包（固定 M1CPU 主卡钱包）
                Text("充值到", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${state.wallet?.walletName ?: "校园卡"}（余额 ${state.wallet?.money ?: "—"} 元）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(16.dp))
                Text("充值金额", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.amount,
                    onValueChange = vm::onAmountChange,
                    placeholder = { Text("请输入金额") },
                    suffix = { Text("元") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("10", "20", "50", "100").forEach { v ->
                        FilterChip(
                            selected = state.amount == v,
                            onClick = { vm.setAmount(v) },
                            label = { Text("$v 元") },
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text("支付方式", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.channels.forEach { c ->
                        FilterChip(
                            selected = state.selectedChannel == c,
                            onClick = { vm.setChannel(c) },
                            label = { Text(c.displayName) },
                        )
                    }
                }

                state.error?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = vm::submit,
                    enabled = !state.submitting,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    if (state.submitting) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("去支付")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "支付将在应用内收银台完成，支付成功后自动显示结果",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
