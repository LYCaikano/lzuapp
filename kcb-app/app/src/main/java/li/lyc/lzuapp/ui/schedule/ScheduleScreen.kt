package li.lyc.lzuapp.ui.schedule

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import li.lyc.lzuapp.data.db.CourseEntity
import li.lyc.lzuapp.data.db.SemesterEntity
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val DAY_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

/** 槽位(1..14) -> 显示标签：5、6 为午1/午2，7-14 显示 5..12 */
internal val SLOT_LABELS = listOf("1", "2", "3", "4", "午1", "午2", "5", "6", "7", "8", "9", "10", "11", "12")

/** 节次槽位总数 */
internal const val SLOT_COUNT = 14

/**
 * 节次时间表（14 槽，时间顺序，与官方 H5 时间表一致）：
 * 午1/午2 在第 4、5 节之间
 */
private val SECTION_TIMES = listOf(
    "08:30-09:15", "09:25-10:10", "10:30-11:15", "11:25-12:10",
    "12:20-13:15", "13:25-14:20",
    "14:30-15:15", "15:25-16:10", "16:30-17:15", "17:25-18:10",
    "19:00-19:45", "19:55-20:40", "20:50-21:35", "21:45-22:30",
)

/** 每节时间拆分为 开始/结束 用于换行显示 */
private val SECTION_TIME_PARTS: List<Pair<String, String>> =
    SECTION_TIMES.map { t -> t.substringBefore('-') to t.substringAfter('-') }

/** 午间课节次（仅 showNoonClasses 时显示） */
private val NOON_SLOTS = setOf(5, 6)

/**
 * 解析 14 位节次掩码 -> 起始槽位(1-based)、结束槽位
 * （掩码第 i 位 = 槽位 i+1，与 SECTION_TIMES 顺序一致）
 * 防御：掩码全 0 时按 1-2 节处理；长度异常（>14）时先截断到 14 位再解析，
 * 避免第一个 '1' 落在 14 位之后导致 coerceIn 区间为空抛 IllegalArgumentException。
 */
fun CourseEntity.sectionRange(): Pair<Int, Int> {
    val mask = jc.take(SLOT_COUNT)
    val start = mask.indexOf('1') + 1
    val end = mask.lastIndexOf('1') + 1
    if (start == 0) return 1 to 2
    return start to end.coerceIn(start, SLOT_COUNT)
}

/** 课程是否在本周上课。补课单次课（kcrq）按日期换算周次，仅当周显示。 */
fun CourseEntity.inWeek(weekNo: Int, semesterStart: String?): Boolean {
    kcrq?.let { date ->
        val d = parseDate(date)
        val s = parseDate(semesterStart)
        if (d != null && s != null) {
            val days = ChronoUnit.DAYS.between(s, d)
            if (days >= 0) return days / 7 + 1 == weekNo.toLong()
        }
        // kcrq/学期开始解析失败、或日期早于学期开始：回退按 week 周次掩码匹配，
        // 掩码也为空则保守隐藏（单次课不应每学期每周都显示）
    }
    val w = weekNo.toString()
    return week.split(",").contains(w)
}

private fun parseDate(s: String?): LocalDate? {
    if (s == null || s.isEmpty()) return null
    return runCatching {
        if (s.contains('-')) LocalDate.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        else LocalDate.parse(s, DateTimeFormatter.ofPattern("yyyyMMdd"))
    }.getOrNull()
}

/** 课程在网格中的布局：列号与列数（同格多课侧排） */
data class CoursePlacement(
    val course: CourseEntity,
    val day: Int,
    val col: Int,
    val colCount: Int,
)

/**
 * 贪心列分配：按天处理，槽位区间重叠的课程分配不同列。
 * 列数取"重叠连通簇"内最大列号+1（起始节不同但时段重叠的课同属一簇），
 * 与列分配逻辑口径一致，保证簇内课程互不遮挡。
 * 返回 (星期, 起始槽位) -> 该格课程布局。
 */
