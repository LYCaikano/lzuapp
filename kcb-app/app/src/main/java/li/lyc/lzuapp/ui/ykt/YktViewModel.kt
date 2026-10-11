package li.lyc.lzuapp.ui.ykt

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import li.lyc.lzuapp.data.repo.YktRepository
import li.lyc.lzuapp.data.repo.YktWallet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class YktUiState(
    /** 进入页面时的登录态静默校验中 */
    val checking: Boolean = true,
    val error: String? = null,
    val wallets: List<YktWallet> = emptyList(),
    /** 付款码位图与查单号 */
    val qrBitmap: Bitmap? = null,
    val authNum: String = "",
    /** 距自动刷新秒数（[QR_REFRESH_SECONDS] 秒一轮） */
    val countdown: Int = QR_REFRESH_SECONDS,
    val qrLoading: Boolean = false,
    val balanceLoading: Boolean = false,
)

/** 付款码自动刷新间隔（用户指定 60 秒；官方 H5 为 70 秒） */
const val QR_REFRESH_SECONDS = 60

class YktViewModelFactory(private val repo: YktRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return YktViewModel(repo) as T
    }
}

class YktViewModel(private val repo: YktRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(YktUiState())
    val uiState: StateFlow<YktUiState> = _uiState.asStateFlow()

    private var countdownJob: Job? = null
    private var lastActionAt = 0L
    private var lastBalanceRefreshAt = 0L
    /** 付款码连续取码失败次数（≥2 清码显错误态） */
    private var qrFailCount = 0

    init {
        // 进入页面先静默校验登录态（防掉登录出示无效码），通过后加载余额与付款码
        viewModelScope.launch {
            when (val r = repo.checkLoginState()) {
                is YktRepository.YktResult.Ok -> {
                    _uiState.value = _uiState.value.copy(checking = false)
                    refreshBalance()
                    refreshQr(initial = true)
                }
                is YktRepository.YktResult.Err -> {
                    _uiState.value = _uiState.value.copy(checking = false, error = r.message)
                }
            }
        }
    }

    private fun cooldown(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastActionAt < 500) return false
        lastActionAt = now
        return true
    }

    /**
     * 刷新余额。1s 纯时间戳节流：冷却内的调用直接丢弃
     * （不排队、冷却结束后也不补发），外部无感知。
     */
    fun refreshBalance() {
        val now = System.currentTimeMillis()
        if (now - lastBalanceRefreshAt < 1000) return
        lastBalanceRefreshAt = now
        if (_uiState.value.balanceLoading) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(balanceLoading = true)
            when (val r = repo.walletMoney()) {
                is YktRepository.YktResult.Ok ->
                    _uiState.value = _uiState.value.copy(wallets = r.data, balanceLoading = false, error = null)
                is YktRepository.YktResult.Err ->
                    _uiState.value = _uiState.value.copy(balanceLoading = false, error = r.message)
            }
        }
    }

    /** 刷新付款码（自动/手动共用；手动有 500ms 冷却） */
    fun refreshQr(manual: Boolean = false, initial: Boolean = false) {
        if (manual && !cooldown()) return
        val s = _uiState.value
        if (s.qrLoading) return
        countdownJob?.cancel()
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(qrLoading = true)
            when (val r = repo.paymentQrCode()) {
                is YktRepository.YktResult.Ok -> {
                    val (content, authNum, _) = r.data
                    val bitmap = renderQr(content)
                    qrFailCount = 0
                    _uiState.value = _uiState.value.copy(
                        qrBitmap = bitmap, authNum = authNum, qrLoading = false, error = null,
                    )
                }
                is YktRepository.YktResult.Err -> {
                    qrFailCount++
                    if (qrFailCount >= 2) {
                        // 连续失败：旧码已超 60s 生命周期不可信，清掉显错误态（顶栏可重试）
                        _uiState.value = _uiState.value.copy(
                            qrBitmap = null, qrLoading = false, error = r.message,
                        )
                    } else {
                        // 单次失败保旧码，仅提示
                        _uiState.value = _uiState.value.copy(qrLoading = false, error = r.message)
                    }
                }
            }
            startCountdown()
        }
    }

    /** 60s 倒计时自动刷新付款码 */
    private fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            var left = QR_REFRESH_SECONDS
            while (isActive && left > 0) {
                _uiState.value = _uiState.value.copy(countdown = left)
                delay(1000)
                left--
            }
            if (isActive) refreshQr()
        }
    }

    /**
     * 页面可见性挂钩（YktScreen 的 LifecycleEventObserver 调用）：
     * 不可见（切 tab/退后台/进子页面）时停倒计时，不再后台刷码；
     * 恢复可见时立即刷新一次码（refreshQr 内部会重启倒计时）。
     */
    fun setVisible(visible: Boolean) {
        if (visible) {
            if (!_uiState.value.checking) refreshQr()
        } else {
            countdownJob?.cancel()
        }
    }

    fun retry() {
        // 取消旧倒计时，避免旧 Job 到点与新流程交错触发 refreshQr
        countdownJob?.cancel()
        qrFailCount = 0
        _uiState.value = YktUiState()
        viewModelScope.launch {
            when (val r = repo.checkLoginState()) {
                is YktRepository.YktResult.Ok -> {
                    _uiState.value = _uiState.value.copy(checking = false)
                    refreshBalance()
                    refreshQr(initial = true)
                }
                is YktRepository.YktResult.Err ->
                    _uiState.value = _uiState.value.copy(checking = false, error = r.message)
            }
        }
    }

    private suspend fun renderQr(content: String): Bitmap? = withContext(Dispatchers.Default) {
        try {
            val size = 720
            // 纠错级别 M：密度与官方 H5 观感接近（默认 L 偏稀疏）；静区由 UI 白底 padding 提供
            val hints = mapOf(
                com.google.zxing.EncodeHintType.ERROR_CORRECTION to
                    com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M,
            )
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
            // IntArray 一次 setPixels（逐像素 setPixel 约 52 万次调用，卡顿明显）
            val pixels = IntArray(size * size)
            for (y in 0 until size) for (x in 0 until size) {
                pixels[y * size + x] =
                    if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            }
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            bmp.setPixels(pixels, 0, size, 0, 0, size, size)
            bmp
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}
