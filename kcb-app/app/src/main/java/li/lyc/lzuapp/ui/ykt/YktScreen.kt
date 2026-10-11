package li.lyc.lzuapp.ui.ykt

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Payment
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 校园卡主页：余额 + 付款码（60s 自动刷新 + 手动刷新）+ 充值/账单入口。
 * 进入页面先静默校验登录态，失败显示错误与重试。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YktScreen(
    repository: li.lyc.lzuapp.data.repo.YktRepository,
    onRecharge: () -> Unit,
    onBills: () -> Unit,
    modifier: Modifier = Modifier,
    vm: YktViewModel = viewModel(factory = YktViewModelFactory(repository)),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()

    // 页面每次进入组合（切回 tab / 充值·账单返回）都静默刷新余额
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.refreshBalance() }

    // 付款码倒计时跟随页面可见性：不可见（切 tab/退后台/进子页面）时停止，
    // 恢复可见时立即刷一次码；退出组合时确保停掉
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> vm.setVisible(true)
                Lifecycle.Event.ON_PAUSE -> vm.setVisible(false)
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        // 切回 tab 重新组合时 Activity 已是 RESUMED，ON_RESUME 不会重放：注册后按当前状态补一次
        vm.setVisible(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose {
            lifecycle.removeObserver(observer)
            vm.setVisible(false)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("校园卡") },
                actions = {
                    // 错误（如确认真无网络）收敛到顶栏右侧：短文本 + 重试
                    if (state.error != null) {
                        Text(
                            state.error!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 140.dp),
                        )
                        TextButton(onClick = vm::retry) { Text("重试") }
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.checking -> Box(
                modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "正在校验登录状态…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> Column(
                modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 余额卡（只显示 M1CPU 主卡钱包）
                val wallet = state.wallets.firstOrNull()
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.AccountBalanceWallet,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                wallet?.walletName ?: "校园卡",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    wallet?.money ?: if (state.balanceLoading) "…" else "—",
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "元",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 4.dp),
                                )
                            }
                        }
                        if (state.balanceLoading) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            IconButton(onClick = vm::refreshBalance, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Refresh, contentDescription = "刷新余额", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 付款码
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "付款码",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(12.dp))
                        Box(
                            Modifier
                                // 码体尽量大（实测小了扫不出）；ZXing 自带 4 模块静区，内边距 8dp 兜底
                                .fillMaxWidth(0.96f)
                                .aspectRatio(1f)
                                .background(Color.White, RoundedCornerShape(12.dp))
                                .padding(8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            val bmp = state.qrBitmap
                            when {
                                bmp != null -> Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "付款码",
                                    modifier = Modifier.fillMaxSize(),
                                )
                                state.qrLoading -> CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 2.dp)
                                else -> Text(
                                    "付款码加载失败",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            if (bmp != null && state.qrLoading) {
                                CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 2.dp)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                "${state.countdown} 秒后自动刷新",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = { vm.refreshQr(manual = true) }) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(2.dp))
                                Text("立即刷新", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 充值 / 账单入口
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onRecharge, modifier = Modifier.weight(1f).height(46.dp)) {
                        Icon(Icons.Default.Payment, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("充值")
                    }
                    OutlinedButton(onClick = onBills, modifier = Modifier.weight(1f).height(46.dp)) {
                        Icon(Icons.Default.Receipt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("账单")
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
