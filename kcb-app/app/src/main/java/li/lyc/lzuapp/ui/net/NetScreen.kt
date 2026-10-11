package li.lyc.lzuapp.ui.net

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import li.lyc.lzuapp.data.AuthStore
import li.lyc.lzuapp.data.api.LzuSelfService
import li.lyc.lzuapp.data.repo.NetRepository

data class NetUiState(
    val accounts: List<AuthStore.NetAccount> = emptyList(),
    /** 当前账户 id（自动登录/设备管理跟随；一键登录会把该账户设为当前） */
    val activeId: String = "",
    val autoLogin: Boolean = false,
    /** null=尚未检测/不在校园网；true/false=门户在线状态 */
    val online: Boolean? = null,
    val onlineIp: String = "",
    val statusText: String = "",
    /** 正在一键登录的账户 id */
    val loggingInId: String? = null,
    /** 详情页打开的账户 id；"new" = 新建账户；null = 卡片首页 */
    val openId: String? = null,
    /** 详情页 登录/注销 busy */
    val busy: Boolean = false,
    val devices: List<LzuSelfService.NetDevice>? = null,
    val devicesError: String? = null,
    val kickingId: String? = null,
)

class NetViewModelFactory(
    private val repo: NetRepository,
    private val authStore: AuthStore,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return NetViewModel(repo, authStore) as T
    }
}

