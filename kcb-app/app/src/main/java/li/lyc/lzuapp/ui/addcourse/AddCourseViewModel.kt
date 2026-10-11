package li.lyc.lzuapp.ui.addcourse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import li.lyc.lzuapp.data.db.CourseEntity
import li.lyc.lzuapp.data.repo.CourseRepository
import li.lyc.lzuapp.data.repo.SyncResult
import li.lyc.lzuapp.ui.schedule.SLOT_LABELS
import li.lyc.lzuapp.ui.schedule.parseColor
import li.lyc.lzuapp.ui.schedule.sectionRange
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class CourseFormState(
    val kcmc: String = "",
    val teacher: String = "",
    val location: String = "",
    /** 星期 1-7 */
    val dayOfWeek: Int = 1,
    /** 起始节 */
    val startSection: Int = 1,
    /** 结束节 */
    val endSection: Int = 2,
    /** 选择的周次（1..totalWeeks 位图） */
    val weeks: Set<Int> = emptySet(),
    val totalWeeks: Int = 20,
    /** 默认色需与 AddCourseScreen 的 COURSE_COLORS 色板一致，否则色板无选中态 */
    val color: String = "#66BB6A",
    val saving: Boolean = false,
    val error: String? = null,
    /** 编辑模式：非空 = 正在编辑该课程（保存时保留 kch） */
    val editingKch: String? = null,
    /** 课程所属学期（添加时取当前学期；编辑时保留原学期） */
    val xn: String? = null,
    val xqm: Int? = null,
) {
    /** 14 位节次掩码 */
    val jcMask: String
        get() = (0 until 14).joinToString("") { i ->
            if (i + 1 in startSection..endSection) "1" else "0"
        }

    val weekString: String
        get() = if (weeks.isEmpty()) "" else "," + weeks.sorted().joinToString(",") + ","
}

