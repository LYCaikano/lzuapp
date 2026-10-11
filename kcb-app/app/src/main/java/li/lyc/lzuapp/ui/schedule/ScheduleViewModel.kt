package li.lyc.lzuapp.ui.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import li.lyc.lzuapp.data.db.CourseEntity
import li.lyc.lzuapp.data.db.SemesterEntity
import li.lyc.lzuapp.data.repo.CourseRepository
import li.lyc.lzuapp.data.repo.SyncFailReason
import li.lyc.lzuapp.data.repo.SyncResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ScheduleUiState(
    val courses: List<CourseEntity> = emptyList(),
    val semester: SemesterEntity? = null,
    val currentWeek: Int = 1,
    val totalWeeks: Int = 20,
    /** 当前学期开始日期（校历 ksrq），用于推算每周日期列与今日高亮 */
    val semesterStart: String? = null,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
    val lastSyncText: String = "",
    /** 同步状态小字（同步成功/同步失败/登录状态失效/无网络） */
    val syncStatusText: String = "",
    /** 同步状态是否为失败（红色显示） */
    val syncStatusError: Boolean = false,
    /** 是否显示午1/午2（13、14 节） */
    val showNoonClasses: Boolean = false,
    /** 是否显示已隐藏的课程（false = 彻底不渲染；内存态，重进 App 恢复关闭） */
    val showHiddenCourses: Boolean = false,
)

class ScheduleViewModelFactory(
    private val repo: CourseRepository,
    private val authStore: li.lyc.lzuapp.data.AuthStore? = null,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return ScheduleViewModel(repo, authStore) as T
    }
}