private fun computeCoursePlacements(courses: List<CourseEntity>): Map<Pair<Int, Int>, List<CoursePlacement>> {
    val result = mutableMapOf<Pair<Int, Int>, MutableList<CoursePlacement>>()
    for (day in 1..7) {
        val dayCourses = courses.filter { it.skxql == day }
            .sortedWith(compareBy({ it.sectionRange().first }, { it.sectionRange().second }))
        if (dayCourses.isEmpty()) continue
        val colOf = mutableMapOf<CourseEntity, Int>()
        for (c in dayCourses) {
            val (s, e) = c.sectionRange()
            var col = 0
            while (true) {
                val conflict = dayCourses.any { o ->
                    colOf.containsKey(o) && colOf[o] == col && s <= o.sectionRange().second && o.sectionRange().first <= e
                }
                if (!conflict) break
                col++
            }
            colOf[c] = col
        }
        // 区间重叠连通簇分组（传递闭包）：A 与 B 重叠、B 与 C 重叠则 A/B/C 同簇
        val colCountOf = mutableMapOf<CourseEntity, Int>()
        for (c in dayCourses) {
            if (colCountOf.containsKey(c)) continue
            val cluster = mutableListOf(c)
            var i = 0
            while (i < cluster.size) {
                val (cs, ce) = cluster[i++].sectionRange()
                for (o in dayCourses) {
                    if (o in cluster) continue
                    val (os, oe) = o.sectionRange()
                    if (cs <= oe && os <= ce) cluster.add(o)
                }
            }
            val count = cluster.maxOf { colOf[it]!! } + 1
            cluster.forEach { colCountOf[it] = count }
        }
        for (c in dayCourses) {
            val (s, _) = c.sectionRange()
            result.getOrPut(day to s) { mutableListOf() }
                .add(CoursePlacement(c, day, colOf[c]!!, colCountOf[c]!!))
        }
    }
    return result
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    vm: ScheduleViewModel = viewModel(),
    onAddCourse: () -> Unit,
    onEditCourse: (CourseEntity) -> Unit = {},
    onDeleteCourse: (CourseEntity) -> Unit = { vm.deleteCourse(it) },
    modifier: Modifier = Modifier,
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val courses by vm.courses.collectAsStateWithLifecycle()
    val semester by vm.semester.collectAsStateWithLifecycle()
    val hidden by vm.hidden.collectAsStateWithLifecycle()

    Scaffold(
        // 底部由外层 NavigationBar 处理，TopAppBar 自带状态栏 inset，避免重复 padding
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            // 紧凑顶栏：不用 TopAppBar（其最小高度 64dp 偏大），自定义行不写死高度、
            // 内容多高就多高，紧贴下方控制栏；字体放大时行高随之增长，不会截断
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    semesterLabel(semester),
                    // 与周选择器同字号（15sp，窄屏同步缩到 12sp），同一行更紧凑
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = 15.sp),
                    // weight 占满剩余宽度，把周选择器推到最右；文字左对齐、超长省略
                    modifier = Modifier.weight(1f),
                )
                WeekSelector(
                    currentWeek = state.currentWeek,
                    totalWeeks = state.totalWeeks,
                    onWeekSelected = vm::setWeek,
                )
            }
        },
    ) { padding ->
        Column(modifier.padding(padding)) {
            ControlBar(
                showNoon = state.showNoonClasses,
                onToggleNoon = vm::setShowNoonClasses,
                showHidden = state.showHiddenCourses,
                onToggleHidden = vm::setShowHiddenCourses,
                syncStatusText = state.syncStatusText,
                syncStatusError = state.syncStatusError,
                refreshing = state.refreshing,
                onRefresh = vm::refresh,
                onAddCourse = onAddCourse,
            )
            // 同步失败不撤本地缓存（repo 层保证）；无课程时也显示空网格，错误以横幅提示
            if (state.error != null) {
                Text(
                    state.error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            // 同步期间保持课表可见，不用全屏转圈遮挡（状态由外层 Snackbar/控制栏小字反馈）
            ScheduleGrid(
                courses = courses,
                currentWeek = state.currentWeek,
                totalWeeks = state.totalWeeks,
                showNoon = state.showNoonClasses,
                showHidden = state.showHiddenCourses,
                semesterStart = state.semesterStart,
                hiddenMap = hidden,
                onWeekSelected = vm::setWeek,
                onEditCourse = onEditCourse,
                onDeleteCourse = onDeleteCourse,
                onHideCourse = vm::hideCourse,
                onUnhideCourse = vm::unhideCourse,
                onUnhideCourseAll = vm::unhideCourseAll,
            )
        }
    }
}

/**
 * 控制栏（单行，紧凑）：午间课开关 | 同步状态文字 | 刷新 | 添加课程。
 * 高度最小化，让课表主体尽量靠上。
 */
@Composable
private fun ControlBar(
    showNoon: Boolean,
    onToggleNoon: (Boolean) -> Unit,
    showHidden: Boolean,
    onToggleHidden: (Boolean) -> Unit,
    syncStatusText: String,
    syncStatusError: Boolean,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onAddCourse: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("午间课", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.width(2.dp))
        androidx.compose.material3.Switch(
            checked = showNoon,
            onCheckedChange = onToggleNoon,
            modifier = Modifier.scale(0.7f),
        )
        // 显示已隐藏课程（半透明块）；关闭 = 彻底不渲染
        Text("显示隐藏", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.width(2.dp))
        androidx.compose.material3.Switch(
            checked = showHidden,
            onCheckedChange = onToggleHidden,
            modifier = Modifier.scale(0.7f),
        )
        // 右侧状态区：常驻 weight(1f) 容器右对齐，保证刷新/添加按钮始终贴最右、不错位；
        // 状态文字超长时自身省略号截断，不挤压按钮
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (refreshing) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(4.dp))
            }
            if (syncStatusText.isNotEmpty()) {
                Text(
                    syncStatusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (syncStatusError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(4.dp))
            }
        }
        IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Refresh, contentDescription = "刷新", modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = onAddCourse, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Add, contentDescription = "添加课程", modifier = Modifier.size(18.dp))
        }
    }
}

