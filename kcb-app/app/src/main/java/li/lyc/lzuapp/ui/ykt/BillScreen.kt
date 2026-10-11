package li.lyc.lzuapp.ui.ykt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import li.lyc.lzuapp.data.repo.YktBillItem
import li.lyc.lzuapp.data.repo.YktRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

data class BillUiState(
    val items: List<YktBillItem> = emptyList(),
    val typeNum: String = "-1",
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val error: String? = null,
)

class BillViewModelFactory(private val repo: YktRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return BillViewModel(repo) as T
    }
}

class BillViewModel(private val repo: YktRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(BillUiState())
    val uiState: StateFlow<BillUiState> = _uiState.asStateFlow()

    private var lastActionAt = 0L
    private var reloadJob: Job? = null
    private var loadMoreJob: Job? = null

    init {
        reload()
    }

    private fun cooldown(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastActionAt < 500) return false
        lastActionAt = now
        return true
    }

    fun setType(typeNum: String) {
        if (_uiState.value.typeNum == typeNum) return
        _uiState.value = _uiState.value.copy(typeNum = typeNum)
        // 类型切换强制重新加载（冷却只对同类型手动刷新生效），否则 chip 显示新筛选、列表是旧数据
        reload(force = true)
    }

    fun reload() = reload(force = false)

    private fun reload(force: Boolean) {
        if (!force && !cooldown() && _uiState.value.items.isNotEmpty()) return
        // 取消在途 loadMore/上一次 reload，防旧类型/旧页数据随后拼进新列表
        loadMoreJob?.cancel()
        reloadJob?.cancel()
        val typeNum = _uiState.value.typeNum
        reloadJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, loadingMore = false, error = null)
            when (val r = repo.bills(typeNum, beginRecNum = 0)) {
                is YktRepository.YktResult.Ok -> {
                    // 落地前比对快照：期间类型又被切换则丢弃本次结果
                    if (_uiState.value.typeNum == typeNum) {
                        _uiState.value = _uiState.value.copy(
                            items = r.data.first, hasMore = r.data.second, loading = false,
                        )
                    }
                }
                is YktRepository.YktResult.Err -> {
                    if (_uiState.value.typeNum == typeNum) {
                        _uiState.value = _uiState.value.copy(loading = false, error = r.message)
                    }
                }
            }
        }
    }

    fun loadMore() {
        val s = _uiState.value
        if (s.loading || s.loadingMore || !s.hasMore) return
        // 请求携带 (类型, 页码) 快照，落地前比对当前状态，不匹配丢弃
        val typeNum = s.typeNum
        val beginRecNum = s.items.size
        loadMoreJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loadingMore = true)
            when (val r = repo.bills(typeNum, beginRecNum = beginRecNum)) {
                is YktRepository.YktResult.Ok -> {
                    val cur = _uiState.value
                    if (cur.typeNum == typeNum && cur.loadingMore) {
                        _uiState.value = cur.copy(
                            items = cur.items + r.data.first,
                            hasMore = r.data.second, loadingMore = false,
                        )
                    }
                }
                is YktRepository.YktResult.Err -> {
                    if (_uiState.value.typeNum == typeNum) {
                        _uiState.value = _uiState.value.copy(loadingMore = false, error = r.message)
                    }
                }
            }
        }
    }
}

private val BILL_TYPES = listOf("-1" to "全部", "-2" to "消费", "-3" to "充值")

private fun monthLabel(month: String): String =
    if (month.length == 6) "${month.substring(0, 4)}年${month.substring(4).trimStart('0')}月" else month

/** 账单页：按月分组的交易列表，类型筛选，滚动到底自动加载更多 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillScreen(
    repository: YktRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    vm: BillViewModel = viewModel(factory = BillViewModelFactory(repository)),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // 滚动到距底部 5 项内才自动加载更多（任意滚动都翻页会白白打满请求）
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index
            lastVisible != null && info.totalItemsCount > 0 &&
                lastVisible >= info.totalItemsCount - 5
        }
            .distinctUntilChanged()
            .filter { it }
            .collect { vm.loadMore() }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("账单") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier.fillMaxSize().padding(padding)) {
            // 类型筛选
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BILL_TYPES.forEach { (num, label) ->
                    FilterChip(
                        selected = state.typeNum == num,
                        onClick = { vm.setType(num) },
                        label = { Text(label) },
                    )
                }
            }

            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                }
                state.items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            state.error ?: "暂无账单记录",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (state.error != null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (state.error != null) {
                            TextButton(onClick = vm::reload) { Text("重试") }
                        }
                    }
                }
                else -> {
                    // 按月分组
                    val grouped = state.items.groupBy { it.month }
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        grouped.forEach { (month, items) ->
                            item(key = "h$month") {
                                Text(
                                    monthLabel(month),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                                )
                            }
                            items.forEachIndexed { index, bill ->
                                // payRecNum 为空时退化 key 拼上 index 兜底：
                                // 同日同额同类型两笔若 key 相同会 duplicate key 崩溃
                                item(key = bill.payRecNum.ifEmpty { "${bill.month}${bill.day}${bill.time}${bill.money}#$index" }) {
                                    BillRow(bill)
                                    HorizontalDivider(
                                        thickness = 0.5.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant,
                                    )
                                }
                            }
                        }
                        if (state.loadingMore) {
                            item {
                                Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                }
                            }
                        } else if (!state.hasMore && state.items.isNotEmpty()) {
                            item {
                                Text(
                                    "— 没有更多了 —",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BillRow(bill: YktBillItem) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(bill.typeName.ifBlank { "交易" }, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                "${bill.day} ${bill.time}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val expense = bill.amount < 0
        Text(
            (if (expense) "" else "+") + bill.money,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (expense) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.primary,
        )
    }
}