class ScheduleViewModel(
    private val repo: CourseRepository,
    private val authStore: li.lyc.lzuapp.data.AuthStore? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScheduleUiState())
    val uiState: StateFlow<ScheduleUiState> = _uiState.asStateFlow()

    /**
     * 同步状态播报（一次性事件，Snackbar 用）。
     * Channel 缓冲：收集器未订阅时事件不丢失（如 App 启动时的"同步中"），
     * 消费后不残留，重建收集（如深色切换重建 Activity）不会重播旧事件。
     */
    private val _syncEvents = Channel<String>(Channel.BUFFERED)
    val syncEvents = _syncEvents.receiveAsFlow()

    private fun announce(text: String) {
        _syncEvents.trySend(text)
    }

    /** 当前学期校历（云端 getXlxx） */
    val semester: StateFlow<SemesterEntity?> = repo.observeSemester()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** 隐藏的系统课程：kch -> 隐藏周集合（0 = 全部周） */
    val hidden: StateFlow<Map<String, Set<Int>>> = repo.observeHidden()
        .map { list -> list.groupBy({ it.kch }, { it.week }).mapValues { (_, v) -> v.toSet() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** 当前学期课程：按校历 (xn, xqm) 过滤；学期字段缺失的历史数据归入显示，避免误隐藏 */
    val courses: StateFlow<List<CourseEntity>> =
        combine(repo.observeCourses(), repo.observeSemester()) { cs, sem ->
            if (sem == null) cs
            else cs.filter { c ->
                c.xn == null || c.xqm == null ||
                    (c.xn == sem.xn && c.xqm == sem.xqm?.toIntOrNull())
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // 午间课开关不持久化：杀进程重进后恢复为不显示
        // 离线启动：用本地校历补自定义课的学期归属，避免归属缺失导致不可见
        viewModelScope.launch { repo.backfillMissingSemester() }
        // 周次/总周数/学期开始日期：随校历联动。
        // 仅在校历实体内容变化时重置周次（忽略 updatedAt 时间戳），
        // 例行重同步、课程变化等不清空用户手动选周。
        var lastSem: SemesterEntity? = null
        viewModelScope.launch {
            repo.observeSemester().collect { sem ->
                if (sem?.copy(updatedAt = 0L) == lastSem?.copy(updatedAt = 0L)) return@collect
                lastSem = sem
                // currentWeek 钳制到 [1, totalWeeks]：学期结束后云端周次可能超过总周数，
                // 直接喂给 Pager 会越界（空白页/"第 25 周/共 20 周"）
                val weeks = sem?.totalWeeks?.coerceAtLeast(1) ?: 20
                _uiState.value = _uiState.value.copy(
                    currentWeek = (sem?.currentWeek ?: 1).coerceIn(1, weeks),
                    totalWeeks = weeks,
                    semesterStart = sem?.semesterStart,
                )
            }
        }
        viewModelScope.launch {
            // 未登录（刚安装/已退出）时不发起同步：登录态 false→true 边沿触发静默刷新，
            // 避免登录前误报"登录状态失效"、且登录后无需手动刷新。
            // VM 是 Activity 作用域，退出重登后不重建——必须持续收集边沿而非一次性 first；
            // 首次已是登录态时 lastLoggedIn 初值 false，同样只触发一次。
            // silentRefresh 自带 tryLock 去重，边沿连发/与手动刷新并发安全。
            var lastLoggedIn = false
            authStore?.state?.collect { s ->
                if (s.isLoggedIn && !lastLoggedIn) {
                    _uiState.value = _uiState.value.copy(loading = true, syncStatusText = "同步中")
                    announce("同步中")
                    when (val r = repo.silentRefresh()) {
                        is SyncResult.Success -> _uiState.value = _uiState.value.copy(
                            loading = false, refreshing = false, error = null,
                            syncStatusText = "同步成功", syncStatusError = false,
                        ).also { announce("同步成功") }
                        is SyncResult.Failed -> _uiState.value = _uiState.value.copy(
                            loading = false, refreshing = false,
                            error = if (hasLocalData()) null else r.message,
                            syncStatusText = r.statusText(), syncStatusError = true,
                        ).also { announce(r.statusText()) }
                    }
                }
                lastLoggedIn = s.isLoggedIn
            }
        }
    }

    fun setShowNoonClasses(show: Boolean) {
        // 仅内存态：不写 DataStore（重进 App 恢复为关闭）
        _uiState.value = _uiState.value.copy(showNoonClasses = show)
    }

    fun setShowHiddenCourses(show: Boolean) {
        // 仅内存态：不写 DataStore（重进 App 恢复为关闭）
        _uiState.value = _uiState.value.copy(showHiddenCourses = show)
    }

    /** 500ms 静默冷却：防连点 */
    private var lastRefreshAt = 0L
    private var lastDeleteAt = 0L

    fun refresh() {
        val now = System.currentTimeMillis()
        if (now - lastRefreshAt < 500) return
        lastRefreshAt = now
        _uiState.value = _uiState.value.copy(refreshing = true, error = null, syncStatusText = "同步中")
        announce("同步中")
        viewModelScope.launch {
            when (val r = repo.forceRefresh()) {
                is SyncResult.Success -> _uiState.value = _uiState.value.copy(
                    refreshing = false, syncStatusText = "同步成功", syncStatusError = false,
                ).also { announce("同步成功") }
                is SyncResult.Failed -> _uiState.value = _uiState.value.copy(
                    refreshing = false, error = r.message,
                    syncStatusText = r.statusText(), syncStatusError = true,
                ).also { announce(r.statusText()) }
            }
        }
    }

    /** 删除自定义课程（云端失败时提示） */
    fun deleteCourse(course: CourseEntity) {
        val now = System.currentTimeMillis()
        if (now - lastDeleteAt < 500) return
        lastDeleteAt = now
        viewModelScope.launch {
            val r = repo.deleteCustomCourse(course)
            if (r is SyncResult.Failed) {
                _uiState.value = _uiState.value.copy(error = r.message)
            }
        }
    }

    // ---------- 隐藏系统课程（纯本地） ----------

    fun hideCourse(course: CourseEntity, weeks: Set<Int>) {
        if (!cooldownHide()) return
        viewModelScope.launch { repo.hideCourse(course.kch, weeks) }
    }

    fun unhideCourse(course: CourseEntity, weeks: Set<Int>) {
        if (!cooldownHide()) return
        viewModelScope.launch { repo.unhideCourse(course.kch, weeks) }
    }

    fun unhideCourseAll(course: CourseEntity) {
        if (!cooldownHide()) return
        viewModelScope.launch { repo.unhideCourseAll(course.kch) }
    }

    private var lastHideAt = 0L
    private fun cooldownHide(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastHideAt < 500) return false
        lastHideAt = now
        return true
    }

    fun setWeek(week: Int) {
        val s = _uiState.value
        if (week in 1..s.totalWeeks) {
            _uiState.value = s.copy(currentWeek = week)
        }
    }

    private suspend fun hasLocalData(): Boolean =
        repo.observeCourses().first().isNotEmpty()
}

/** 同步结果 -> 状态小字 */
private fun SyncResult.statusText(): String = when (this) {
    is SyncResult.Success -> "同步成功"
    is SyncResult.Failed -> when (reason) {
        SyncFailReason.LOGIN -> "登录状态失效"
        SyncFailReason.NETWORK -> "无网络"
        SyncFailReason.OTHER -> "同步失败"
    }
}
