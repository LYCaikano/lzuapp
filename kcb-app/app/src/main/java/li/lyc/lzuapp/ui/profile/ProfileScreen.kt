package li.lyc.lzuapp.ui.profile

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import li.lyc.lzuapp.data.AuthStore
import li.lyc.lzuapp.data.repo.CourseRepository
import li.lyc.lzuapp.data.repo.FdmRepository
import li.lyc.lzuapp.theme.ThemeColor
import li.lyc.lzuapp.theme.ThemeMode
import li.lyc.lzuapp.ui.common.MadeByLyc
import li.lyc.lzuapp.ui.login.AgreementDialog
import kotlinx.coroutines.launch

private val THEME_OPTIONS = listOf(
    ThemeMode.SYSTEM to "自动",
    ThemeMode.LIGHT to "浅色",
    ThemeMode.DARK to "深色",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    authStore: AuthStore,
    repository: CourseRepository,
    fdmRepository: FdmRepository,
    modifier: Modifier = Modifier,
) {
    val auth by authStore.state.collectAsStateWithLifecycle(initialValue = null)
    var confirmLogout by remember { mutableStateOf(false) }
    var showAgreement by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (showAgreement) {
        AgreementDialog(onDismiss = { showAgreement = false })
    }

    Scaffold(
        // 系统栏 inset 由 TopAppBar 自行处理，避免内容底部重复空白
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("我的") }) },
        modifier = modifier,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // 内容超过屏高时可下滑（配色行 + 退出按钮不再被挤出屏幕）
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            Spacer(Modifier.height(16.dp))

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    InfoRow("学号", auth?.xykh?.ifBlank { null } ?: auth?.username.orEmpty())
                    InfoRow("姓名", auth?.userName?.ifBlank { null } ?: "—")
                    InfoRow("学院", auth?.college?.ifBlank { null } ?: "—")
                    InfoRow("登录状态", if (auth?.isLoggedIn == true) "已登录" else "未登录")
                }
            }

            Spacer(Modifier.height(12.dp))
            // 刷新登录状态：重新登录并补拉用户信息（登录态被挤掉/信息缺失时自愈）
            var checkingLogin by remember { mutableStateOf(false) }
            var loginCheckOk by remember { mutableStateOf<Boolean?>(null) }
            var loginCheckMsg by remember { mutableStateOf("") }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        checkingLogin = true
                        loginCheckOk = null
                        val r = repository.refreshLoginState()
                        checkingLogin = false
                        loginCheckOk = r is li.lyc.lzuapp.data.repo.SyncResult.Success
                        loginCheckMsg = if (loginCheckOk == true) "登录状态正常"
                        else (r as? li.lyc.lzuapp.data.repo.SyncResult.Failed)?.message ?: "刷新失败"
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
            loginCheckOk?.let { ok ->
                Text(
                    loginCheckMsg,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            Spacer(Modifier.height(16.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
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

            Spacer(Modifier.height(16.dp))
            // 左侧版本号；右侧用户协议 + 署名（♥ made by lyc 内置彩蛋手势：连点 12 次再长按 2.4s，回调预留）
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
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
                ) {
                    Text(
                        "用户协议",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                MadeByLyc()
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { confirmLogout = true },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Text("退出登录", color = MaterialTheme.colorScheme.error)
            }
            // 底部留白，避免按钮贴住导航栏（可滚动时作为末端间距）
            Spacer(Modifier.height(24.dp))
        }
    }

    // 退出登录二次确认
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("退出登录") },
            text = { Text("退出后将清除课程缓存、校历、校园卡会话及全部个人数据，仅保留学号用于下次登录预填。确定退出吗？") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    scope.launch {
                        // 一并清查寝内存会话（cookie 罐），防换账号后串号
                        fdmRepository.logout()
                        repository.logout()
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
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