class NetViewModel(
    private val repo: NetRepository,
    private val authStore: AuthStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NetUiState())
    val uiState: StateFlow<NetUiState> = _uiState.asStateFlow()

    /** 一次性提示（登录/注销/下线结果），Activity 层 snackbar 播报 */
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    /** 服务状态同步钩子（由 UI 层注入，避免 VM 持有 Context） */
    var onAutoLoginChanged: ((Boolean) -> Unit)? = null

    private var lastActionAt = 0L
    private fun coolDown(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastActionAt < 500) return false
        lastActionAt = now
        return true
    }

    /** 一键登录专用 3s 冷却（防疯狂点击触发门户风控），与通用 500ms 冷却独立 */
    private var lastLoginAt = 0L
    private fun loginCoolDown(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastLoginAt < 3_000) return false
        lastLoginAt = now
        return true
    }

    init {
        viewModelScope.launch {
            // 旧单账户 → 多账户列表（幂等）
            authStore.migrateNetAccounts()
            authStore.state.collect { s ->
                _uiState.value = _uiState.value.copy(
                    accounts = s.netAccounts.filter { it.username.isNotBlank() },
                    activeId = s.activeNetAccount?.id.orEmpty(),
                    autoLogin = s.netAutoLogin,
                )
            }
        }
        refreshStatus()
    }

    private fun persist(accounts: List<AuthStore.NetAccount>, activeId: String) {
        viewModelScope.launch { authStore.saveNetAccounts(accounts, activeId) }
    }

    // ---------- 详情页导航 ----------

    fun openDetail(id: String) {
        if (!coolDown()) return
        _uiState.value = _uiState.value.copy(openId = id, devices = null, devicesError = null)
        val acc = _uiState.value.accounts.firstOrNull { it.id == id }
        if (acc != null) refreshDevices(acc)
    }

    fun openNew() {
        if (!coolDown()) return
        _uiState.value = _uiState.value.copy(openId = "new", devices = null, devicesError = null)
    }

    fun closeDetail() {
        _uiState.value = _uiState.value.copy(openId = null, devices = null, devicesError = null)
    }

    // ---------- 账户管理 ----------

    /** 保存（新建或更新）账户；新建成功后回卡片首页 */
    fun saveAccount(id: String?, username: String, password: String, type: NetRepository.AccountType) {
        if (username.isBlank()) { _events.tryEmit("账号不能为空"); return }
        if (password.isBlank()) { _events.tryEmit("密码不能为空"); return }
        val s = _uiState.value
        if (id == null || id == "new") {
            val newId = "acc${System.currentTimeMillis()}"
            val list = s.accounts + AuthStore.NetAccount(newId, username.trim(), password, type.storage)
            // 首个账户自动设为当前
            persist(list, if (s.accounts.isEmpty()) newId else s.activeId)
            _events.tryEmit("已添加账户")
            closeDetail()
        } else {
            val list = s.accounts.map {
                if (it.id == id) it.copy(username = username.trim(), password = password, type = type.storage) else it
            }
            persist(list, s.activeId)
            _events.tryEmit("已保存")
        }
    }

    fun deleteAccount(id: String) {
        if (!coolDown()) return
        val s = _uiState.value
        val list = s.accounts.filterNot { it.id == id }
        val newActive = if (s.activeId == id) list.firstOrNull()?.id.orEmpty() else s.activeId
        persist(list, newActive)
        _events.tryEmit("已删除账户")
        closeDetail()
    }

    // ---------- 门户登录/注销 ----------

    fun refreshStatus() {
        viewModelScope.launch {
            when (val r = repo.status()) {
                is NetRepository.NetResult.Ok -> _uiState.value = _uiState.value.copy(
                    online = r.data.online,
                    onlineIp = r.data.onlineIp,
                    statusText = if (r.data.online) "已在线" else "当前设备未登录",
                )
                is NetRepository.NetResult.Err -> _uiState.value = _uiState.value.copy(
                    online = null, onlineIp = "", statusText = r.message,
                )
            }
        }
    }

    /** 一键登录：若当前 IP 会话挂在其他账户上，先注销旧会话再登录（否则 UI 切了会话没切） */
    fun login(account: AuthStore.NetAccount) {
        if (!loginCoolDown() || _uiState.value.loggingInId != null || _uiState.value.busy) return
        if (account.password.isBlank()) { _events.tryEmit("该账户未填密码，请进入详情页完善"); return }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loggingInId = account.id)
            // 实时查一次在线状态：在线且当前账户不是目标账户 → 先注销旧会话（失败不阻塞，继续登录）
            val st = repo.status()
            val onlineNow = (st as? NetRepository.NetResult.Ok)?.data?.online == true
            val old = _uiState.value.accounts.firstOrNull { it.id == _uiState.value.activeId }
            if (onlineNow && old != null && old.username != account.username) {
                repo.logout(old.username)
                // 注销后稍作等待再登录，防止连续操作触发门户风控
                kotlinx.coroutines.delay(500)
            }
            when (val r = repo.login(account.username, account.password,
                NetRepository.AccountType.fromStorage(account.type))) {
                is NetRepository.NetResult.Ok -> {
                    _events.tryEmit(r.data)
                    // 登录成功 → 设为当前账户
                    persist(_uiState.value.accounts, account.id)
                    refreshStatus()
                    refreshDevices(account)
                }
                is NetRepository.NetResult.Err -> _events.tryEmit(r.message)
            }
            _uiState.value = _uiState.value.copy(loggingInId = null)
        }
    }

    fun logout(account: AuthStore.NetAccount) {
        if (!coolDown() || _uiState.value.busy) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(busy = true)
            when (val r = repo.logout(account.username)) {
                is NetRepository.NetResult.Ok -> {
                    _events.tryEmit(r.data)
                    refreshStatus()
                    refreshDevices(account)
                }
                is NetRepository.NetResult.Err -> _events.tryEmit(r.message)
            }
            _uiState.value = _uiState.value.copy(busy = false)
        }
    }

    // ---------- 自动登录 ----------

    /** 自动登录开关：开启 = 该账户设为当前并打开自动登录；关闭 = 关自动登录 */
    fun setAutoLogin(account: AuthStore.NetAccount, enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                persist(_uiState.value.accounts, account.id)
                authStore.setNetAutoLogin(true)
                if (account.password.isBlank()) _events.tryEmit("该账户未填密码，请先保存")
            } else {
                authStore.setNetAutoLogin(false)
            }
            onAutoLoginChanged?.invoke(enabled)
        }
    }

    // ---------- 设备管理（跟随详情页账户） ----------

    fun refreshDevices(account: AuthStore.NetAccount) {
        viewModelScope.launch {
            when (val r = repo.onlineDevices(account.username, account.password)) {
                is NetRepository.NetResult.Ok -> _uiState.value = _uiState.value.copy(
                    devices = r.data, devicesError = null,
                )
                is NetRepository.NetResult.Err -> _uiState.value = _uiState.value.copy(
                    devicesError = r.message,
                )
            }
        }
    }

    fun kick(account: AuthStore.NetAccount, device: LzuSelfService.NetDevice) {
        if (!coolDown() || _uiState.value.kickingId != null) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(kickingId = device.id)
            when (val r = repo.kickDevice(account.username, account.password, device)) {
                is NetRepository.NetResult.Ok -> {
                    _events.tryEmit("已下线 ${device.ip}")
                    // 下线有传播延迟，先乐观移除，再延迟复核
                    _uiState.value = _uiState.value.copy(
                        devices = _uiState.value.devices?.filterNot { it.id == device.id },
                    )
                    kotlinx.coroutines.delay(1_500)
                    refreshDevices(account)
                }
                is NetRepository.NetResult.Err -> _events.tryEmit(r.message)
            }
            _uiState.value = _uiState.value.copy(kickingId = null)
        }
    }
}

