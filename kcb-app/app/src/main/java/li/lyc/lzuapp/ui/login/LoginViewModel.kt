package li.lyc.lzuapp.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import li.lyc.lzuapp.data.repo.CourseRepository
import li.lyc.lzuapp.data.repo.LoginResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LoginUiState(
    val username: String = "",
    val password: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    /** 是否需要短信二次认证（验证码栏可见） */
    val needCaptcha: Boolean = false,
    /** 验证码输入内容 */
    val captcha: String = "",
    /** 验证码是否已发送 */
    val captchaSent: Boolean = false,
    /** 发送按钮冷却剩余秒数，0 = 可发送 */
    val captchaCooldown: Int = 0,
    /** 密码明文显示 */
    val passwordVisible: Boolean = false,
)

class LoginViewModel(private val repo: CourseRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    // 学号/密码变更后旧的验证码会话（sessionId）已失效，需清掉二次认证状态回到账号密码登录
    fun onUsernameChange(v: String) {
        _uiState.value = _uiState.value.copy(
            username = v, error = null, needCaptcha = false, captcha = "", captchaSent = false
        )
    }

    fun onPasswordChange(v: String) {
        _uiState.value = _uiState.value.copy(
            password = v, error = null, needCaptcha = false, captcha = "", captchaSent = false
        )
    }

    fun onCaptchaChange(v: String) {
        _uiState.value = _uiState.value.copy(captcha = v, error = null)
    }

    /** 重置表单（退出登录后再次进入登录页时清理残留状态） */
    fun reset() {
        // 取消可能仍在跑的冷却倒计时，避免旧协程往新状态写值
        cooldownJob?.cancel()
        _uiState.value = LoginUiState()
    }

    fun togglePasswordVisible() {
        _uiState.value = _uiState.value.copy(passwordVisible = !_uiState.value.passwordVisible)
    }

    fun login() {
        val s = _uiState.value
        if (s.username.isBlank() || s.password.isBlank()) {
            _uiState.value = s.copy(error = "请输入学号和密码")
            return
        }
        if (s.needCaptcha && s.captcha.isBlank()) {
            _uiState.value = s.copy(error = "请输入验证码")
            return
        }
        _uiState.value = s.copy(loading = true, error = null)
        viewModelScope.launch {
            val result = if (s.needCaptcha) repo.verifyCaptcha(s.captcha.trim())
            else repo.login(s.username.trim(), s.password)
            when (result) {
                is LoginResult.Success -> _uiState.value = _uiState.value.copy(loading = false)
                is LoginResult.NeedCaptcha -> _uiState.value = _uiState.value.copy(
                    loading = false, needCaptcha = true, captchaSent = false
                )
                is LoginResult.Failed -> _uiState.value = _uiState.value.copy(
                    loading = false, error = result.message
                )
            }
        }
    }

    private var cooldownJob: Job? = null

    fun sendCaptcha() {
        _uiState.value = _uiState.value.copy(captchaCooldown = 90)
        viewModelScope.launch {
            val ok = repo.sendCaptcha()
            if (ok) {
                _uiState.value = _uiState.value.copy(captchaSent = true, error = null)
                startCooldown()
            } else {
                _uiState.value = _uiState.value.copy(
                    captchaCooldown = 0, error = "验证码发送失败，请重试"
                )
            }
        }
    }

    /** 冷却倒计时：先取消旧 Job，避免重复发送后多个计时协程互相覆盖 */
    private fun startCooldown() {
        cooldownJob?.cancel()
        cooldownJob = viewModelScope.launch {
            for (i in 90 downTo 1) {
                _uiState.value = _uiState.value.copy(captchaCooldown = i)
                delay(1000)
            }
            _uiState.value = _uiState.value.copy(captchaCooldown = 0)
        }
    }
}
