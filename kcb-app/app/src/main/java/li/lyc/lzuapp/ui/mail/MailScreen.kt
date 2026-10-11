package li.lyc.lzuapp.ui.mail

import android.annotation.SuppressLint
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Drafts
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Report
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import li.lyc.lzuapp.data.api.model.MailAttachment
import li.lyc.lzuapp.data.api.model.MailDetail
import li.lyc.lzuapp.data.api.model.MailFlags
import li.lyc.lzuapp.data.api.model.MailFolder
import li.lyc.lzuapp.data.api.model.MailFolderStats
import li.lyc.lzuapp.data.api.model.MailMessage
import li.lyc.lzuapp.data.repo.MailRepository

private const val PAGE_SIZE = 20

/** 文件夹卡片展示顺序：收件箱/已发送/草稿箱/已删除/垃圾邮件/病毒邮件（服务端 id 顺序不同） */
private val FOLDER_ORDER = listOf(1, 3, 2, 4, 5, 6)

private fun folderIcon(fid: Int): ImageVector = when (fid) {
    1 -> Icons.Default.Inbox
    2 -> Icons.Default.Drafts
    3 -> Icons.AutoMirrored.Filled.Send
    4 -> Icons.Default.Delete
    5 -> Icons.Default.Report
    6 -> Icons.Default.GppBad
    else -> Icons.Default.Email
}

/** 系统文件夹名统一简体（账号服务端语言是繁体，按 id 映射；自定义文件夹保留原名） */
private fun folderDisplayName(f: MailFolder): String = when (f.id) {
    1 -> "收件箱"
    2 -> "草稿箱"
    3 -> "已发送"
    4 -> "已删除"
    5 -> "垃圾邮件"
    6 -> "病毒邮件"
    else -> f.name
}

data class MailUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val account: String = "",
    val folders: List<MailFolder> = emptyList(),
    /** fid -> 统计（邮件数/未读数） */
    val stats: Map<Int, MailFolderStats> = emptyMap(),
    /** 当前打开的文件夹；null = 文件夹首页 */
    val currentFid: Int? = null,
    val messages: List<MailMessage> = emptyList(),
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
    val listLoading: Boolean = false,
    /** 打开的邮件详情；非空即显示详情视图 */
    val detail: MailDetail? = null,
    val detailId: String? = null,
    val detailLoading: Boolean = false,
    val detailError: String? = null,
    val composeOpen: Boolean = false,
    val sending: Boolean = false,
    val deleting: Boolean = false,
    /** 一次性 toast 消息 */
    val toast: String? = null,
)

class MailViewModelFactory(private val repo: MailRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return MailViewModel(repo) as T
    }
}