private val SECTION_LABEL_WIDTH = 32.dp
private val SECTION_ROW_HEIGHT = 72.dp
private val GRID_H_PADDING = 4.dp

/** 行距 = 行高 + 上下 1dp padding */
private val ROW_PITCH = SECTION_ROW_HEIGHT + 2.dp

/** 槽位 [slot] 的显示纵向偏移：午间课槽位按展开比例 [noonF] 占位，其余全高 */
private fun rowOffsetY(slot: Int, noonF: Float): Dp {
    var nonNoon = 0
    var noon = 0
    for (i in 1 until slot) if (i in NOON_SLOTS) noon++ else nonNoon++
    return ROW_PITCH * (nonNoon + noonF * noon)
}

/** 槽位区间 [s]..[e] 的显示高度：午间课槽位按展开比例 [noonF] 计入 */
private fun rowSpanH(s: Int, e: Int, noonF: Float): Dp {
    var nonNoon = 0
    var noon = 0
    for (i in s..e) if (i in NOON_SLOTS) noon++ else nonNoon++
    return ROW_PITCH * (nonNoon + noonF * noon)
}

/**
 * 顶部星期列头行（与网格左侧节次列对齐，单元格宽度与网格一致）。
 * 列头下方显示该周日期（月/日，按学期开始日期推算），今日列高亮。
 * [todayCol]：仅当显示的周是本周时 != 0（0 = 不高亮，滑动到其他周不高亮）。
 */
