package li.lyc.lzuapp.ui.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import li.lyc.lzuapp.data.AuthStore
import li.lyc.lzuapp.data.repo.CourseRepository
import li.lyc.lzuapp.data.repo.FdmRepository
import li.lyc.lzuapp.data.repo.LoginResult
import li.lyc.lzuapp.data.repo.SyncResult
import li.lyc.lzuapp.theme.ThemeColor
import li.lyc.lzuapp.theme.ThemeMode
import li.lyc.lzuapp.ui.apps.AppCatalog
import li.lyc.lzuapp.ui.common.MadeByLyc
import li.lyc.lzuapp.ui.login.AgreementDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val THEME_OPTIONS = listOf(
    ThemeMode.SYSTEM to "自动",
    ThemeMode.LIGHT to "浅色",
    ThemeMode.DARK to "深色",
)

private enum class ProfilePage(val title: String) {
    HOME("设置"),
    ACCOUNT("我的"),
    PERSONALIZATION("个性化"),
    APP_MANAGEMENT("应用管理"),
    OTHER("其他设置"),
    REAUTHENTICATION("重新认证"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    authStore: AuthStore,
    repository: CourseRepository,
    fdmRepository: FdmRepository,
    modifier: Modifier = Modifier,
) {
    val auth by authStore.state.collectAsStateWithLifecycle(initialValue = null)
    var page by remember { mutableStateOf(ProfilePage.HOME) }
    var confirmLogout by remember { mutableStateOf(false) }
    var showAgreement by remember { mutableStateOf(false) }
    var checkingLogin by remember { mutableStateOf(false) }
    var accountMessage by remember { mutableStateOf("") }
    var refreshError by remember { mutableStateOf<String?>(null) }
    var amapApiKeyInput by remember { mutableStateOf("") }
    var amapKeyMessage by remember { mutableStateOf("") }
    var reauthUsername by remember { mutableStateOf("") }
    var reauthPassword by remember { mutableStateOf("") }
    var reauthCaptcha by remember { mutableStateOf("") }
    var reauthSessionId by remember { mutableStateOf("") }
    var reauthNeedCaptcha by remember { mutableStateOf(false) }
    var reauthCaptchaSent by remember { mutableStateOf(false) }
    var reauthCaptchaCooldown by remember { mutableIntStateOf(0) }
    var reauthLoading by remember { mutableStateOf(false) }
    var reauthSending by remember { mutableStateOf(false) }
    var reauthMessage by remember { mutableStateOf("") }
    var reauthError by remember { mutableStateOf("") }
    var refreshJob by remember { mutableStateOf<Job?>(null) }
    var reauthJob by remember { mutableStateOf<Job?>(null) }
    var logoutJob by remember { mutableStateOf<Job?>(null) }
    var refreshGeneration by remember { mutableIntStateOf(0) }
    var reauthGeneration by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    fun cancelRefresh() {
        refreshGeneration++
        refreshJob?.cancel()
        refreshJob = null
        checkingLogin = false
    }

    fun cancelReauthenticationJob() {
        reauthGeneration++
        reauthJob?.cancel()
        reauthJob = null
        reauthLoading = false
        reauthSending = false
    }

    fun openReauthentication() {
        cancelRefresh()
        cancelReauthenticationJob()
        refreshError = null
        reauthUsername = auth?.username.orEmpty()
        reauthPassword = ""
        reauthCaptcha = ""
        reauthSessionId = ""
        reauthNeedCaptcha = false
        reauthCaptchaSent = false
        reauthCaptchaCooldown = 0
        reauthMessage = ""
        reauthError = ""
        page = ProfilePage.REAUTHENTICATION
    }

    fun closeReauthentication() {
        cancelReauthenticationJob()
        reauthPassword = ""
        reauthCaptcha = ""
        reauthSessionId = ""
        page = ProfilePage.ACCOUNT
    }

    BackHandler(enabled = page != ProfilePage.HOME) {
        when (page) {
            ProfilePage.REAUTHENTICATION -> closeReauthentication()
            ProfilePage.ACCOUNT -> {
                cancelRefresh()
                page = ProfilePage.HOME
            }
            else -> page = ProfilePage.HOME
        }
    }

    LaunchedEffect(reauthCaptchaCooldown) {
        if (reauthCaptchaCooldown > 0) {
            delay(1_000)
            reauthCaptchaCooldown--
        }
    }

    if (showAgreement) {
        AgreementDialog(onDismiss = { showAgreement = false })
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(page.title) },
                navigationIcon = {
                    if (page != ProfilePage.HOME) {
                        IconButton(
                            onClick = {
                                when (page) {
                                    ProfilePage.REAUTHENTICATION -> closeReauthentication()
                                    ProfilePage.ACCOUNT -> {
                                        cancelRefresh()
                                        page = ProfilePage.HOME
                                    }
                                    else -> page = ProfilePage.HOME
                                }
                            },
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
            )
        },
        modifier = modifier,
    ) { padding ->
        when (page) {
            ProfilePage.HOME -> SettingsContent(padding) {
                SettingsEntryCard(
                    icon = Icons.Default.AccountCircle,
                    title = "我的",
                    subtitle = "账号信息 · 登录状态 · 重新认证",
                    onClick = { page = ProfilePage.ACCOUNT },
                )
                SettingsEntryCard(
                    icon = Icons.Default.Palette,
                    title = "个性化",
                    subtitle = "深色模式 · 主题配色",
                    onClick = { page = ProfilePage.PERSONALIZATION },
                )
                SettingsEntryCard(
                    icon = Icons.Default.Apps,
                    title = "应用管理",
                    subtitle = "显示或隐藏应用入口",
                    onClick = { page = ProfilePage.APP_MANAGEMENT },
                )
                SettingsEntryCard(
                    icon = Icons.Default.Tune,
                    title = "其他设置",
                    subtitle = "高德 API key",
                    onClick = { page = ProfilePage.OTHER },
                )
                Spacer(Modifier.height(16.dp))
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
                    TextButton(
                        onClick = { showAgreement = true },
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) {
                        Text(
                            "用户协议",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    MadeByLyc()
                }
            }

            ProfilePage.ACCOUNT -> SettingsContent(padding) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                        Text("账号信息", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(8.dp))
                        InfoRow("学号", auth?.xykh?.ifBlank { null } ?: auth?.username.orEmpty())
                        InfoRow("姓名", auth?.userName?.ifBlank { null } ?: "—")
                        InfoRow("学院", auth?.college?.ifBlank { null } ?: "—")
                        InfoRow("登录状态", if (auth?.isLoggedIn == true) "已登录" else "未登录")
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = {
                                refreshJob?.cancel()
                                val generation = ++refreshGeneration
                                checkingLogin = true
                                accountMessage = ""
                                refreshError = null
                                refreshJob = scope.launch {
                                    try {
                                        when (val result = repository.refreshLoginState()) {
                                            SyncResult.Success -> {
                                                if (generation == refreshGeneration) {
                                                    accountMessage = "登录状态正常"
                                                }
                                            }
                                            is SyncResult.Failed -> {
                                                if (generation == refreshGeneration) {
                                                    refreshError = result.message
                                                }
                                            }
                                        }
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        if (generation == refreshGeneration) {
                                            refreshError = "刷新登录状态失败，请稍后重试"
                                        }
                                    } finally {
                                        if (generation == refreshGeneration) {
                                            checkingLogin = false
                                            refreshJob = null
                                        }
                                    }
                                }
                            },
                            enabled = !checkingLogin,
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                        ) {
                            if (checkingLogin) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Text("刷新登录状态")
                            }
                        }
                        if (accountMessage.isNotEmpty()) {
                            Text(
                                accountMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = ::openReauthentication,
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                        ) {
                            Text("重新认证 / 修改密码")
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { confirmLogout = true },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        ) {
                            Text("退出登录", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            ProfilePage.PERSONALIZATION -> SettingsContent(padding) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                        Text("外观", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "深色模式",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        val currentMode = ThemeMode.fromStorage(auth?.themeMode)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            THEME_OPTIONS.forEachIndexed { index, (mode, label) ->
                                SegmentedButton(
                                    selected = currentMode == mode,
                                    onClick = { scope.launch { authStore.setThemeMode(mode.storageValue) } },
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = index,
                                        count = THEME_OPTIONS.size,
                                    ),
                                ) {
                                    Text(label)
                                }
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                        Text(
                            "主题配色",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        val currentColor = ThemeColor.fromStorage(auth?.themeColor)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            ThemeColor.entries.forEachIndexed { index, color ->
                                SegmentedButton(
                                    selected = currentColor == color,
                                    onClick = { scope.launch { authStore.setThemeColor(color.storageValue) } },
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = index,
                                        count = ThemeColor.entries.size,
                                    ),
                                ) {
                                    Text(color.label)
                                }
                            }
                        }
                    }
                }
            }

            ProfilePage.OTHER -> SettingsContent(padding) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                        Text("高德 API key", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(8.dp))
                        InfoRow(
                            "当前配置",
                            if (auth?.amapApiKey?.isNotBlank() == true) "自定义配置" else "默认配置",
                        )
                        OutlinedTextField(
                            value = amapApiKeyInput,
                            onValueChange = {
                                amapApiKeyInput = it
                                amapKeyMessage = ""
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            label = { Text("自定义 API key（可选）") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                        )
                        Row(
                            Modifier.fillMaxWidth().padding(top = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        authStore.saveAmapApiKey(amapApiKeyInput.trim())
                                        amapApiKeyInput = ""
                                        amapKeyMessage = "已保存，下次定位生效"
                                    }
                                },
                                enabled = amapApiKeyInput.isNotBlank(),
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("保存")
                            }
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        authStore.saveAmapApiKey("")
                                        amapApiKeyInput = ""
                                        amapKeyMessage = "已恢复默认配置，下次定位生效"
                                    }
                                },
                                enabled = auth?.amapApiKey?.isNotBlank() == true,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("清除")
                            }
                        }
                        if (amapKeyMessage.isNotEmpty()) {
                            Text(
                                amapKeyMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }

            }

            ProfilePage.APP_MANAGEMENT -> SettingsContent(padding) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                        val appOrder = AppCatalog.normalizeOrder(auth?.appOrder.orEmpty())
                        val appsById = AppCatalog.entries.associateBy { it.id }
                        val hiddenApps = auth?.hiddenApps.orEmpty()
                        appOrder.forEach { appId ->
                            val app = appsById[appId] ?: return@forEach
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(app.title, modifier = Modifier.weight(1f))
                                Switch(
                                    checked = app.id !in hiddenApps,
                                    enabled = auth != null,
                                    onCheckedChange = { visible ->
                                        scope.launch { authStore.setAppVisibility(app.id, visible) }
                                    },
                                    modifier = Modifier.semantics {
                                        contentDescription = "显示${app.title}"
                                    },
                                )
                            }
                        }
                        TextButton(
                            onClick = {
                                scope.launch { authStore.restoreAllApps() }
                            },
                            enabled = hiddenApps.isNotEmpty(),
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            Text("恢复全部隐藏")
                        }
                    }
                }
            }

            ProfilePage.REAUTHENTICATION -> SettingsContent(padding) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                        Text(
                            "仅可验证当前学号，不能借此更换账号。验证成功后才会更新密码和登录状态，失败或取消不影响当前登录。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = reauthUsername,
                            onValueChange = {},
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                            label = { Text("当前学号") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                            singleLine = true,
                            readOnly = true,
                            enabled = !reauthLoading && !reauthSending,
                        )
                        OutlinedTextField(
                            value = reauthPassword,
                            onValueChange = {
                                reauthPassword = it
                                reauthNeedCaptcha = false
                                reauthSessionId = ""
                                reauthCaptcha = ""
                                reauthCaptchaSent = false
                                reauthCaptchaCooldown = 0
                                reauthMessage = ""
                                reauthError = ""
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            label = { Text("密码") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                            enabled = !reauthLoading && !reauthSending,
                        )
                        if (reauthNeedCaptcha) {
                            Row(
                                Modifier.fillMaxWidth().padding(top = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                OutlinedTextField(
                                    value = reauthCaptcha,
                                    onValueChange = {
                                        reauthCaptcha = it
                                        reauthError = ""
                                    },
                                    modifier = Modifier.weight(1f),
                                    label = { Text("短信验证码") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    enabled = !reauthLoading && !reauthSending,
                                )
                                Spacer(Modifier.width(12.dp))
                                OutlinedButton(
                                    onClick = {
                                        val sessionId = reauthSessionId
                                        if (sessionId.isBlank()) {
                                            reauthError = "短信验证会话已失效，请重新认证"
                                        } else {
                                            reauthJob?.cancel()
                                            val generation = ++reauthGeneration
                                            reauthSending = true
                                            reauthError = ""
                                            reauthMessage = ""
                                            reauthJob = scope.launch {
                                                try {
                                                    val sent = repository.sendReauthenticationCaptcha(sessionId)
                                                    if (generation != reauthGeneration) return@launch
                                                    if (sent) {
                                                        reauthCaptchaSent = true
                                                        reauthCaptchaCooldown = 90
                                                        reauthMessage = "验证码已发送到你的手机，请输入。"
                                                    } else {
                                                        reauthError = "验证码发送失败，请稍后重试"
                                                    }
                                                } catch (e: CancellationException) {
                                                    throw e
                                                } catch (e: Exception) {
                                                    if (generation == reauthGeneration) {
                                                        reauthError = "验证码发送失败，请稍后重试"
                                                    }
                                                } finally {
                                                    if (generation == reauthGeneration) {
                                                        reauthSending = false
                                                        reauthJob = null
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    enabled = !reauthLoading && !reauthSending && reauthCaptchaCooldown == 0,
                                    modifier = Modifier.height(56.dp),
                                ) {
                                    if (reauthSending) {
                                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    } else {
                                        Text(
                                            if (reauthCaptchaCooldown > 0) "${reauthCaptchaCooldown}s后重发"
                                            else if (reauthCaptchaSent) "重新发送"
                                            else "发送验证码",
                                        )
                                    }
                                }
                            }
                        }
                        if (reauthMessage.isNotEmpty()) {
                            Text(
                                reauthMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        if (reauthError.isNotEmpty()) {
                            Text(
                                reauthError,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        Button(
                            onClick = {
                                val needsCaptcha = reauthNeedCaptcha
                                val sessionId = reauthSessionId
                                if (needsCaptcha && sessionId.isBlank()) {
                                    reauthError = "短信验证会话已失效，请重新认证"
                                } else {
                                    val username = reauthUsername.trim()
                                    val password = reauthPassword
                                    val captcha = reauthCaptcha.trim()
                                    reauthJob?.cancel()
                                    val generation = ++reauthGeneration
                                    reauthLoading = true
                                    reauthError = ""
                                    reauthMessage = ""
                                    reauthJob = scope.launch {
                                        try {
                                            val result = if (needsCaptcha) {
                                                repository.verifyReauthentication(
                                                    username = username,
                                                    password = password,
                                                    sessionId = sessionId,
                                                    captcha = captcha,
                                                )
                                            } else {
                                                repository.reauthenticate(username, password)
                                            }
                                            if (generation != reauthGeneration) return@launch
                                            when (result) {
                                                is LoginResult.Success -> {
                                                    reauthPassword = ""
                                                    reauthCaptcha = ""
                                                    reauthSessionId = ""
                                                    reauthNeedCaptcha = false
                                                    reauthCaptchaSent = false
                                                    reauthCaptchaCooldown = 0
                                                    accountMessage = "重新认证成功，登录信息已更新"
                                                    page = ProfilePage.ACCOUNT
                                                }
                                                is LoginResult.NeedCaptcha -> {
                                                    if (result.sessionId.isBlank()) {
                                                        reauthNeedCaptcha = false
                                                        reauthError = "登录服务未返回短信验证会话，请稍后重试"
                                                    } else {
                                                        reauthNeedCaptcha = true
                                                        reauthSessionId = result.sessionId
                                                        reauthCaptcha = ""
                                                        reauthCaptchaSent = false
                                                        reauthCaptchaCooldown = 0
                                                        reauthMessage = "系统需要短信验证，请先发送验证码。"
                                                    }
                                                }
                                                is LoginResult.Failed -> reauthError = result.message
                                            }
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            if (generation == reauthGeneration) {
                                                reauthError = "认证或登录信息保存失败，请重试"
                                            }
                                        } finally {
                                            if (generation == reauthGeneration) {
                                                reauthLoading = false
                                                reauthJob = null
                                            }
                                        }
                                    }
                                }
                            },
                            enabled = !reauthLoading && !reauthSending &&
                                reauthUsername.isNotBlank() && reauthPassword.isNotBlank() &&
                                (!reauthNeedCaptcha || reauthCaptcha.isNotBlank()),
                            modifier = Modifier.fillMaxWidth().padding(top = 20.dp).height(48.dp),
                        ) {
                            if (reauthLoading) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Text(if (reauthNeedCaptcha) "完成验证" else "验证并保存")
                            }
                        }
                        TextButton(
                            onClick = ::closeReauthentication,
                            enabled = !reauthLoading && !reauthSending,
                            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp),
                        ) {
                            Text("取消，保留当前登录")
                        }
                    }
                }
            }
        }
    }

    refreshError?.let { message ->
        AlertDialog(
            onDismissRequest = { refreshError = null },
            title = { Text("刷新失败") },
            text = { Text("$message\n\n当前登录和已保存的密码均未改变。你可以重新输入密码认证，或保留当前登录。") },
            confirmButton = {
                TextButton(onClick = ::openReauthentication) {
                    Text("重新输入密码认证")
                }
            },
            dismissButton = {
                TextButton(onClick = { refreshError = null }) {
                    Text("保留当前登录")
                }
            },
        )
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("退出登录") },
            text = { Text("退出后将清除课程缓存、校历、校园卡会话及全部个人数据，仅保留学号用于下次登录预填。确定退出吗？") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    cancelRefresh()
                    cancelReauthenticationJob()
                    refreshError = null
                    reauthPassword = ""
                    reauthCaptcha = ""
                    reauthSessionId = ""
                    logoutJob = scope.launch {
                        try {
                            try {
                                fdmRepository.logout()
                            } finally {
                                repository.logout()
                            }
                        } finally {
                            logoutJob = null
                        }
                    }
                }) {
                    Text("退出", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SettingsContent(
    contentPadding: PaddingValues,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        content()
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SettingsEntryCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