/**
 * 校园网页（多账户）：首页 = 状态卡 + 账户卡片（右侧一键登录）；
 * 点卡片进详情（编辑/保存/删除/登录/注销/自动登录/设备管理）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetScreen(
    repository: NetRepository,
    authStore: AuthStore,
    onAutoLoginChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    vm: NetViewModel = viewModel(factory = NetViewModelFactory(repository, authStore)),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        vm.onAutoLoginChanged = onAutoLoginChanged
    }
    val inDetail = state.openId != null
    BackHandler(enabled = inDetail) { vm.closeDetail() }

    // Android 13+ 常驻通知需运行时权限；无论授权结果如何都继续开启（通知仅状态展示）
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val notifPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }
    val requestNotifPermission: () -> Unit = {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.POST_NOTIFICATIONS,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val openAccount = state.accounts.firstOrNull { it.id == state.openId }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            state.openId == "new" -> "新建账户"
                            inDetail -> openAccount?.username ?: "账户详情"
                            else -> "校园网"
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (inDetail) vm.closeDetail() else onBack?.invoke() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (!inDetail) {
                        if (state.statusText.isNotEmpty()) {
                            Text(
                                state.statusText,
                                color = when (state.online) {
                                    true -> MaterialTheme.colorScheme.primary
                                    false -> MaterialTheme.colorScheme.error
                                    null -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 140.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        IconButton(onClick = {
                            vm.refreshStatus()
                            openAccount?.let(vm::refreshDevices)
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                        IconButton(onClick = vm::openNew) {
                            Icon(Icons.Default.Add, contentDescription = "添加账户")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (inDetail && state.openId != "new" && openAccount == null) {
            // 账户被删后兜底回首页
            LaunchedEffect(Unit) { vm.closeDetail() }
        } else if (inDetail) {
            NetDetailView(
                state = state,
                account = openAccount,
                isNew = state.openId == "new",
                vm = vm,
                requestNotifPermission = requestNotifPermission,
                modifier = modifier.padding(padding),
            )
        } else {
            NetHomeView(state = state, vm = vm, modifier = modifier.padding(padding))
        }
    }
}

/** 卡片首页：门户状态卡 + 账户卡片列表（右侧一键登录） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NetHomeView(state: NetUiState, vm: NetViewModel, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        // 在线状态卡
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (state.online == true)
                    MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Wifi,
                    contentDescription = null,
                    tint = if (state.online == true)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        when (state.online) {
                            true -> "校园网已连接"
                            false -> "校园网未登录"
                            null -> "未检测到校园网"
                        },
                        fontWeight = FontWeight.Medium,
                    )
                    if (state.online == true && state.onlineIp.isNotEmpty()) {
                        Text(
                            "IP：${state.onlineIp}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // 账户卡片
        state.accounts.forEach { acc ->
            val active = acc.id == state.activeId
            Card(
                onClick = { vm.openDetail(acc.id) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Wifi,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                acc.username,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (active) {
                                Spacer(Modifier.width(6.dp))
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.primary,
                                ) {
                                    Text(
                                        "当前",
                                        Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                }
                            }
                        }
                        Text(
                            NetRepository.AccountType.fromStorage(acc.type).label +
                                if (active && state.autoLogin) " · 自动登录" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    // 一键登录（不进入详情）
                    FilledTonalButton(
                        onClick = { vm.login(acc) },
                        enabled = state.loggingInId == null,
                    ) {
                        if (state.loggingInId == acc.id) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        } else {
                            Text("一键登录")
                        }
                    }
                }
            }
        }

        if (state.accounts.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "暂无账户，点击右上角 + 添加",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 添加账户入口卡
        OutlinedCard(
            onClick = vm::openNew,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(14.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(6.dp))
                Text("添加账户", color = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** 详情页：编辑凭据 + 自动登录 + 登录/注销/删除 + 设备管理 */
