package li.lyc.lzuapp.ui.grade

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import li.lyc.lzuapp.data.api.model.GradeRecord
import li.lyc.lzuapp.data.api.model.StuXqItem
import li.lyc.lzuapp.data.repo.GradeRepository

data class GradeUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val semesters: List<StuXqItem> = emptyList(),
    /** 选中的学期下标；null = 全部学期 */
    val selected: Int? = null,
    val records: List<GradeRecord> = emptyList(),
) {
    /** 加权平均绩点：Σ(学分×绩点)/Σ学分（缺绩点的课程不参与） */
    val gpa: String? get() {
        var sumW = 0.0
        var sum = 0.0
        for (r in records) {
            val w = r.kcxf?.toDoubleOrNull() ?: continue
            val j = r.jd?.toDoubleOrNull() ?: continue
            sumW += w
            sum += w * j
        }
        if (sumW <= 0.0) return null
        return "%.2f".format(java.util.Locale.US, sum / sumW)
    }

    /** 加权平均分：Σ(学分×学分积成绩)/Σ学分（等级课按 xfjcj 换算分参与） */
    val weightedScore: String? get() {
        var sumW = 0.0
        var sum = 0.0
        for (r in records) {
            val w = r.kcxf?.toDoubleOrNull() ?: continue
            val sc = r.xfjcj?.toDoubleOrNull() ?: continue
            sumW += w
            sum += w * sc
        }
        if (sumW <= 0.0) return null
        return "%.1f".format(java.util.Locale.US, sum / sumW)
    }

    val totalCredit: String get() {
        var sum = 0.0
        for (r in records) sum += r.kcxf?.toDoubleOrNull() ?: 0.0
        return if (sum == sum.toLong().toDouble()) sum.toLong().toString()
        else "%.1f".format(java.util.Locale.US, sum)
    }
}

class GradeViewModelFactory(private val repo: GradeRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return GradeViewModel(repo) as T
    }
}

class GradeViewModel(private val repo: GradeRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(GradeUiState())
    val uiState: StateFlow<GradeUiState> = _uiState.asStateFlow()

    private var lastActionAt = 0L

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            // 学期列表失败不阻塞成绩加载
            when (val r = repo.semesters()) {
                is GradeRepository.GradeResult.Ok ->
                    _uiState.value = _uiState.value.copy(semesters = r.data)
                is GradeRepository.GradeResult.Err -> { /* 忽略，筛选条只是没有 */ }
            }
            val s = _uiState.value
            val sel = s.selected?.let { s.semesters.getOrNull(it) }
            val xn = sel?.xn?.toIntOrNull() ?: 0
            val xqm = sel?.xqm?.toIntOrNull() ?: 0
            when (val r = repo.grades(xn, xqm)) {
                is GradeRepository.GradeResult.Ok ->
                    _uiState.value = _uiState.value.copy(loading = false, records = r.data)
                is GradeRepository.GradeResult.Err ->
                    _uiState.value = _uiState.value.copy(loading = false, error = r.message)
            }
        }
    }

    fun selectSemester(index: Int?) {
        if (System.currentTimeMillis() - lastActionAt < 500) return
        lastActionAt = System.currentTimeMillis()
        if (_uiState.value.selected == index) return
        _uiState.value = _uiState.value.copy(selected = index)
        load()
    }

    fun refresh() {
        if (System.currentTimeMillis() - lastActionAt < 500) return
        lastActionAt = System.currentTimeMillis()
        load()
    }
}

/** 成绩查询页：学期筛选 + GPA 汇总 + 成绩卡片列表 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GradeScreen(
    repository: GradeRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    vm: GradeViewModel = viewModel(factory = GradeViewModelFactory(repository)),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("成绩查询") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = vm::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier.fillMaxSize().padding(padding)) {
            // 学期筛选条
            if (state.semesters.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = state.selected == null,
                        onClick = { vm.selectSemester(null) },
                        label = { Text("全部") },
                    )
                    state.semesters.forEachIndexed { i, s ->
                        FilterChip(
                            selected = state.selected == i,
                            onClick = { vm.selectSemester(i) },
                            label = { Text(s.xqsj ?: "${s.xn}年${s.xq ?: ""}") },
                        )
                    }
                }
            }

            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 2.dp)
                }
                state.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            state.error!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = vm::load) { Text("重试") }
                    }
                }
                state.records.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "暂无成绩记录",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp, end = 16.dp, bottom = 16.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        // 汇总卡：GPA + 学分 + 门数
                        Card(
                            Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                            ) {
                                SummaryItem("平均绩点", state.gpa ?: "—")
                                SummaryItem("加权平均分", state.weightedScore ?: "—")
                                SummaryItem("总学分", state.totalCredit)
                                SummaryItem("门数", state.records.size.toString())
                            }
                        }
                    }
                    // 不用自定义 key：重修课会出现 kch+学期+考试性质完全相同的记录
                    items(state.records) { r ->
                        GradeCard(r)
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GradeCard(r: GradeRecord) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    r.kcmc.orEmpty(),
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        append("${r.ksxn.orEmpty()}年${r.ksxq.orEmpty()}")
                        r.kcxf?.let { append(" · ${it}学分") }
                        r.jd?.let { append(" · 绩点$it") }
                        r.xksx?.let { append(" · $it") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val detail = listOfNotNull(
                    r.pscj?.let { "平时 $it" },
                    r.qzcj?.let { "期中 $it" },
                    r.qmcj?.let { "期末 $it" },
                ).joinToString("  ")
                if (detail.isNotEmpty()) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                r.zpcj ?: "—",
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
