package li.lyc.lzuapp.ui.login

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import li.lyc.lzuapp.R
import li.lyc.lzuapp.data.repo.CourseRepository

class LoginViewModelFactory(private val repo: CourseRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return LoginViewModel(repo) as T
    }
}

@Composable
fun rememberLoginViewModelFactory(repository: CourseRepository): LoginViewModelFactory =
    remember(repository) { LoginViewModelFactory(repository) }

/**
 * 深色背景下输入框固定白底深字（锁定浅色样式），避免深色主题下输入内容看不清。
 * 浮起标签背景由 M3 用容器色裁剪，深灰色在两种模式下都可读。
 */
private val LOGIN_FIELD_COLORS
    @Composable get() = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = Color.White,
        unfocusedContainerColor = Color.White,
        focusedTextColor = Color(0xFF1A1A1A),
        unfocusedTextColor = Color(0xFF1A1A1A),
        focusedLabelColor = MaterialTheme.colorScheme.primary,
        unfocusedLabelColor = Color(0xFF5F6368),
        focusedTrailingIconColor = Color(0xFF5F6368),
        unfocusedTrailingIconColor = Color(0xFF5F6368),
        cursorColor = MaterialTheme.colorScheme.primary,
    )

@Composable
fun LoginScreen(
    repository: CourseRepository,
    modifier: Modifier = Modifier,
    /** 已同意用户协议的学号（当前输入学号与之一致时登录免弹协议窗） */
    agreementUser: String = "",
    /** 用户在协议弹窗点了同意（持久化由调用方完成） */
    onAcceptAgreement: (String) -> Unit = {},
    vm: LoginViewModel = viewModel(factory = rememberLoginViewModelFactory(repository)),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    var showAgreement by remember { mutableStateOf(false) }
    // 弹窗目的：true=登录前强制确认（需滑到底才可同意）；false=仅查看协议
    var agreementForLogin by remember { mutableStateOf(false) }

    // 进入登录页时重置表单（退出登录后再次进入不残留验证码/密码可见状态）
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.reset() }

    if (showAgreement) {
        AgreementDialog(
            onDismiss = { showAgreement = false },
            onConfirm = if (agreementForLogin) {
                {
                    showAgreement = false
                    onAcceptAgreement(state.username.trim())
                    vm.login()
                }
            } else null,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // 登录页无 Scaffold，窗口背景在系统深色下是 launch_background 深色；
            // 强制浅色必须自己画背景，且要画在安全区 padding 之前（盖住状态栏区域），
            // 否则会透出深色窗口底
            .background(MaterialTheme.colorScheme.background)
            .then(modifier)
            // edge-to-edge 规范：imePadding 在 verticalScroll 之前，键盘弹起不遮挡输入框
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.app_icon),
            contentDescription = "应用图标",
            modifier = Modifier
                .size(88.dp)
                .clip(RoundedCornerShape(20.dp)),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "LZUapp",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "第三方兰大工具箱",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(40.dp))

        OutlinedTextField(
            value = state.username,
            onValueChange = vm::onUsernameChange,
            label = { Text("学号") },
            singleLine = true,
            // 学号可能含字母，用普通键盘而非纯数字键盘
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            colors = LOGIN_FIELD_COLORS,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = state.password,
            onValueChange = vm::onPasswordChange,
            label = { Text("密码") },
            singleLine = true,
            visualTransformation = if (state.passwordVisible) VisualTransformation.None
            else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            colors = LOGIN_FIELD_COLORS,
            trailingIcon = {
                IconButton(onClick = vm::togglePasswordVisible) {
                    Icon(
                        imageVector = if (state.passwordVisible) Icons.Filled.VisibilityOff
                        else Icons.Filled.Visibility,
                        contentDescription = if (state.passwordVisible) "隐藏密码" else "显示密码",
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        // 左侧版本号；右侧协议入口（仅查看，不强制）+ 署名（内置彩蛋手势预留）
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "v${li.lyc.lzuapp.BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                "登录即代表同意",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = {
                    agreementForLogin = false
                    showAgreement = true
                },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
            ) {
                Text(
                    "用户协议",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            li.lyc.lzuapp.ui.common.MadeByLyc()
        }

        // 短信二次认证：内联验证码栏（登录返回 20001 后显示）
        if (state.needCaptcha) {
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.captcha,
                    onValueChange = vm::onCaptchaChange,
                    label = { Text("短信验证码") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = LOGIN_FIELD_COLORS,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                OutlinedButton(
                    onClick = vm::sendCaptcha,
                    enabled = state.captchaCooldown == 0,
                    modifier = Modifier.height(56.dp),
                ) {
                    Text(
                        if (state.captchaCooldown > 0) "${state.captchaCooldown}s后重发"
                        else if (state.captchaSent) "重新发送"
                        else "发送验证码"
                    )
                }
            }
            Text(
                if (state.captchaSent) "验证码已发送到你的手机，请输入。" else "系统需要短信验证，请先发送验证码。",
                style = MaterialTheme.typography.bodySmall,
                // 显式跟随主题（深色模式下继承的 LocalContentColor 过暗看不清）
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }

        if (state.error != null) {
            Spacer(Modifier.height(12.dp))
            Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(32.dp))
        Button(
            onClick = {
                // 该学号未确认过当前版本协议 → 先弹协议窗（需滑到底）；已确认过直接登录
                if (state.username.isNotBlank() && state.password.isNotBlank() &&
                    state.username.trim() + "|" + AGREEMENT_VERSION != agreementUser
                ) {
                    agreementForLogin = true
                    showAgreement = true
                } else {
                    vm.login()
                }
            },
            enabled = !state.loading,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            if (state.loading) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text("登 录")
            }
        }
    }
}
