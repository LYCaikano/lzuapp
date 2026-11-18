package li.lyc.lzuapp.ui.apps

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import li.lyc.lzuapp.data.AuthStore

/** 应用聚合页：低频功能（评教/查寝/校园网/成绩/电费/邮箱）的卡片入口 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AppsScreen(
    authStore: AuthStore,
    onOpenDorm: () -> Unit,
    onOpenNet: () -> Unit,
    onOpenGrade: () -> Unit,
    onOpenMail: () -> Unit,
    onOpenElec: () -> Unit,
    onOpenEval: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val authState by authStore.state.collectAsStateWithLifecycle(initialValue = null)
    val layoutViewModel: AppsLayoutViewModel = viewModel(
        factory = AppsLayoutViewModelFactory(authStore),
    )
    val listState = rememberLazyListState()
    var editing by remember { mutableStateOf(false) }
    var editedOrder by remember { mutableStateOf(AppCatalog.entries.map { it.id }) }
    var editedHidden by remember { mutableStateOf(emptySet<String>()) }
    var pendingHide by remember { mutableStateOf<AppCatalogEntry?>(null) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragStartOrder by remember { mutableStateOf<List<String>>(emptyList()) }
    var draggedTop by remember { mutableFloatStateOf(0f) }
    var dragDirection by remember { mutableIntStateOf(0) }
    var settlingId by remember { mutableStateOf<String?>(null) }
    var settlingTop by remember { mutableFloatStateOf(0f) }
    var settlingFraction by remember { mutableFloatStateOf(0f) }

    val storedOrder = AppCatalog.normalizeOrder(authState?.appOrder.orEmpty())
    val storedHidden = authState?.hiddenApps.orEmpty()
    val currentOrder = if (editing) editedOrder else storedOrder
    val currentHidden = if (editing) editedHidden else storedHidden
    val entriesById = AppCatalog.entries.associateBy { it.id }
    val visibleEntries = currentOrder.mapNotNull(entriesById::get).filterNot { it.id in currentHidden }

    fun finishDrag() {
        val id = draggingId ?: return
        settlingTop = draggedTop
        settlingFraction = 1f
        settlingId = id
        draggingId = null
        dragDirection = 0
        if (editedOrder != dragStartOrder) layoutViewModel.saveOrder(editedOrder)
    }

    fun exitEditing() {
        finishDrag()
        editing = false
    }

    fun moveDraggedItem(id: String, direction: Int) {
        val visibleIds = editedOrder.filterNot { it in editedHidden }
        val itemsInfo = listState.layoutInfo.visibleItemsInfo
        val currentInfo = itemsInfo.firstOrNull { it.key == id } ?: return
        val fromIndex = visibleIds.indexOf(id)
        // 第 0 项是提示行。等待列表按上一次的新顺序布局后再比较位置。
        if (fromIndex < 0 || currentInfo.index != fromIndex + 1) return
        val center = draggedTop + currentInfo.size / 2f
        val targets = itemsInfo.filter { item ->
            (item.index - currentInfo.index) * direction > 0 &&
                item.key in visibleIds &&
                (center - (item.offset + item.size / 2f)) * direction >= 0
        }
        val target = (if (direction > 0) targets.lastOrNull() else targets.firstOrNull()) ?: return
        val reordered = visibleIds.toMutableList()
        reordered.add(target.index - 1, reordered.removeAt(fromIndex))
        val reorderedIterator = reordered.iterator()
        editedOrder = editedOrder.map { if (it in editedHidden) it else reorderedIterator.next() }
        // 按位置保留滚动锚点，避免拖动首个可见卡片时列表跟着它跳动。
        listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
    }

    LaunchedEffect(settlingId) {
        if (settlingId == null) return@LaunchedEffect
        animate(1f, 0f, animationSpec = tween(durationMillis = 180)) { value, _ ->
            settlingFraction = value
        }
        settlingId = null
    }

    val autoScrollEdge = with(LocalDensity.current) { 48.dp.toPx() }
    val autoScrollStep = with(LocalDensity.current) { 12.dp.toPx() }
    LaunchedEffect(draggingId) {
        val id = draggingId ?: return@LaunchedEffect
        while (draggingId == id) {
            withFrameNanos { }
            val direction = dragDirection
            val currentInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }
            if (direction != 0 && currentInfo != null) {
                val viewportStart = listState.layoutInfo.viewportStartOffset.toFloat()
                val viewportEnd = listState.layoutInfo.viewportEndOffset.toFloat()
                val itemBottom = draggedTop + currentInfo.size
                val scrollAmount = when {
                    direction > 0 && itemBottom > viewportEnd - autoScrollEdge ->
                        minOf(autoScrollStep, itemBottom - viewportEnd + autoScrollEdge)
                    direction < 0 && draggedTop < viewportStart + autoScrollEdge ->
                        -minOf(autoScrollStep, viewportStart + autoScrollEdge - draggedTop)
                    else -> 0f
                }
                if (scrollAmount != 0f) listState.scrollBy(scrollAmount)
                if (draggingId == id) moveDraggedItem(id, direction)
            }
        }
    }

    BackHandler(enabled = editing, onBack = ::exitEditing)

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(if (editing) "管理应用" else "应用") },
                actions = {
                    if (editing) {
                        TextButton(onClick = ::exitEditing) {
                            Text("完成")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (authState == null) {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "hint") {
                    Text(
                        if (editing) "按住右侧手柄拖动排序，点击眼睛图标隐藏应用"
                        else "长按卡片可调整顺序或隐藏应用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (visibleEntries.isEmpty()) {
                    item(key = "empty") {
                        Box(
                            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 80.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "所有应用均已隐藏\n可在“设置 → 应用管理”中恢复",
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                items(visibleEntries, key = { it.id }) { entry ->
                    val presentation = appPresentation(entry.id)
                    val onOpen = when (entry.id) {
                        "eval" -> onOpenEval
                        "dorm" -> onOpenDorm
                        "net" -> onOpenNet
                        "grade" -> onOpenGrade
                        "elec" -> onOpenElec
                        "mail" -> onOpenMail
                        else -> error("Unknown app id: ${entry.id}")
                    }
                    AppCard(
                        icon = presentation.icon,
                        title = entry.title,
                        subtitle = presentation.subtitle,
                        editing = editing,
                        dragging = draggingId == entry.id,
                        onClick = onOpen,
                        onLongClick = {
                            editedOrder = storedOrder
                            editedHidden = storedHidden
                            editing = true
                        },
                        onHide = { pendingHide = entry },
                        onDragStart = {
                            val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == entry.id }
                            if (draggingId == null && item != null) {
                                val carryOver = if (settlingId == entry.id) {
                                    (settlingTop - item.offset) * settlingFraction
                                } else {
                                    0f
                                }
                                settlingId = null
                                dragStartOrder = editedOrder
                                draggedTop = item.offset + carryOver
                                draggingId = entry.id
                                true
                            } else {
                                false
                            }
                        },
                        onDrag = { amount ->
                            if (draggingId == entry.id && amount != 0f) {
                                draggedTop += amount
                                dragDirection = if (amount > 0f) 1 else -1
                                moveDraggedItem(entry.id, dragDirection)
                            }
                        },
                        onDragEnd = {
                            if (draggingId == entry.id) finishDrag()
                        },
                        modifier = Modifier
                            .zIndex(if (entry.id == draggingId || entry.id == settlingId) 1f else 0f)
                            .then(
                                if (entry.id == draggingId || entry.id == settlingId) {
                                    Modifier.graphicsLayer {
                                        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == entry.id }
                                        translationY = when {
                                            item == null -> 0f
                                            entry.id == draggingId -> draggedTop - item.offset
                                            entry.id == settlingId -> (settlingTop - item.offset) * settlingFraction
                                            else -> 0f
                                        }
                                    }
                                } else {
                                    Modifier.animateItem(placementSpec = tween(durationMillis = 180))
                                }
                            ),
                    )
                }
            }
        }
    }

    pendingHide?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingHide = null },
            title = { Text("隐藏${entry.title}？") },
            text = { Text("隐藏后可在“设置 → 应用管理”中恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        editedHidden = editedHidden + entry.id
                        layoutViewModel.hideApp(entry.id)
                        if (draggingId == entry.id) finishDrag()
                        pendingHide = null
                    },
                ) {
                    Text("隐藏")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingHide = null }) {
                    Text("取消")
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    editing: Boolean,
    dragging: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onHide: () -> Unit,
    onDragStart: () -> Boolean,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val startDrag by rememberUpdatedState(onDragStart)
    val dragBy by rememberUpdatedState(onDrag)
    val endDrag by rememberUpdatedState(onDragEnd)
    Card(
        modifier
            .fillMaxWidth()
            .combinedClickable(
                enabled = !editing,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (dragging) MaterialTheme.colorScheme.surfaceContainerHigh
            else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(
                start = 16.dp,
                end = if (editing) 8.dp else 16.dp,
                top = if (editing) 10.dp else 16.dp,
                bottom = if (editing) 10.dp else 16.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(if (editing) 12.dp else 16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (editing) {
                IconButton(onClick = onHide) {
                    Icon(
                        Icons.Default.VisibilityOff,
                        contentDescription = "隐藏$title",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .pointerInput(Unit) {
                            var ownsDrag = false
                            detectVerticalDragGestures(
                                onDragStart = { ownsDrag = startDrag() },
                                onDragEnd = {
                                    if (ownsDrag) endDrag()
                                    ownsDrag = false
                                },
                                onDragCancel = {
                                    if (ownsDrag) endDrag()
                                    ownsDrag = false
                                },
                                onVerticalDrag = { _, dragAmount ->
                                    if (ownsDrag) dragBy(dragAmount)
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.DragHandle,
                        contentDescription = "按住拖动调整$title 顺序",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private sealed interface AppLayoutRequest {
    data class SaveOrder(val order: List<String>) : AppLayoutRequest
    data class HideApp(val appId: String) : AppLayoutRequest
}

private class AppsLayoutViewModelFactory(
    private val authStore: AuthStore,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return AppsLayoutViewModel(authStore) as T
    }
}

private class AppsLayoutViewModel(
    private val authStore: AuthStore,
) : ViewModel() {
    private val requests = Channel<AppLayoutRequest>(Channel.UNLIMITED)

    init {
        viewModelScope.launch {
            for (request in requests) {
                when (request) {
                    is AppLayoutRequest.SaveOrder -> withContext(NonCancellable) {
                        authStore.saveAppOrder(request.order)
                    }
                    is AppLayoutRequest.HideApp -> withContext(NonCancellable) {
                        authStore.hideApp(request.appId)
                    }
                }
            }
        }
    }

    fun saveOrder(order: List<String>) {
        val request = AppLayoutRequest.SaveOrder(AppCatalog.normalizeOrder(order))
        requests.trySend(request).getOrThrow()
    }

    fun hideApp(appId: String) {
        requests.trySend(AppLayoutRequest.HideApp(appId)).getOrThrow()
    }
}

private data class AppPresentation(val icon: ImageVector, val subtitle: String)

private fun appPresentation(id: String) = when (id) {
    "eval" -> AppPresentation(Icons.Default.School, "期中/期末评教 · 课程评价")
    "dorm" -> AppPresentation(Icons.Default.Bedtime, "晚点名签到 · 签到记录")
    "net" -> AppPresentation(Icons.Default.Wifi, "自动登录 · 在线设备管理")
    "grade" -> AppPresentation(Icons.Default.School, "各科成绩 · 学分绩点")
    "elec" -> AppPresentation(Icons.Default.Bolt, "剩余电量查询 · 在线缴费")
    "mail" -> AppPresentation(Icons.Default.Email, "兰大邮箱")
    else -> error("Unknown app id: $id")
}