@Composable
private fun DayHeaderRow(currentWeek: Int, semesterStart: String?, todayCol: Int) {
    val weekStart = remember(currentWeek, semesterStart) {
        parseDate(semesterStart)?.plusWeeks((currentWeek - 1).toLong())
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val dayW = (maxWidth - SECTION_LABEL_WIDTH - GRID_H_PADDING * 2) / 7
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = SECTION_LABEL_WIDTH + GRID_H_PADDING, end = GRID_H_PADDING, top = 2.dp, bottom = 1.dp)
        ) {
            DAY_LABELS.forEachIndexed { idx, day ->
                val isToday = todayCol != 0 && idx + 1 == todayCol
                Column(
                    Modifier.width(dayW),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "周$day",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isToday) MaterialTheme.colorScheme.primary else Color.Unspecified,
                    )
                    val date = weekStart?.plusDays(idx.toLong())
                    if (date != null) {
                        Text(
                            date.format(DateTimeFormatter.ofPattern("M/d")),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isToday) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 课表网格：列 = 周一到周日（顶部表头），行 = 节次（左侧）。
 * 周切换：左右滑动（HorizontalPager），顶栏点击可快速跳转。
 * 跨节课程按 span 合并高度，同格多课按贪心列侧排。
 */
@Composable
private fun ScheduleGrid(
    courses: List<CourseEntity>,
    currentWeek: Int,
    totalWeeks: Int,
    showNoon: Boolean,
    showHidden: Boolean,
    semesterStart: String?,
    hiddenMap: Map<String, Set<Int>>,
    onWeekSelected: (Int) -> Unit,
    onEditCourse: (CourseEntity) -> Unit,
    onDeleteCourse: (CourseEntity) -> Unit,
    onHideCourse: (CourseEntity, Set<Int>) -> Unit,
    onUnhideCourse: (CourseEntity, Set<Int>) -> Unit,
    onUnhideCourseAll: (CourseEntity) -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = (currentWeek - 1).coerceAtLeast(0)) { totalWeeks.coerceAtLeast(1) }

    // 双向同步防回环：程序滚动期间不向外传播页码，pager 只上报用户真实滑动。
    // 否则"状态→pager 滚动→snapshotFlow 中间页→回写状态"会互相打断，导致周次被重置
    var syncingFromState by remember { mutableStateOf(false) }
    val latestWeek by rememberUpdatedState(currentWeek)

    // 顶栏点击跳转/状态变化时同步滑动位置
    LaunchedEffect(currentWeek) {
        if (pagerState.currentPage != currentWeek - 1) {
            syncingFromState = true
            pagerState.scrollToPage(currentWeek - 1)
            syncingFromState = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 仅当当前显示的周是本周时才高亮今日列（滑动到其他周不高亮）
        val todayCol = remember(currentWeek, semesterStart) {
            val s = parseDate(semesterStart) ?: return@remember 0
            val today = LocalDate.now()
            val weekStart = s.plusWeeks((currentWeek - 1).toLong())
            if (today >= weekStart && today < weekStart.plusDays(7)) today.dayOfWeek.value else 0
        }
        // 已隐藏课程的可见度动画（0↔1）：关闭时渐隐、打开时渐显到 0.4 半透明。
        // 提到本层（而非各页）：布局占位在渐隐完成后才让位（见下方 showHiddenEffective），
        // 顺序 = 隐藏的课先淡出 → 重叠的课再动画恢复全宽
        val hiddenF by animateFloatAsState(
            if (showHidden) 1f else 0f,
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
            label = "hiddenFade",
        )
        val showHiddenEffective = showHidden || hiddenF > 0.01f
        DayHeaderRow(currentWeek = currentWeek, semesterStart = semesterStart, todayCol = todayCol)

        // 左右滑动切换周次时同步 ViewModel 状态（程序滚动不回传，页码与状态一致时不回传）
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.currentPage }.collect { p ->
                if (!syncingFromState && p + 1 != latestWeek) onWeekSelected(p + 1)
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
        ) { page ->
            val week = page + 1
            val weekCoursesAll = remember(courses, week, semesterStart) {
                courses.filter { it.inWeek(week, semesterStart) }
            }
            // 被隐藏且开关关闭（含渐隐完成后）的课不参与布局：重叠的课恢复全宽
            val weekCourses = remember(weekCoursesAll, hiddenMap, showHiddenEffective, week) {
                weekCoursesAll.filter { c ->
                    val hiddenNow = hiddenMap[c.kch]?.let { 0 in it || week in it } == true
                    !hiddenNow || showHiddenEffective
                }
            }
            // 配色索引基于完整周课表（含被隐藏的课）：隐藏/恢复/切换开关不影响其他课的取色位置
            val colorIdx = remember(weekCoursesAll) {
                weekCoursesAll
                    .sortedWith(compareBy({ it.skxql }, { it.sectionRange().first }, { it.sectionRange().second }, { it.kch }))
                    .mapIndexed { i, c -> c.kch to i }
                    .toMap()
            }
            val placements = remember(weekCourses) { computeCoursePlacements(weekCourses) }
            // 换周回到顶部：每页在“停稳在本页”与否变化时重建滚动状态（初始 0）。
            // 关键：不用 rememberScrollState（其内部 rememberSaveable 会被 Pager 存档/恢复旧滚动位置）。
            // 滑动过程中本页保持原状态不打断；滑出停稳后重建时本页已在屏外，无视觉跳变。
            val scrollState = remember(pagerState.settledPage == page) { ScrollState(0) }
            GridPage(
                showNoon = showNoon,
                hiddenF = hiddenF,
                placements = placements,
                colorIdx = colorIdx,
                scrollState = scrollState,
                todayCol = todayCol,
                totalWeeks = totalWeeks,
                week = week,
                hiddenMap = hiddenMap,
                onEdit = onEditCourse,
                onDelete = onDeleteCourse,
                onHide = onHideCourse,
                onUnhide = onUnhideCourse,
                onUnhideAll = onUnhideCourseAll,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * 单周网格：背景行层 + 课程卡片层（共享 scrollState 锁步滚动）。
 * 课程卡片按 (星期, 起始槽位) 绝对定位，高度 = 跨节数 × 行距。
 * 长按自定义课程弹出 编辑/删除 菜单，删除需二次确认。
 */
@Composable
private fun GridPage(
    showNoon: Boolean,
    /** 已隐藏课程的可见度动画值（0↔1），由外层 ScheduleGrid 统一驱动 */
    hiddenF: Float,
    placements: Map<Pair<Int, Int>, List<CoursePlacement>>,
    /** kch -> 周内轮换配色索引（基于完整周课表，隐藏课不影响其他课取色） */
    colorIdx: Map<String, Int>,
    scrollState: ScrollState,
    todayCol: Int,
    totalWeeks: Int,
    week: Int,
    hiddenMap: Map<String, Set<Int>>,
    onEdit: (CourseEntity) -> Unit,
    onDelete: (CourseEntity) -> Unit,
    onHide: (CourseEntity, Set<Int>) -> Unit,
    onUnhide: (CourseEntity, Set<Int>) -> Unit,
    onUnhideAll: (CourseEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuCourse by remember { mutableStateOf<CourseEntity?>(null) }
    var confirmDelete by remember { mutableStateOf<CourseEntity?>(null) }
    var hideTarget by remember { mutableStateOf<CourseEntity?>(null) }
    var unhideTarget by remember { mutableStateOf<CourseEntity?>(null) }
    // 课程块 x/宽度动画状态：按 kch 存活在组合外（GridPage 作用域）。
    // 不依赖组合内 key 匹配——隐藏课让位/恢复导致结构变化时动画连续性必然保持
    val cellAnims = remember { mutableMapOf<String, CellAnim>() }
    // 今日列高亮：浅色用 primaryContainer 淡色块；深色下 container 太闷，改用 primary 低透明度
    val bg = MaterialTheme.colorScheme.background
    val darkScheme = (bg.red * 0.299f + bg.green * 0.587f + bg.blue * 0.114f) < 0.5f
    val todayBg = if (darkScheme) MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
    else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
    // 午间课（5、6 节）仅以区块上下虚线区分，底色与普通行一致（随斑马纹）
    val noonDash = MaterialTheme.colorScheme.outlineVariant
    // 午间课开关：两行高度 0↔1 展开/收起（无回弹 spring，Material 动效规范），下方行随之下移/上移让位
    val noonF by animateFloatAsState(
        if (showNoon) 1f else 0f,
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "noonExpand",
    )
    BoxWithConstraints(modifier) {
        val maxW = maxWidth
        val dayW = (maxW - SECTION_LABEL_WIDTH - GRID_H_PADDING * 2) / 7
        val contentH = ROW_PITCH * (SLOT_COUNT - NOON_SLOTS.size + NOON_SLOTS.size * noonF)

        // 背景行层
        Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
            // 单个背景行（节次标签 + 7 天格），结构对所有行一致
            @Composable
            fun BgRow(slot: Int) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = GRID_H_PADDING, vertical = 1.dp)
                ) {
                    SlotLabel(slot)
                    for (day in 1..7) {
                        Box(
                            Modifier
                                .width(dayW)
                                .height(SECTION_ROW_HEIGHT)
                                .background(
                                    when {
                                        // 今日列整列高亮
                                        day == todayCol -> todayBg
                                        // 隔行斑马纹：浅色下明显一些，深色下减弱避免反差过大
                                        slot % 2 == 0 ->
                                            MaterialTheme.colorScheme.surfaceVariant.copy(
                                                alpha = if (darkScheme) 0.28f else 0.55f
                                            )
                                        else -> Color.Transparent
                                    },
                                    RoundedCornerShape(4.dp),
                                )
                        )
                    }
                }
            }
            (1..SLOT_COUNT).forEach { slot ->
                when {
                    // 午间课区块：两行作为一个整体容器，高度随 noonF 展开/收起（下方行自然让位）。
                    // 虚线画在容器自身的上下边界，天然上下对称、不被行内边距挤压
                    slot == NOON_SLOTS.first() -> {
                        if (noonF > 0f) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .height(ROW_PITCH * NOON_SLOTS.size * noonF)
                                    .clipToBounds()
                                    .drawBehind {
                                        val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
                                        val w = 1.dp.toPx()
                                        drawLine(noonDash, Offset(0f, w), Offset(size.width, w), w, alpha = noonF, pathEffect = dash)
                                        drawLine(noonDash, Offset(0f, size.height - w), Offset(size.width, size.height - w), w, alpha = noonF, pathEffect = dash)
                                    }
                            ) {
                                NOON_SLOTS.forEach { BgRow(it) }
                            }
                        }
                    }
                    slot !in NOON_SLOTS -> BgRow(slot)
                }
            }
        }

        // 课程卡片层
        Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
            Box(
                Modifier
                    .width(maxW)
                    .height(contentH)
                    .clipToBounds()
            ) {
                // 渲染可见槽位内的课程；午间课收起时其课程随区块一起收起裁剪。
                // 扁平化遍历（不按格嵌套）：所有课程块同为 Box 的直接子级 key 组，
                // 隐藏课让位导致位置前移时 key(kch) 仍能匹配上，动画状态不丢
                placements.values.flatten().forEach { p ->
                    val day = p.day
                    run {
                        val (s, e) = p.course.sectionRange()
                        val visible = (s..e).filter { it !in NOON_SLOTS || noonF > 0.01f }
                        if (visible.isEmpty()) return@run
                        val top = visible.first()
                        val cellW = dayW / p.colCount
                        // 本周该课是否被隐藏（0 = 全部周）
                        val hiddenNow = hiddenMap[p.course.kch]?.let { 0 in it || week in it } == true
                        // 隐藏开关关闭时彻底不渲染（渐隐到接近 0 后移除，动画过程可见）
                        if (hiddenNow && hiddenF <= 0.01f) return@run
                        // key(kch)：仅为 LaunchedEffect 提供稳定作用域；
                        // 动画值本身活在 cellAnims（组合外），布局让位/恢复时从当前值平滑过渡
                        val targetX = SECTION_LABEL_WIDTH + GRID_H_PADDING + dayW * (day - 1) + cellW * p.col
                        val anim = cellAnims.getOrPut(p.course.kch) { CellAnim(targetX, cellW) }
                        androidx.compose.runtime.key(p.course.kch) {
                            LaunchedEffect(targetX) { anim.x.animateTo(targetX, CELL_LAYOUT_SPRING) }
                            LaunchedEffect(cellW) { anim.w.animateTo(cellW, CELL_LAYOUT_SPRING) }
                            CourseCell(
                                p.course,
                                accent = run {
                                    val c = parseColor(p.course.color)
                                    if (c != null) c else WEEK_PALETTE[(colorIdx[p.course.kch] ?: 0) % WEEK_PALETTE.size]
                                },
                                tag = when {
                                    hiddenNow -> "[已隐藏]"
                                    p.course.isCustom -> "[我]"
                                    else -> null
                                },
                                cellAlpha = if (hiddenNow) 0.4f * hiddenF else 1f,
                                onClick = { menuCourse = p.course },
                                onLongPress = { menuCourse = p.course },
                                modifier = Modifier
                                    .offset(x = anim.x.value, y = rowOffsetY(top, noonF) + 1.dp)
                                    .width(anim.w.value)
                                    .height(rowSpanH(visible.first(), visible.last(), noonF) - 2.dp)
                                    .clipToBounds()
                                    // 午间课槽位上的课程卡片随区块展开度渐显/收起
                                    .then(if (top in NOON_SLOTS) Modifier.alpha(noonF) else Modifier),
                            )
                        }
                }
            }
        }

        // 课程菜单：单击/长按课程弹出。自定义课程含 编辑/删除，正式课程只读。
        menuCourse?.let { c ->
            val (s, e) = c.sectionRange()
            AlertDialog(
                onDismissRequest = { menuCourse = null },
                title = { Text("课程信息", style = MaterialTheme.typography.titleMedium) },
                text = {
                    Column(
                        Modifier.padding(top = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            "课程：${c.kcmc}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "教师：${c.jsxm?.ifBlank { null } ?: "—"}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "教室：${c.skjsl?.ifBlank { null } ?: "—"}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        val dayText = "一二三四五六日"[c.skxql.coerceIn(1, 7) - 1]
                        val rangeText = if (e > s) "${SLOT_LABELS[s - 1]}-${SLOT_LABELS[e - 1]}"
                        else SLOT_LABELS[s - 1]
                        Text(
                            "时间：周$dayText 第${rangeText}节",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        // 周次只显示学期范围内的（官方 App 数据可能拉满到 24 周）；
                        // 全部超出范围时显示原始周次并标注，避免误导为"无周次"
                        val allWeeks = c.week.trim(',').split(',')
                            .mapNotNull { it.toIntOrNull() }
                            .sorted()
                        val inRangeWeeks = allWeeks.filter { it in 1..totalWeeks }
                        val weeksText = when {
                            inRangeWeeks.isNotEmpty() -> inRangeWeeks.joinToString("、")
                            allWeeks.isNotEmpty() -> allWeeks.joinToString("、") + "（超出本学期）"
                            else -> "—"
                        }
                        Text("周次：$weeksText", style = MaterialTheme.typography.bodyMedium)
                    }
                },
                confirmButton = {
                    if (c.isCustom) {
                        TextButton(onClick = { menuCourse = null; onEdit(c) }) { Text("编辑") }
                    } else {
                        Row {
                            // 已有隐藏条目时提供取消隐藏入口（隐藏的课点进来主要就是为了恢复）
                            if (hiddenMap[c.kch]?.isNotEmpty() == true) {
                                TextButton(onClick = { menuCourse = null; unhideTarget = c }) { Text("取消隐藏") }
                            }
                            TextButton(onClick = { menuCourse = null }) { Text("关闭") }
                        }
                    }
                },
                dismissButton = {
                    if (c.isCustom) {
                        TextButton(onClick = { menuCourse = null; confirmDelete = c }) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        TextButton(onClick = { menuCourse = null; hideTarget = c }) { Text("隐藏") }
                    }
                },
            )
        }
        // 隐藏范围选择（系统课程）：全部周 / 仅本周 / 指定周
        hideTarget?.let { c ->
            HideCourseDialog(
                course = c,
                week = week,
                totalWeeks = totalWeeks,
                onDismiss = { hideTarget = null },
                onConfirm = { weeks -> hideTarget = null; onHide(c, weeks) },
            )
        }
        // 取消隐藏：按已有隐藏条目勾选恢复
        unhideTarget?.let { c ->
            UnhideCourseDialog(
                course = c,
                hiddenWeeks = hiddenMap[c.kch].orEmpty(),
                onDismiss = { unhideTarget = null },
                onConfirm = { weeks -> unhideTarget = null; onUnhide(c, weeks) },
                onUnhideAll = { unhideTarget = null; onUnhideAll(c) },
            )
        }
        // 删除二次确认
        confirmDelete?.let { c ->
            AlertDialog(
                onDismissRequest = { confirmDelete = null },
                title = { Text("删除课程") },
                text = { Text("确定删除「${c.kcmc}」吗？") },
                confirmButton = {
                    TextButton(onClick = { confirmDelete = null; onDelete(c) }) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDelete = null }) { Text("取消") }
                },
            )
        }
    }
}
}