@Composable
private fun NetDetailView(
    state: NetUiState,
    account: AuthStore.NetAccount?,
    isNew: Boolean,
    vm: NetViewModel,
    requestNotifPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 编辑态（保存才落盘）；账户切换时重建
    var username by remember(account?.id) { mutableStateOf(account?.username.orEmpty()) }
    var password by remember(account?.id) { mutableStateOf(account?.password.orEmpty()) }
    var type by remember(account?.id) {
        mutableStateOf(NetRepository.AccountType.fromStorage(account?.type ?: "campus"))
    }
    val active = account != null && account.id == state.activeId

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        // 凭据卡
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it.trim() },
                    label = { Text("上网账号") },
                    // 账号可能带字母，用普通文本键盘
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("上网密码") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    NetRepository.AccountType.entries.forEachIndexed { i, t ->
                        SegmentedButton(
                            selected = type == t,
                            onClick = { type = t },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = i, count = NetRepository.AccountType.entries.size,
                            ),
                        ) { Text(t.label) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("自动登录（接入校园网时）", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = active && state.autoLogin,
                        enabled = account != null,
                        onCheckedChange = { enabled ->
                            if (enabled) requestNotifPermission()
                            account?.let { vm.setAutoLogin(it, enabled) }
                        },
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { vm.saveAccount(account?.id, username, password, type) },
                        modifier = Modifier.weight(1f),
                    ) { Text(if (isNew) "添加" else "保存") }
                    if (!isNew && account != null) {
                        Spacer(Modifier.width(12.dp))
                        OutlinedButton(
                            onClick = { vm.deleteAccount(account.id) },
                            modifier = Modifier.weight(1f),
                        ) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }

        if (!isNew && account != null) {
            Spacer(Modifier.height(12.dp))
            // 登录/注销卡
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Row(Modifier.fillMaxWidth().padding(16.dp)) {
                    Button(
                        onClick = { vm.login(account) },
                        enabled = !state.busy && state.loggingInId == null,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (state.loggingInId == account.id) {
                            CircularProgressIndicator(
                                Modifier.size(16.dp), strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("登录")
                    }
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(
                        onClick = { vm.logout(account) },
                        enabled = !state.busy && state.online == true,
                        modifier = Modifier.weight(1f),
                    ) { Text("注销") }
                }
            }

            Spacer(Modifier.height(12.dp))

            // 已登录设备卡
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("已登录设备", fontWeight = FontWeight.Medium)
                        IconButton(onClick = { vm.refreshDevices(account) }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "刷新设备列表",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    when {
                        state.devicesError != null -> Text(
                            state.devicesError!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        state.devices == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "加载中…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        state.devices!!.isEmpty() -> Text(
                            "当前无在线设备",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        else -> state.devices!!.forEachIndexed { i, d ->
                            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.Computer,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(d.ip, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "${d.product} · ${d.since}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (state.kickingId == d.id) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                } else {
                                    TextButton(onClick = { vm.kick(account, d) }) {
                                        Text("下线", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