class MailViewModel(private val repo: MailRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(MailUiState())
    val uiState: StateFlow<MailUiState> = _uiState.asStateFlow()

    private var lastActionAt = 0L
    private fun cooldown(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastActionAt < 500) return false
        lastActionAt = now
        return true
    }

    init {
        loadFolders()
    }

    fun toastShown() {
        _uiState.value = _uiState.value.copy(toast = null)
    }

    fun loadFolders() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            // 账号信息失败不阻塞主流程
            when (val a = repo.account()) {
                is MailRepository.MailResult.Ok ->
                    _uiState.value = _uiState.value.copy(account = a.data.email)
                is MailRepository.MailResult.Err -> {}
            }
            when (val f = repo.folders()) {
                is MailRepository.MailResult.Ok -> {
                    val (folders, stats) = f.data
                    _uiState.value = _uiState.value.copy(
                        loading = false, folders = folders, stats = stats,
                    )
                }
                is MailRepository.MailResult.Err ->
                    _uiState.value = _uiState.value.copy(loading = false, error = f.message)
            }
        }
    }

    fun openFolder(fid: Int) {
        if (!cooldown()) return
        _uiState.value = _uiState.value.copy(
            currentFid = fid, messages = emptyList(), listLoading = true, error = null,
        )
        viewModelScope.launch { loadPage(start = 0) }
    }

    fun backToFolders() {
        _uiState.value = _uiState.value.copy(
            currentFid = null, messages = emptyList(), detail = null,
            detailId = null, detailError = null,
        )
        // 回首页顺手刷新未读数
        loadFolders()
    }

    private suspend fun loadPage(start: Int) {
        val fid = _uiState.value.currentFid ?: return
        when (val r = repo.messages(fid, start, PAGE_SIZE)) {
            is MailRepository.MailResult.Ok -> {
                val merged = if (start == 0) r.data else _uiState.value.messages + r.data
                _uiState.value = _uiState.value.copy(
                    loading = false, listLoading = false, loadingMore = false,
                    messages = merged.distinctBy { it.id },
                    hasMore = r.data.size >= PAGE_SIZE,
                )
            }
            is MailRepository.MailResult.Err ->
                _uiState.value = _uiState.value.copy(
                    loading = false, listLoading = false, loadingMore = false,
                    error = if (start == 0) r.message else _uiState.value.error,
                )
        }
    }

    fun loadMore() {
        val s = _uiState.value
        if (s.loadingMore || !s.hasMore || s.listLoading) return
        if (!cooldown()) return
        _uiState.value = s.copy(loadingMore = true)
        viewModelScope.launch { loadPage(start = s.messages.size) }
    }

    fun refresh() {
        if (!cooldown()) return
        if (_uiState.value.currentFid == null) {
            loadFolders()
        } else {
            _uiState.value = _uiState.value.copy(listLoading = true, error = null)
            viewModelScope.launch { loadPage(start = 0) }
        }
    }

    /** 打开邮件详情；读完后把列表里那封标记为已读 */
    fun open(id: String) {
        if (!cooldown()) return
        _uiState.value = _uiState.value.copy(
            detail = null, detailId = id, detailLoading = true, detailError = null,
        )
        viewModelScope.launch {
            when (val r = repo.read(id)) {
                is MailRepository.MailResult.Ok -> _uiState.value = _uiState.value.copy(
                    detail = r.data, detailLoading = false,
                    messages = _uiState.value.messages.map {
                        if (it.id == id) it.copy(
                            flags = (it.flags ?: MailFlags()).copy(read = true),
                        ) else it
                    },
                )
                is MailRepository.MailResult.Err -> _uiState.value = _uiState.value.copy(
                    detailLoading = false, detailError = r.message,
                )
            }
        }
    }

    fun closeDetail() {
        _uiState.value = _uiState.value.copy(
            detail = null, detailId = null, detailLoading = false, detailError = null,
        )
    }

    /** 删除当前打开的邮件（移入已删除），完成后回列表并刷新 */
    fun deleteCurrent() {
        val id = _uiState.value.detailId ?: return
        if (!cooldown()) return
        _uiState.value = _uiState.value.copy(deleting = true)
        viewModelScope.launch {
            when (repo.moveToTrash(id)) {
                is MailRepository.MailResult.Ok -> {
                    closeDetail()
                    _uiState.value = _uiState.value.copy(
                        deleting = false, toast = "已移入已删除",
                        messages = _uiState.value.messages.filter { it.id != id },
                    )
                    refresh()
                }
                is MailRepository.MailResult.Err ->
                    _uiState.value = _uiState.value.copy(deleting = false, toast = "删除失败，请重试")
            }
        }
    }

    fun openCompose() {
        if (!cooldown()) return
        _uiState.value = _uiState.value.copy(composeOpen = true)
    }

    fun closeCompose() {
        _uiState.value = _uiState.value.copy(composeOpen = false, sending = false)
    }

    fun send(to: String, subject: String, content: String) {
        if (_uiState.value.sending || !cooldown()) return
        val recipients = to.split(',', ';', '，', '；', ' ', '\n')
            .map { it.trim() }.filter { it.isNotEmpty() }
        if (recipients.isEmpty() || recipients.any { !it.contains("@") }) {
            _uiState.value = _uiState.value.copy(toast = "收件人地址不正确")
            return
        }
        if (content.isBlank()) {
            _uiState.value = _uiState.value.copy(toast = "正文不能为空")
            return
        }
        _uiState.value = _uiState.value.copy(sending = true)
        viewModelScope.launch {
            // 纯文本正文转简单 html
            val html = content
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .split("\n").joinToString("") { "<p>$it</p>" }
            when (repo.send(recipients, subject.trim(), html)) {
                is MailRepository.MailResult.Ok -> {
                    _uiState.value = _uiState.value.copy(sending = false, composeOpen = false)
                    _uiState.value = _uiState.value.copy(toast = "发送成功")
                }
                is MailRepository.MailResult.Err ->
                    _uiState.value = _uiState.value.copy(sending = false, toast = "发送失败，请重试")
            }
        }
    }
}