/**
 * 隐藏课程范围选择：全部周次 / 仅本周 / 指定周次（多选）。
 * 结果集合：{0} = 全部周；{N} = 仅第 N 周；多选 = 各周。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HideCourseDialog(
    course: CourseEntity,
    week: Int,
    totalWeeks: Int,
    onDismiss: () -> Unit,
    onConfirm: (Set<Int>) -> Unit,
) {
    // 0=全部周 1=仅本周 2=指定周
    var mode by remember { mutableStateOf(0) }
    var picked by remember { mutableStateOf(setOf<Int>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("隐藏课程", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "「${course.kcmc}」隐藏后在原位置显示为半透明，可随时恢复。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                listOf(
                    0 to "隐藏所有周次",
                    1 to "仅隐藏本周（第 $week 周）",
                    2 to "隐藏指定周次",
                ).forEach { (m, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { mode = m }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = mode == m, onClick = { mode = m })
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (mode == 2) {
                    FlowRow(
                        Modifier.fillMaxWidth().padding(start = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        (1..totalWeeks.coerceAtLeast(1)).forEach { w ->
                            FilterChip(
                                selected = w in picked,
                                onClick = { picked = if (w in picked) picked - w else picked + w },
                                label = { Text("$w", style = MaterialTheme.typography.labelMedium) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = mode != 2 || picked.isNotEmpty(),
                onClick = {
                    onConfirm(
                        when (mode) {
                            0 -> setOf(0)
                            1 -> setOf(week)
                            else -> picked
                        },
                    )
                },
            ) { Text("隐藏") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 取消隐藏：列出该课已有隐藏条目（所有周次 / 第 N 周），勾选恢复，或一键全部恢复 */
