package li.lyc.lzuapp.ui.addcourse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import li.lyc.lzuapp.data.db.CourseEntity
import li.lyc.lzuapp.data.repo.CourseRepository
import li.lyc.lzuapp.ui.schedule.SLOT_LABELS
import kotlinx.coroutines.flow.first

class AddCourseViewModelFactory(private val repo: CourseRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return AddCourseViewModel(repo) as T
    }
}

@Composable
fun rememberAddCourseViewModelFactory(repository: CourseRepository): AddCourseViewModelFactory =
    remember(repository) { AddCourseViewModelFactory(repository) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddCourseScreen(
    repository: CourseRepository,
    totalWeeks: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    editCourse: CourseEntity? = null,
    vm: AddCourseViewModel = viewModel(factory = rememberAddCourseViewModelFactory(repository)),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()

    // 每次进入页面重置表单并监听保存完成：
    // 保存成功是一次性事件（savedEvents），VM 内世代校验保证上次仍在飞行的
    // 保存协程完成时不会误触发本表单的关闭
    androidx.compose.runtime.LaunchedEffect(Unit) {
        vm.reset()
        vm.load(totalWeeks, editCourse)
        vm.savedEvents.first()
        onBack()
    }

    // 校历异步加载完成后 totalWeeks 变化：仅刷新周次 chips 数量，不清空已选内容
    androidx.compose.runtime.LaunchedEffect(totalWeeks) {
        vm.updateTotalWeeks(totalWeeks)
    }

    Scaffold(
        // 系统栏 inset 由 TopAppBar 自行处理，避免内容重复 padding
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(if (editCourse != null) "编辑课程" else "添加课程") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier
                .fillMaxSize()
                .padding(padding)
                // edge-to-edge 规范：imePadding 必须在 verticalScroll 之前，保证键盘不遮挡输入框
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            OutlinedTextField(
                value = state.kcmc,
                onValueChange = vm::onNameChange,
                label = { Text("课程名称 *") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.teacher,
                onValueChange = vm::onTeacherChange,
                label = { Text("教师") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.location,
                onValueChange = vm::onLocationChange,
                label = { Text("教室 *") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))
            Text("星期", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..7).forEach { day ->
                    FilterChip(
                        selected = state.dayOfWeek == day,
                        onClick = { vm.onDayChange(day) },
                        label = { Text("周${"一二三四五六日"[day - 1]}") },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("节次", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionDropdown("起始", state.startSection, vm::onSectionRange, state, isStart = true)
                Spacer(Modifier.width(8.dp))
                Text("—")
                Spacer(Modifier.width(8.dp))
                SectionDropdown("结束", state.endSection, vm::onSectionRange, state, isStart = false)
            }

            Spacer(Modifier.height(16.dp))
            Text("上课周次（选择第几周上课）", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            // FlowRow 自适应换行：周次多/屏幕窄时不会挤出屏幕
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy((-2).dp),
            ) {
                (1..state.totalWeeks.coerceAtLeast(1)).forEach { w ->
                    FilterChip(
                        selected = w in state.weeks,
                        onClick = { vm.toggleWeek(w) },
                        label = { Text("$w") },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("课程颜色", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            // FlowRow 自适应换行：窄屏不溢出
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                COURSE_COLORS.forEach { c ->
                    val selected = state.color.equals(c, ignoreCase = true)
                    Box(
                        Modifier
                            .size(36.dp)
                            .background(
                                runCatching { Color(android.graphics.Color.parseColor(c)) }
                                    .getOrDefault(Color.Gray),
                                RoundedCornerShape(10.dp),
                            )
                            .border(
                                width = if (selected) 3.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                shape = RoundedCornerShape(10.dp),
                            )
                            .clickable { vm.onColorChange(c) },
                    )
                }
            }

            if (state.error != null) {
                Spacer(Modifier.height(12.dp))
                Text(state.error!!, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { vm.save() },
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                if (state.saving) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(if (editCourse != null) "保存修改" else "保存课程")
                }
            }
        }
    }
}

/**
 * 课程色盘：4 行 × 7 列，按色相红→紫排列。
 * 前三行取自设计色板（colorsimage.png，已剔除黑白灰等低对比色），
 * 第四行为高饱和经典色，明暗梯度齐全。
 */
private val COURSE_COLORS = listOf(
    "#EC7E7C", "#FAA970", "#FACD6E", "#BBD48C", "#89C8AC", "#94D1D9", "#80B2DA",
    "#E0909A", "#F0A5C3", "#CBB59F", "#DCE775", "#A5D6A7", "#738BC5", "#A38EC2",
    "#B94F53", "#C85B28", "#D89D24", "#83A74E", "#22A0A2", "#366797", "#7C5986",
    "#EF5350", "#FF7043", "#FFCA28", "#66BB6A", "#26A69A", "#42A5F5", "#AB47BC",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SectionDropdown(
    label: String,
    value: Int,
    onChange: (Int, Int) -> Unit,
    state: CourseFormState,
    isStart: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = SLOT_LABELS[value - 1],
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().width(96.dp),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            (1..14).forEach { s ->
                DropdownMenuItem(
                    text = { Text(SLOT_LABELS[s - 1]) },
                    onClick = {
                        expanded = false
                        if (isStart) onChange(s, state.endSection.coerceAtLeast(s))
                        else onChange(state.startSection.coerceAtMost(s), s)
                    },
                )
            }
        }
    }
}