class AddCourseViewModel(private val repo: CourseRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(CourseFormState())
    val uiState: StateFlow<CourseFormState> = _uiState.asStateFlow()

    /** 保存成功一次性事件：页面收到后 onBack。不用状态位，避免旧协程残留/误触发新表单 */
    private val _savedEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val savedEvents: SharedFlow<Unit> = _savedEvents.asSharedFlow()

    /** 500ms 静默冷却：防连点导致重复提交（校验通过后才记录，见 save()） */
    private var lastSaveAt = 0L

    /** 表单世代：reset/load 自增。旧保存协程完成时世代已变，只落库、不再触碰新表单状态 */
    private var generation = 0

    /** 进入页面初始化：空表单（添加）或预填课程（编辑） */
    fun load(totalWeeks: Int, edit: CourseEntity? = null) {
        generation++
        val base = CourseFormState(totalWeeks = totalWeeks.coerceAtLeast(1))
        if (edit == null) {
            _uiState.value = base
            // 新课程归属当前学期（用于同学期冲突校验与入库学期标记）
            viewModelScope.launch {
                val (xn, xqm) = repo.currentSemesterKey()
                val s = _uiState.value
                if (s.editingKch == null && s.xn == null && s.xqm == null) {
                    _uiState.value = s.copy(xn = xn, xqm = xqm)
                }
            }
            return
        }
        val (start, end) = edit.sectionRange()
        _uiState.value = base.copy(
            kcmc = edit.kcmc,
            teacher = edit.jsxm.orEmpty(),
            location = edit.skjsl.orEmpty(),
            dayOfWeek = edit.skxql,
            startSection = start,
            endSection = end,
            weeks = edit.week.split(",").mapNotNull { it.toIntOrNull() }.toSet(),
            // rgb()/rgba() 等非 # 格式统一转成 #RRGGBB 再匹配色板，无法解析回退默认色
            color = edit.color?.let { colorToHex(it) } ?: "#66BB6A",
            editingKch = edit.kch,
            xn = edit.xn,
            xqm = edit.xqm,
        )
    }

    /** 清空表单（重新进入页面时调用；世代自增使上次仍在飞行的保存协程完成后不影响新表单） */
    fun reset() {
        generation++
        _uiState.value = CourseFormState()
    }

    /** 校历异步加载后 totalWeeks 变化：只更新周次网格数量，保留已选周次与表单内容 */
    fun updateTotalWeeks(totalWeeks: Int) {
        val s = _uiState.value
        val tw = totalWeeks.coerceAtLeast(1)
        if (s.totalWeeks != tw) _uiState.value = s.copy(totalWeeks = tw)
    }

    /** 任意支持的颜色格式（rgb()/rgba()/#hex）转 #RRGGBB；解析失败返回 null */
    private fun colorToHex(color: String): String? {
        val c = parseColor(color) ?: return null
        val r = (c.red * 255).roundToInt().coerceIn(0, 255)
        val g = (c.green * 255).roundToInt().coerceIn(0, 255)
        val b = (c.blue * 255).roundToInt().coerceIn(0, 255)
        return "#%02X%02X%02X".format(r, g, b)
    }

    fun onNameChange(v: String) = _uiState.value.let { _uiState.value = it.copy(kcmc = v, error = null) }
    fun onTeacherChange(v: String) = _uiState.value.let { _uiState.value = it.copy(teacher = v, error = null) }
    fun onLocationChange(v: String) = _uiState.value.let { _uiState.value = it.copy(location = v, error = null) }
    fun onDayChange(day: Int) = _uiState.value.let { _uiState.value = it.copy(dayOfWeek = day) }
    fun onColorChange(color: String) = _uiState.value.let { _uiState.value = it.copy(color = color) }
    fun onSectionRange(start: Int, end: Int) =
        _uiState.value.let { _uiState.value = it.copy(startSection = start.coerceIn(1, 14), endSection = end.coerceIn(start, 14)) }
    fun toggleWeek(week: Int) {
        val s = _uiState.value
        val weeks = if (week in s.weeks) s.weeks - week else s.weeks + week
        _uiState.value = s.copy(weeks = weeks)
    }

    fun save() {
        val s = _uiState.value
        if (s.saving) return
        if (s.kcmc.isBlank()) { _uiState.value = s.copy(error = "请输入课程名"); return }
        if (s.location.isBlank()) { _uiState.value = s.copy(error = "请输入教室号"); return }
        if (s.weeks.isEmpty()) { _uiState.value = s.copy(error = "请选择上课周次"); return }
        // 冷却记录放在所有校验通过之后：校验失败不占用冷却，修正后可立即重试
        val now = System.currentTimeMillis()
        if (now - lastSaveAt < 500) return
        lastSaveAt = now

        val entity = CourseEntity(
            kch = s.editingKch.orEmpty(),
            kcmc = s.kcmc.trim(),
            jsxm = s.teacher.trim().ifEmpty { null },
            jc = s.jcMask,
            skjsl = s.location.trim().ifEmpty { null },
            skxql = s.dayOfWeek,
            week = s.weekString,
            bs = "1",
            color = s.color,
            skjc = "周${"一二三四五六日"[s.dayOfWeek - 1]}，第${SLOT_LABELS[s.startSection - 1]}节 - 第${SLOT_LABELS[s.endSection - 1]}节",
            weekFb = s.weeks.sorted().joinToString(","),
            xn = s.xn,
            xqm = s.xqm,
            isCustom = true,
        )
        _uiState.value = s.copy(saving = true, error = null)
        val gen = generation
        viewModelScope.launch {
            // 同一天同一时段只允许一节课（编辑时排除自身）；按学期隔离；
            // 周次集合无交集（错开周次/单双周）的课不算冲突
            val conflict = repo.findConflict(
                s.dayOfWeek, s.startSection, s.endSection,
                excludeKch = s.editingKch, xn = s.xn, xqm = s.xqm,
                weeks = s.weeks,
            )
            if (conflict != null) {
                if (generation != gen) return@launch
                _uiState.value = _uiState.value.copy(
                    saving = false,
                    error = "与「${conflict.kcmc}」时间冲突：周${"一二三四五六日"[s.dayOfWeek - 1]} 第${SLOT_LABELS[s.startSection - 1]}~${SLOT_LABELS[s.endSection - 1]}节已有课",
                )
                return@launch
            }
            val r = if (s.editingKch != null) repo.updateCustomCourse(entity)
            else repo.addCustomCourse(entity)
            // 表单已重置/换世代：结果落库即可，不再触碰新表单状态、不发完成事件
            if (generation != gen) return@launch
            if (r is SyncResult.Failed) {
                // 云端失败：回滚已完成（添加模式），留在页面提示重试
                _uiState.value = _uiState.value.copy(saving = false, error = r.message)
            } else {
                _uiState.value = _uiState.value.copy(saving = false)
                _savedEvents.emit(Unit)
            }
        }
    }
}