@Composable
private fun UnhideCourseDialog(
    course: CourseEntity,
    hiddenWeeks: Set<Int>,
    onDismiss: () -> Unit,
    onConfirm: (Set<Int>) -> Unit,
    onUnhideAll: () -> Unit,
) {
    var picked by remember { mutableStateOf(setOf<Int>()) }
    val entries = hiddenWeeks.sorted()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("取消隐藏", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "「${course.kcmc}」当前被隐藏的范围：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                entries.forEach { w ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { picked = if (w in picked) picked - w else picked + w }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = w in picked,
                            onCheckedChange = { picked = if (w in picked) picked - w else picked + w },
                        )
                        Text(
                            if (w == 0) "所有周次" else "第 $w 周",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onUnhideAll) { Text("全部恢复") }
                TextButton(
                    enabled = picked.isNotEmpty(),
                    onClick = { onConfirm(picked) },
                ) { Text("恢复所选") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

/** 左侧节次标签列：等宽字体，文字左对齐贴住屏幕左边缘 */
@Composable
private fun SlotLabel(slot: Int) {    Column(
        Modifier
            .width(SECTION_LABEL_WIDTH)
            .height(SECTION_ROW_HEIGHT)
            .padding(top = 3.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            SLOT_LABELS[slot - 1],
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
        val (start, end) = SECTION_TIME_PARTS[slot - 1]
        Text(
            start,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 10.sp,
            maxLines = 1,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "~",
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 8.sp,
            maxLines = 1,
            color = MaterialTheme.colorScheme.outline,
        )
        Text(
            end,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 10.sp,
            maxLines = 1,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 顶栏静态学期标签：当前学期（校历），如 "2026 夏季学期"；无校历时显示"课表" */
private fun semesterLabel(sem: SemesterEntity?): String {
    val xn = sem?.xn?.takeIf { it.isNotBlank() } ?: return "课表"
    val season = sem.xq?.takeIf { it.isNotBlank() } ?: when (sem.xqm?.toIntOrNull()) {
        1 -> "秋季学期"
        2 -> "春季学期"
        3 -> "夏季学期"
        else -> "学期"
    }
    return "$xn $season"
}

@Composable
private fun WeekSelector(
    currentWeek: Int,
    totalWeeks: Int,
    onWeekSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        TextButton(
            onClick = { expanded = true },
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
            // 去掉默认 48dp 最小高度，让紧凑顶栏紧贴内容
            modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 1.dp),
        ) {
            // 宽屏 15sp，窄屏自动缩小到 12sp 保底，不换行；缩到下限仍超宽时省略号兜底
            Text(
                "第 $currentWeek 周 / 共 $totalWeeks 周",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = 15.sp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            (1..totalWeeks.coerceAtLeast(1)).forEach { w ->
                DropdownMenuItem(
                    text = { Text("第 $w 周") },
                    onClick = {
                        onWeekSelected(w)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CourseCell(
    course: CourseEntity,
    accent: Color,
    tag: String? = null,
    /** 整块透明度：隐藏的课 0.2（随开关动画 0↔0.2），普通课 1 */
    cellAlpha: Float = 1f,
    onClick: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // 完整色块卡片；一律白字，深色模式下白字微灰（不与课程颜色绑定）。
    // 按 App 实际主题背景亮度判定（用户手动覆盖主题时 isSystemInDarkTheme 会误判）
    val themeBg = MaterialTheme.colorScheme.background
    val darkScheme = (themeBg.red * 0.299f + themeBg.green * 0.587f + themeBg.blue * 0.114f) < 0.5f
    val contentColor = if (darkScheme) Color(0xFFE8E8E8) else Color.White
    val pressModifier = if (onClick != null || onLongPress != null) {
        Modifier.combinedClickable(onClick = { onClick?.invoke() }, onLongClick = onLongPress)
    } else Modifier
    Card(
        shape = RoundedCornerShape(6.dp),
        // 隐藏的课：整块低透明度（随开关渐显/渐隐），仍可点击进详情恢复
        modifier = modifier
            .padding(2.dp)
            .then(if (cellAlpha < 1f) Modifier.alpha(cellAlpha) else Modifier)
            .then(pressModifier),
    ) {
        Box(
            Modifier.fillMaxSize().background(accent.copy(alpha = 0.85f)).padding(horizontal = 2.dp, vertical = 4.dp),
            contentAlignment = Alignment.TopStart,
        ) {
            Column {
                // 标记行：自定义课 [我] / 隐藏课 [已隐藏]
                tag?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        fontWeight = FontWeight.Bold,
                        color = contentColor.copy(alpha = 0.9f),
                        maxLines = 1,
                    )
                }
                Text(
                    course.kcmc,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                course.skjsl?.let {
                    Text(
                        it.substringBefore("(").substringBefore("（"),
                        // 教室行字号小一号 + 内边距收窄：汉字楼名（如 天山堂）尽量排在同一行
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = contentColor,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * 解析课程颜色：支持 #RRGGBB 等 android 格式，以及 rgb(r,g,b) / rgb(r,g,b,a) / rgba(...)：
 * alpha 省略视为不透明；>1 的数值按 0-255 整数归一到 0-1。解析失败返回 null（调用方回退默认色）。
 */
fun parseColor(color: String?): Color? {
    if (color == null) return null
    return runCatching {
        val m = Regex("""rgba?\(\s*(\d+)[,，]\s*(\d+)[,，]\s*(\d+)\s*(?:[,，]\s*([\d.]+)\s*)?\)""")
            .find(color)
        if (m != null) {
            val aRaw = m.groupValues[4]
            val a = when {
                aRaw.isEmpty() -> 1f
                aRaw.toFloat() > 1f -> aRaw.toFloat() / 255f
                else -> aRaw.toFloat()
            }
            Color(
                m.groupValues[1].toInt() / 255f,
                m.groupValues[2].toInt() / 255f,
                m.groupValues[3].toInt() / 255f,
                a.coerceIn(0f, 1f),
            )
        } else {
            Color(android.graphics.Color.parseColor(color))
        }
    }.getOrNull()
}

/**
 * 周内轮换配色（用于无服务端颜色的正式课，只作卡片左侧色条）：
 * 红/黄/绿/蓝优先，粉/紫/橙/青绿/浅蓝备选；调用方按周内时间顺序循环取色，避免同周同色扎堆。
 */
private val WEEK_PALETTE = listOf(
    "#E57373", "#F2B94B", "#81C784", "#64B5F6",
    "#F48FB1", "#9575CD", "#FFB74D", "#4DB6AC", "#4FC3F7",
).map { Color(android.graphics.Color.parseColor(it)) }

/** 课程块布局（x/宽度）动画规格：无回弹 spring，与午间课/隐藏渐隐动效一致 */
private val CELL_LAYOUT_SPRING = spring<Dp>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

/** 课程块布局动画状态（x 偏移 + 宽度），按 kch 存活在组合外，保证结构变化时动画连续 */
private class CellAnim(x0: Dp, w0: Dp) {
    val x = androidx.compose.animation.core.Animatable(x0, Dp.VectorConverter)
    val w = androidx.compose.animation.core.Animatable(w0, Dp.VectorConverter)
}
