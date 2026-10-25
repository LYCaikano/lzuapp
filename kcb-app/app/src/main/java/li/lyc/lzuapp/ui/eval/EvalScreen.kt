package li.lyc.lzuapp.ui.eval

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import li.lyc.lzuapp.data.api.model.EvalCourse
import li.lyc.lzuapp.data.api.model.EvalQuestion
import li.lyc.lzuapp.data.api.model.EvalTask
import li.lyc.lzuapp.data.repo.EvalRepository

/** 已完成/已评标签绿 */
private val DoneGreen = Color(0xFF43A047)

/**
 * 学生评教页：任务 → 课程 → 合讲教师 → 表单（普通课程跳过教师列表）。
 * 仅支持进行中 + 打分制任务；已评内容按任务规则只读或回填修改。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EvalScreen(
    repository: EvalRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    vm: EvalViewModel = viewModel(factory = EvalViewModelFactory(repository)),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    BackHandler(enabled = state.batch.running || (state.page != EvalPage.TASKS && !state.submitting)) {
        if (!state.batch.running) vm.back()
    }

    LaunchedEffect(state.toast) {
        state.toast?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); vm.toastShown() }
    }
    LaunchedEffect(state.message) {
        state.message?.let { snackbarHostState.showSnackbar(it); vm.messageShown() }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("学生评教")
                        val subtitle = when (state.page) {
                            EvalPage.COURSES -> state.task?.taskname
                            EvalPage.TEACHERS, EvalPage.FORM -> state.course?.coursename
                            EvalPage.TASKS -> null
                        }
                        if (!subtitle.isNullOrEmpty()) {
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = { if (state.page == EvalPage.TASKS) onBack() else vm.back() },
                        enabled = !state.submitting && !state.batch.running,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (state.page != EvalPage.FORM) {
                        IconButton(onClick = vm::retry, enabled = !state.batch.running) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                if (state.page == EvalPage.TASKS || state.page == EvalPage.COURSES) {
                    BatchBar(state, vm)
                }
                Box(Modifier.weight(1f)) {
                    when (state.page) {
                        EvalPage.TASKS -> TaskListView(state, vm)
                        EvalPage.COURSES -> CourseListView(state, vm)
                        EvalPage.TEACHERS -> TeacherListView(state, vm)
                        EvalPage.FORM -> FormView(state, vm)
                    }
                }
            }
        }
    }

    if (state.showBatchConfirm) {
        AlertDialog(
            onDismissRequest = vm::dismissBatch,
            title = { Text("一键评教所有课程和老师？") },
            text = {
                Text("${if (state.page == EvalPage.TASKS) "所有进行中的打分任务" else "当前任务"}中，未评课程和合讲教师将按现有一键高分规则填写并直接提交，评语使用默认中文内容；已评对象会跳过。遇到不支持的题型或提交失败会暂停，可随时停止后续评价。")
            },
            confirmButton = { TextButton(onClick = vm::startBatch) { Text("开始评教并提交") } },
            dismissButton = { TextButton(onClick = vm::dismissBatch) { Text("取消") } },
        )
    }

    if (state.showAutoFillConfirm) {
        AlertDialog(
            onDismissRequest = vm::dismissAutoFill,
            title = { Text("覆盖当前填写？") },
            text = {
                Text("一键填写会覆盖当前计分题和评语；禁止全满分时会随机降档2或3道，且候选不足2道时不会填写。操作仅修改表单，不会自动提交。")
            },
            confirmButton = {
                TextButton(onClick = vm::confirmAutoFill) { Text("覆盖填写") }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissAutoFill) { Text("取消") }
            },
        )
    }
}

@Composable
private fun BatchBar(state: EvalUiState, vm: EvalViewModel) {
    val eligible = if (state.page == EvalPage.TASKS) {
        state.tasks.any { it.currentStatus == "进行中" && it.pjfs == "打分" }
    } else {
        state.task?.currentStatus == "进行中" && state.task.pjfs == "打分"
    }
    if (!eligible && state.batch.result == null && !state.batch.running) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (state.batch.running) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(if (state.batch.stopping) "正在停止，等待当前请求结束…" else "正在批量评教…")
            }
            Text(state.batch.current, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = vm::stopBatch, enabled = !state.batch.stopping) { Text("停止后续评价") }
        } else {
            Button(
                onClick = vm::requestBatch,
                enabled = eligible && !state.loading && state.error == null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("一键评教所有课程和老师") }
            state.batch.result?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
                Text(state.batch.current, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (state.batch.running || state.batch.result != null) {
            Text("已提交 ${state.batch.submitted} 项，跳过已评 ${state.batch.skipped} 项", style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---------- 任务列表 ----------

@Composable
private fun TaskListView(state: EvalUiState, vm: EvalViewModel) {
    when {
        state.loading -> LoadingBox()
        state.error != null -> ErrorBox(state.error, vm::retry)
        state.tasks.isEmpty() -> EmptyBox("暂无评教任务")
        else -> LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.tasks, key = { it.taskid }, contentType = { "task" }) { t -> TaskCard(t, onClick = { if (!state.batch.running) vm.openTask(t) }) }
        }
    }
}

@Composable
private fun TaskCard(t: EvalTask, onClick: () -> Unit) {
    val active = t.currentStatus == "进行中"
    Card(
        Modifier.fillMaxWidth().then(if (active) Modifier.clickable(onClick = onClick) else Modifier),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    t.taskname,
                    Modifier.weight(1f),
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                StatusChip(t.currentStatus)
            }
            val period = listOf(t.starttime, t.endtime)
                .filter { it.isNotEmpty() }
                .joinToString(" ~ ") { it.take(10) }
            if (period.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    period,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (t.pjfs.isNotEmpty() && t.pjfs != "打分") {
                Spacer(Modifier.height(4.dp))
                Text(
                    t.pjfs,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (t.remark.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    t.remark,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun StatusChip(status: String) {
    val (fg, bg) = when (status) {
        "进行中" -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant to MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(color = bg, shape = RoundedCornerShape(6.dp)) {
        Text(
            status,
            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = fg,
        )
    }
}

// ---------- 课程列表 ----------

@Composable
private fun CourseListView(state: EvalUiState, vm: EvalViewModel) {
    when {
        state.loading -> LoadingBox()
        state.error != null -> ErrorBox(state.error, vm::retry)
        state.courses.isEmpty() -> EmptyBox("暂无可评课程")
        else -> LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.courses, key = { listOf(it.id, it.coursecode, it.classno, it.yearterm, it.pjcoursetype, it.jobnumber).joinToString("|") }, contentType = { "course" }) { c ->
                val taskActive = state.task?.currentStatus == "进行中"
                val openable = !state.batch.running && (c.isEvaluated || taskActive)
                CourseRow(
                    c = c,
                    openable = openable,
                    onClick = { vm.openCourse(c) },
                    onTeachersClick = { vm.openTeachers(c) },
                )
            }
        }
    }
}

@Composable
private fun CourseRow(
    c: EvalCourse,
    openable: Boolean,
    onClick: () -> Unit,
    onTeachersClick: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (openable) Modifier.clickable(onClick = onClick) else Modifier)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        c.coursename,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val sub = listOfNotNull(
                        c.teachername.takeIf { it.isNotEmpty() },
                        c.pjcoursetype.takeIf { it.isNotEmpty() },
                    ).joinToString(" · ")
                    if (sub.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            sub,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                CourseEvaluationStatusChip(c)
            }
            if (c.pjtype == "hjjskc" && c.isEvaluated && !c.isComplete) {
                Text(
                    c.remainingTeachers?.let { "还差 $it 位教师" } ?: "教师评价进度未知",
                    Modifier.padding(start = 14.dp, end = 14.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (c.pjtype == "hjjskc") {
                TextButton(
                    onClick = onTeachersClick,
                    enabled = openable,
                    modifier = Modifier.align(Alignment.End).padding(end = 6.dp),
                ) {
                    Text("教师评价")
                }
            }
        }
    }
}

@Composable
private fun CourseEvaluationStatusChip(course: EvalCourse) {
    val label = when {
        !course.isEvaluated -> "未评"
        course.isComplete -> "已完成"
        else -> "未完成"
    }
    EvaluationStatusChip(course.isComplete, label)
}

@Composable
private fun EvaluationStatusChip(done: Boolean, text: String? = null) {
    val fg = if (done) DoneGreen else MaterialTheme.colorScheme.onSurfaceVariant
    val bg = if (done) DoneGreen.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant
    Surface(color = bg, shape = RoundedCornerShape(6.dp)) {
        Text(
            text ?: if (done) "已评" else "未评",
            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = fg,
        )
    }
}

// ---------- 合讲教师列表 ----------

@Composable
private fun TeacherListView(state: EvalUiState, vm: EvalViewModel) {
    when {
        state.loading -> LoadingBox()
        state.error != null -> ErrorBox(state.error, vm::retry)
        state.teachers.isEmpty() -> EmptyBox("暂无合讲教师")
        else -> LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "course_header", contentType = "header") {
                Text(
                    "课程：${state.course?.coursename.orEmpty()}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
            }
            items(state.teachers, key = { "${it.jobnumber}|${it.teachername}" }, contentType = { "teacher" }) { teacher ->
                val taskActive = state.task?.currentStatus == "进行中"
                TeacherRow(
                    teacher = teacher,
                    openable = teacher.isEvaluated || taskActive,
                    onClick = { vm.openTeacher(teacher) },
                )
            }
        }
    }
}

@Composable
private fun TeacherRow(teacher: EvalCourse, openable: Boolean, onClick: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .then(if (openable) Modifier.clickable(onClick = onClick) else Modifier),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                teacher.teachername,
                Modifier.weight(1f),
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(12.dp))
            EvaluationStatusChip(teacher.isEvaluated)
        }
    }
}

// ---------- 评教表单 ----------

@Composable
private fun FormView(state: EvalUiState, vm: EvalViewModel) {
    when {
        state.loading -> LoadingBox()
        state.error != null -> ErrorBox(state.error, vm::retry)
        state.questions.isEmpty() -> EmptyBox("暂无评价题目")
        else -> {
            val listState = rememberLazyListState()
            // 校验失败：滚动到第一道未答必答题
            LaunchedEffect(state.scrollToQuestion) {
                val qid = state.scrollToQuestion ?: return@LaunchedEffect
                val idx = flatIndexOf(state.questions, qid)
                if (idx >= 0) listState.animateScrollToItem(idx)
                vm.scrollHandled()
            }
            Column(Modifier.fillMaxSize()) {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    state = listState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "form_header", contentType = "header") { FormHeader(state) }
                    questionItems(state, vm)
                }
                SubmitBar(state, vm)
            }
        }
    }
}

/** 分组渲染题目；与 [flatIndexOf] 遍历顺序保持一致（groupBy 为 LinkedHashMap） */
private fun LazyListScope.questionItems(state: EvalUiState, vm: EvalViewModel) {
    state.questions.groupBy { it.firstlevlindex }.forEach { (group, qs) ->
        if (group.isNotEmpty()) {
            item(key = "header_$group", contentType = "header") {
                Text(
                    group,
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        items(qs, key = { it.indexid }, contentType = { it.type }) { q -> QuestionCard(q, state, vm) }
    }
}

/** 题目在 LazyColumn 中的下标（顶部课程卡占 1 位） */
private fun flatIndexOf(questions: List<EvalQuestion>, qid: Long): Int {
    var i = 1
    for ((group, qs) in questions.groupBy { it.firstlevlindex }) {
        if (group.isNotEmpty()) i++
        for (q in qs) {
            if (q.indexid == qid) return i
            i++
        }
    }
    return -1
}

@Composable
private fun FormHeader(state: EvalUiState) {
    val c = state.course ?: return
    val target = if (c.pjtype == "hjjspj") "该教师" else "该课程"
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(c.coursename, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (c.teachername.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    if (c.pjtype == "hjjspj") "评价教师：${c.teachername}" else "授课教师：${c.teachername}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.questions.any { it.isscored == "是" && it.type == "单选题" }) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "当前总分：${fmtScore(state.totalScore)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.readonly) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "${target}已评价，仅供查看",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (c.isEvaluated) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "${target}已评价，可修改后重新提交",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun QuestionCard(q: EvalQuestion, state: EvalUiState, vm: EvalViewModel) {
    val editable = !state.readonly && !state.submitting
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                if (q.isemptyed == "否") {
                    Text("*", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(2.dp))
                }
                Text(q.title, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                if (q.isscored == "是" && q.score > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${fmtScore(q.score)}分",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            when (q.type) {
                "单选题" -> Column {
                    q.optionarr.forEach { opt ->
                        val selected = state.singleAnswers[q.indexid] == opt.id
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = editable) {
                                    vm.setSingleAnswer(q.indexid, opt.id)
                                }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = { vm.setSingleAnswer(q.indexid, opt.id) },
                                enabled = editable,
                            )
                            Text(opt.title, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                "问答题" -> OutlinedTextField(
                    value = state.textAnswers[q.indexid].orEmpty(),
                    onValueChange = { vm.setTextAnswer(q.indexid, it) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    enabled = editable,
                    placeholder = { Text("请输入") },
                )
                else -> Text(
                    "该题型暂不支持，请在官方 App 评价",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun SubmitBar(state: EvalUiState, vm: EvalViewModel) {
    Surface(shadowElevation = 8.dp) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            if (state.hasUnsupported && !state.readonly) {
                Text(
                    "包含暂不支持的题型，请前往官方 App 完成评价",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
            }
            if (state.readonly) {
                Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().height(46.dp)) {
                    Text("已提交")
                }
            } else {
                if (state.task?.sfqxzdzgf == "是") {
                    Text(
                        "本任务禁止全选最高分；一键高分随机降档2或3道计分单选题，其余保持最高分，并非全满分。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                if (state.task?.sfqxzdf == "是") {
                    Text(
                        "本任务禁止全选最低分。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedButton(
                    onClick = vm::requestAutoFill,
                    enabled = !state.submitting && !state.hasUnsupported,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                ) {
                    Text(if (state.task?.sfqxzdzgf == "是") "一键高分" else "一键满分")
                }
                Text(
                    "自动填写不会自动提交，中文评语可继续编辑",
                    Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.submitError?.let { submitError ->
                    Text(
                        submitError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Button(
                    onClick = vm::submit,
                    enabled = !state.submitting && !state.hasUnsupported,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                ) {
                    if (state.submitting) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(if (state.submitting) "提交中…" else "提交评教")
                }
            }
        }
    }
}

// ---------- 通用状态块 ----------

@Composable
private fun LoadingBox() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun ErrorBox(error: String?, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                error ?: "加载失败",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}

@Composable
private fun EmptyBox(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun fmtScore(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