/** 从 `"姓名" <addr>` 提取显示名 */
private fun displaySender(raw: String): String {
    val m = Regex("\"?([^\"<>]+)\"?\\s*<[^>]+>").find(raw.trim())
    return m?.groupValues?.get(1)?.trim()?.ifBlank { null } ?: raw.trim()
}

/** "2026-09-08 11:57:05" -> 当天 "11:57"，同年 "09-08"，跨年 "2026-09-08" */
private fun formatMailDate(raw: String): String {
    if (raw.length < 10) return raw
    return try {
        val cal = java.util.Calendar.getInstance()
        val today = "%04d-%02d-%02d".format(
            cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
        )
        val year = today.substring(0, 4)
        when {
            raw.startsWith(today) -> raw.substring(11, 16)
            raw.startsWith(year) -> raw.substring(5, 10)
            else -> raw.substring(0, 10)
        }
    } catch (e: Exception) {
        raw.take(10)
    }
}

private fun formatSize(size: Long): String = when {
    size >= 1024 * 1024 -> "%.1fMB".format(java.util.Locale.US, size / 1048576.0)
    size >= 1024 -> "%.0fKB".format(java.util.Locale.US, size / 1024.0)
    else -> "${size}B"
}

/**
 * 邮箱页：原生 UI 直连 Coremail JSON API。
 * 首页 = 文件夹卡片；进入文件夹 = 邮件列表；邮件详情可删除（移入已删除）；
 * 首页右下角 FAB 写邮件。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailScreen(
    repository: MailRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    vm: MailViewModel = viewModel(factory = MailViewModelFactory(repository)),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val inList = state.currentFid != null
    val detailOpen = state.detail != null || state.detailLoading || state.detailError != null

    BackHandler(enabled = state.composeOpen) { vm.closeCompose() }
    BackHandler(enabled = !state.composeOpen && detailOpen) { vm.closeDetail() }
    BackHandler(enabled = !state.composeOpen && !detailOpen && inList) { vm.backToFolders() }

    val context = LocalContext.current
    LaunchedEffect(state.toast) {
        state.toast?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); vm.toastShown() }
    }

    val folderName = state.folders.firstOrNull { it.id == state.currentFid }
        ?.let { folderDisplayName(it) } ?: ""
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            when {
                                state.composeOpen -> "写邮件"
                                detailOpen -> "邮件详情"
                                inList -> folderName
                                else -> "邮箱"
                            },
                        )
                        if (!inList && !state.composeOpen && state.account.isNotEmpty()) {
                            Text(
                                state.account,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        when {
                            state.composeOpen -> vm.closeCompose()
                            detailOpen -> vm.closeDetail()
                            inList -> vm.backToFolders()
                            else -> onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    when {
                        detailOpen && state.detail != null -> IconButton(
                            onClick = { showDeleteConfirm = true },
                            enabled = !state.deleting,
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "删除")
                        }
                        !detailOpen && !state.composeOpen -> IconButton(onClick = vm::refresh) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                        else -> {}
                    }
                },
            )
        },
        floatingActionButton = {
            if (!inList && !detailOpen && !state.composeOpen && !state.loading && state.error == null) {
                FloatingActionButton(onClick = vm::openCompose) {
                    Icon(Icons.Default.Create, contentDescription = "写邮件")
                }
            }
        },
    ) { padding ->
        Box(modifier.fillMaxSize().padding(padding)) {
            when {
                state.composeOpen -> ComposeView(state, vm)
                detailOpen -> MailDetailView(state, vm)
                inList -> MailListView(state, vm)
                else -> FolderHomeView(state, vm)
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除邮件") },
            text = { Text("将这封邮件移入「已删除」？") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    vm.deleteCurrent()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            },
        )
    }
}

/** 文件夹首页：卡片列表 + 未读数 */
@Composable
private fun FolderHomeView(state: MailUiState, vm: MailViewModel) {
    when {
        state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 2.dp)
        }
        state.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    state.error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = vm::loadFolders) { Text("重试") }
            }
        }
        else -> {
            val ordered = state.folders.sortedBy {
                FOLDER_ORDER.indexOf(it.id).let { i -> if (i < 0) Int.MAX_VALUE else i }
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(ordered, key = { it.id }) { f ->
                    FolderCard(f, state.stats[f.id], onClick = { vm.openFolder(f.id) })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderCard(f: MailFolder, stats: MailFolderStats?, onClick: () -> Unit) {
    val unread = stats?.unreadMessageCount ?: 0
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                folderIcon(f.id),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(14.dp))
            Text(
                folderDisplayName(f),
                Modifier.weight(1f),
                fontWeight = FontWeight.Medium,
            )
            if (unread > 0) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Text(
                        "$unread 未读",
                        Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                Spacer(Modifier.width(8.dp))
            }
            Text(
                "${stats?.messageCount ?: 0} 封",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 文件夹内邮件列表 */
@Composable
private fun MailListView(state: MailUiState, vm: MailViewModel) {
    when {
        state.listLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 2.dp)
        }
        state.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    state.error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = vm::refresh) { Text("重试") }
            }
        }
        state.messages.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "暂无邮件",
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
            items(state.messages, key = { it.id }) { m ->
                MailCard(m, onClick = { vm.open(m.id) })
            }
            if (state.hasMore) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                        if (state.loadingMore) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            TextButton(onClick = vm::loadMore) { Text("加载更多") }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MailCard(m: MailMessage, onClick: () -> Unit) {
    val unread = m.flags?.read == false
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (unread) {
                Surface(
                    Modifier.size(8.dp),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primary,
                ) {}
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    displaySender(m.from),
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    m.subject.ifBlank { "(无主题)" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                    color = if (unread) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                formatMailDate(m.receivedDate.ifBlank { m.sentDate }),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 邮件详情：头部信息 + 附件 + 正文（html WebView / 纯文本） */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MailDetailView(state: MailUiState, vm: MailViewModel) {
    when {
        state.detailLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 2.dp)
        }
        state.detailError != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    state.detailError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = vm::closeDetail) { Text("返回列表") }
            }
        }
        state.detail != null -> {
            val d = state.detail
            Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        d.subject.ifBlank { "(无主题)" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "发件人：${d.from.joinToString("；")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (d.to.isNotEmpty()) {
                        Text(
                            "收件人：${d.to.joinToString("；")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "时间：${d.sentDate}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (d.attachments.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            d.attachments.forEach { AttachmentChip(it) }
                        }
                    }
                }
                HorizontalDivider()
                val html = d.bodyHtml
                if (html != null) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = false
                                loadDataWithBaseURL(
                                    "https://mail.lzu.edu.cn/coremail/",
                                    "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                                        "<style>img{max-width:100%;height:auto}</style>" + html,
                                    "text/html", "utf-8", null,
                                )
                            }
                        },
                        onRelease = { it.stopLoading(); it.destroy() },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                } else {
                    SelectionContainer(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                    ) {
                        Text(
                            d.bodyText ?: "(无正文)",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentChip(a: MailAttachment) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            "${a.displayName}（${formatSize(a.size)}）",
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/** 写邮件：收件人 / 主题 / 正文 + 底部发送按钮 */
@Composable
private fun ComposeView(state: MailUiState, vm: MailViewModel) {
    var to by remember { mutableStateOf("") }
    var subject by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        OutlinedTextField(
            value = to,
            onValueChange = { to = it },
            label = { Text("收件人（多个用逗号分隔）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = subject,
            onValueChange = { subject = it },
            label = { Text("主题") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = content,
            onValueChange = { content = it },
            label = { Text("正文") },
            minLines = 8,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        androidx.compose.material3.Button(
            onClick = { vm.send(to, subject, content) },
            enabled = !state.sending,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.sending) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("发送中…")
            } else {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("发送")
            }
        }
    }
}
