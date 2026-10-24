package li.lyc.lzuapp.ui.eval

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import li.lyc.lzuapp.data.api.model.EvalCourse
import li.lyc.lzuapp.data.api.model.EvalOption
import li.lyc.lzuapp.data.api.model.EvalQuestion
import li.lyc.lzuapp.data.api.model.EvalSubmitAnswer
import li.lyc.lzuapp.data.api.model.EvalTask
import li.lyc.lzuapp.data.repo.EvalRepository

/** 任务列表 → 课程列表 → 合讲教师列表 → 评教表单；普通课程跳过教师列表 */
enum class EvalPage { TASKS, COURSES, TEACHERS, FORM }

data class EvalUiState(
    val page: EvalPage = EvalPage.TASKS,
    val loading: Boolean = true,
    val error: String? = null,
    /** 提交校验或服务器提交失败，持续显示在当前表单底部 */
    val submitError: String? = null,
    /** 一次性 snackbar 消息（自动填写/提交结果） */
    val message: String? = null,
    /** 一次性 toast（任务类型不支持等轻提示） */
    val toast: String? = null,
    val tasks: List<EvalTask> = emptyList(),
    val task: EvalTask? = null,
    val courses: List<EvalCourse> = emptyList(),
    val teachers: List<EvalCourse> = emptyList(),
    /** 教师列表显示父课程，表单显示当前课程或教师 */
    val course: EvalCourse? = null,
    val teacherParent: EvalCourse? = null,
    val questions: List<EvalQuestion> = emptyList(),
    val readonly: Boolean = false,
    /** 单选题作答：题目 indexid → 选项 id */
    val singleAnswers: Map<Long, Long> = emptyMap(),
    /** 问答题作答：题目 indexid → 文本 */
    val textAnswers: Map<Long, String> = emptyMap(),
    /** 已评/暂存答案 ID：题目 indexid → 答案 id */
    val answerIds: Map<Long, Long> = emptyMap(),
    val submitting: Boolean = false,
    /** 校验失败后需滚动到的题目 indexid，UI 消费后清零 */
    val scrollToQuestion: Long? = null,
    val showAutoFillConfirm: Boolean = false,
) {
    val hasUnsupported: Boolean
        get() = questions.any { it.type != "单选题" && it.type != "问答题" }

    val totalScore: Double
        get() = questions.asSequence()
            .filter { it.isscored == "是" && it.type == "单选题" }
            .sumOf { q -> q.optionarr.firstOrNull { it.id == singleAnswers[q.indexid] }?.score ?: 0.0 }

    val hasUserInput: Boolean
        get() = singleAnswers.isNotEmpty() || textAnswers.values.any { it.isNotBlank() }
}

class EvalViewModelFactory(private val repo: EvalRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return EvalViewModel(repo) as T
    }
}

private data class DowngradeCandidate(
    val indexid: Long,
    val optionId: Long,
)

class EvalViewModel(private val repo: EvalRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(EvalUiState())
    val uiState: StateFlow<EvalUiState> = _uiState.asStateFlow()

    private var lastActionAt = 0L
    private var coursesGeneration = 0L
    private var teachersGeneration = 0L

    init {
        loadTasks()
    }

    private fun cooldown(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastActionAt < 500) return false
        lastActionAt = now
        return true
    }

    fun loadTasks() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            when (val r = repo.tasks()) {
                is EvalRepository.EvalResult.Ok ->
                    _uiState.value = _uiState.value.copy(loading = false, tasks = r.data)
                is EvalRepository.EvalResult.Err ->
                    _uiState.value = _uiState.value.copy(loading = false, error = r.message)
            }
        }
    }

    /** 打开任务：进行中 + 打分制才支持，其他类型提示去官方 App */
    fun openTask(task: EvalTask) {
        if (!cooldown()) return
        if (task.currentStatus != "进行中") return
        if (task.pjfs != "打分") {
            _uiState.value = _uiState.value.copy(toast = "该类型请在官方 App 完成")
            return
        }
        _uiState.value = _uiState.value.copy(
            page = EvalPage.COURSES,
            task = task,
            course = null,
            teacherParent = null,
            teachers = emptyList(),
        )
        loadCourses(task)
    }

    private fun loadCourses(task: EvalTask) {
        val request = ++coursesGeneration
        _uiState.value = _uiState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            when (val r = repo.courses(task)) {
                is EvalRepository.EvalResult.Ok -> {
                    if (!isCurrentCourseRequest(task, request)) return@launch
                    _uiState.value = _uiState.value.copy(loading = false, courses = r.data)
                }
                is EvalRepository.EvalResult.Err -> {
                    if (!isCurrentCourseRequest(task, request)) return@launch
                    _uiState.value = _uiState.value.copy(loading = false, error = r.message)
                }
            }
        }
    }

    private fun isCurrentCourseList(task: EvalTask): Boolean =
        _uiState.value.page == EvalPage.COURSES && _uiState.value.task == task

    private fun isCurrentCourseRequest(task: EvalTask, request: Long): Boolean =
        request == coursesGeneration && isCurrentCourseList(task)

    fun openCourse(course: EvalCourse) {
        if (!cooldown()) return
        val task = _uiState.value.task ?: return
        if (!course.isEvaluated && task.currentStatus != "进行中") return
        openForm(task, course, teacherParent = null)
    }

    fun openTeachers(course: EvalCourse) {
        if (!cooldown()) return
        val task = _uiState.value.task ?: return
        if (course.pjtype != "hjjskc") return
        if ((course.hjjss ?: 0) > (task.hjjstask ?: 0) && course.pjjgid == null) {
            _uiState.value = _uiState.value.copy(toast = "请先进行课程评价")
            return
        }
        _uiState.value = _uiState.value.copy(
            page = EvalPage.TEACHERS,
            course = course,
            teacherParent = course,
            teachers = emptyList(),
            questions = emptyList(),
            singleAnswers = emptyMap(),
            textAnswers = emptyMap(),
            answerIds = emptyMap(),
        )
        loadTeachers(task, course)
    }

    private fun loadTeachers(task: EvalTask, course: EvalCourse) {
        val request = ++teachersGeneration
        _uiState.value = _uiState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            when (val r = repo.teachers(task, course)) {
                is EvalRepository.EvalResult.Ok -> {
                    if (!isCurrentTeacherRequest(course, request)) return@launch
                    _uiState.value = _uiState.value.copy(loading = false, teachers = r.data)
                }
                is EvalRepository.EvalResult.Err -> {
                    if (!isCurrentTeacherRequest(course, request)) return@launch
                    _uiState.value = _uiState.value.copy(loading = false, error = r.message)
                }
            }
        }
    }

    private fun isCurrentTeachers(course: EvalCourse): Boolean {
        val s = _uiState.value
        return s.page == EvalPage.TEACHERS && s.course == course && s.teacherParent == course
    }

    private fun isCurrentTeacherRequest(course: EvalCourse, request: Long): Boolean =
        request == teachersGeneration && isCurrentTeachers(course)

    private fun sameEvaluationTarget(first: EvalCourse, second: EvalCourse): Boolean {
        if (first.pjtype != second.pjtype ||
            first.coursecode != second.coursecode ||
            first.coursename != second.coursename ||
            first.pjcoursetype != second.pjcoursetype ||
            first.yearterm != second.yearterm
        ) {
            return false
        }
        if (second.pjtype == "hjjspj") {
            return first.teachername == second.teachername && first.jobnumber == second.jobnumber
        }
        if (first.id != 0L || second.id != 0L) return first.id == second.id
        return first.classno == second.classno &&
            first.teachername == second.teachername &&
            first.jobnumber == second.jobnumber
    }

    private fun List<EvalCourse>.hasEvaluatedTarget(target: EvalCourse): Boolean =
        any { it.isEvaluated && sameEvaluationTarget(it, target) }

    private suspend fun refreshCoursesAfterSubmit(task: EvalTask, target: EvalCourse) {
        val request = ++coursesGeneration
        repeat(2) { attempt ->
            delay(if (attempt == 0) 500L else 1200L)
            if (!isCurrentCourseRequest(task, request)) return
            when (val r = repo.courses(task)) {
                is EvalRepository.EvalResult.Ok -> {
                    if (!isCurrentCourseRequest(task, request)) return
                    _uiState.value = _uiState.value.copy(
                        loading = false,
                        courses = r.data,
                        error = null,
                    )
                    if (r.data.hasEvaluatedTarget(target)) return
                    if (attempt == 1) {
                        _uiState.value = _uiState.value.copy(
                            message = "提交成功，但列表尚未更新，请稍后刷新",
                        )
                    }
                }
                is EvalRepository.EvalResult.Err -> {
                    if (!isCurrentCourseRequest(task, request)) return
                    _uiState.value = _uiState.value.copy(loading = false, error = null)
                    if (attempt == 1) {
                        _uiState.value = _uiState.value.copy(
                            message = "提交成功，但列表刷新失败，请稍后刷新",
                        )
                    }
                }
            }
            if (attempt == 0 && isCurrentCourseRequest(task, request)) {
                _uiState.value = _uiState.value.copy(loading = true)
            }
        }
    }

    private suspend fun refreshTeachersAfterSubmit(
        task: EvalTask,
        parent: EvalCourse,
        target: EvalCourse,
    ) {
        val request = ++teachersGeneration
        repeat(2) { attempt ->
            delay(if (attempt == 0) 500L else 1200L)
            if (!isCurrentTeacherRequest(parent, request)) return
            when (val r = repo.teachers(task, parent)) {
                is EvalRepository.EvalResult.Ok -> {
                    if (!isCurrentTeacherRequest(parent, request)) return
                    _uiState.value = _uiState.value.copy(
                        loading = false,
                        teachers = r.data,
                        error = null,
                    )
                    if (r.data.hasEvaluatedTarget(target)) return
                    if (attempt == 1) {
                        _uiState.value = _uiState.value.copy(
                            message = "提交成功，但列表尚未更新，请稍后刷新",
                        )
                    }
                }
                is EvalRepository.EvalResult.Err -> {
                    if (!isCurrentTeacherRequest(parent, request)) return
                    _uiState.value = _uiState.value.copy(loading = false, error = null)
                    if (attempt == 1) {
                        _uiState.value = _uiState.value.copy(
                            message = "提交成功，但列表刷新失败，请稍后刷新",
                        )
                    }
                }
            }
            if (attempt == 0 && isCurrentTeacherRequest(parent, request)) {
                _uiState.value = _uiState.value.copy(loading = true)
            }
        }
    }

    fun openTeacher(teacher: EvalCourse) {
        if (!cooldown()) return
        val s = _uiState.value
        val task = s.task ?: return
        val parent = s.teacherParent ?: return
        if (!teacher.isEvaluated && task.currentStatus != "进行中") return
        openForm(task, teacher, teacherParent = parent)
    }

    private fun openForm(task: EvalTask, course: EvalCourse, teacherParent: EvalCourse?) {
        _uiState.value = _uiState.value.copy(
            page = EvalPage.FORM,
            course = course,
            teacherParent = teacherParent,
            readonly = shouldReadonly(task, course),
            questions = emptyList(),
            singleAnswers = emptyMap(),
            textAnswers = emptyMap(),
            answerIds = emptyMap(),
            submitting = false,
            error = null,
            submitError = null,
        )
        loadForm(task, course)
    }

    private fun shouldReadonly(task: EvalTask, course: EvalCourse): Boolean =
        course.isEvaluated && (task.currentStatus != "进行中" || task.sfkydcpj != "是")

    private fun shouldLoadAnswers(course: EvalCourse): Boolean = when (course.pjtype) {
        "hjjspj" -> (course.pjjgid ?: 0L) != 0L && course.speakstates == 2
        else -> course.pjjgid != null
    }

    private fun isCurrentForm(course: EvalCourse): Boolean =
        _uiState.value.page == EvalPage.FORM && _uiState.value.course == course

    private fun loadForm(task: EvalTask, course: EvalCourse) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            val questions = when (val r = repo.questions(task.indexid, course.pjcoursetype)) {
                is EvalRepository.EvalResult.Ok -> r.data.sortedBy { it.ordor }
                is EvalRepository.EvalResult.Err -> {
                    if (isCurrentForm(course)) {
                        _uiState.value = _uiState.value.copy(loading = false, error = r.message)
                    }
                    return@launch
                }
            }
            if (!isCurrentForm(course)) return@launch

            val saved = if (shouldLoadAnswers(course)) {
                when (val r = repo.savedAnswers(requireNotNull(course.pjjgid))) {
                    is EvalRepository.EvalResult.Ok -> r.data
                    is EvalRepository.EvalResult.Err -> {
                        if (isCurrentForm(course)) {
                            _uiState.value = _uiState.value.copy(
                                loading = false,
                                error = "答案加载失败：${r.message}",
                            )
                        }
                        return@launch
                    }
                }
            } else {
                emptyList()
            }
            if (!isCurrentForm(course)) return@launch

            val savedByQuestion = saved.associateBy { it.indexid }
            val single = mutableMapOf<Long, Long>()
            val text = mutableMapOf<Long, String>()
            for (q in questions) {
                val a = savedByQuestion[q.indexid] ?: continue
                when (q.type) {
                    "单选题" -> {
                        val option = q.optionarr.firstOrNull { it.id == a.option_id }
                            ?: q.optionarr.firstOrNull { it.title == a.index_title }
                        option?.let { single[q.indexid] = it.id }
                    }
                    "问答题" -> text[q.indexid] = a.index_title
                }
            }
            _uiState.value = _uiState.value.copy(
                loading = false,
                questions = questions,
                singleAnswers = single,
                textAnswers = text,
                answerIds = saved.associate { it.indexid to it.id },
            )
        }
    }

    /** 表单按来源退回教师或课程列表，再逐级退回任务列表 */
    fun back() {
        val s = _uiState.value
        if (s.submitting) return
        when (s.page) {
            EvalPage.FORM -> {
                val parent = s.teacherParent
                if (s.course?.pjtype == "hjjspj" && parent != null) {
                    _uiState.value = s.copy(
                        page = EvalPage.TEACHERS,
                        course = parent,
                        loading = false,
                        error = null,
                        questions = emptyList(),
                        singleAnswers = emptyMap(),
                        textAnswers = emptyMap(),
                        answerIds = emptyMap(),
                    )
                } else {
                    _uiState.value = s.copy(
                        page = EvalPage.COURSES,
                        course = null,
                        teacherParent = null,
                        loading = false,
                        error = null,
                        questions = emptyList(),
                        singleAnswers = emptyMap(),
                        textAnswers = emptyMap(),
                        answerIds = emptyMap(),
                    )
                }
            }
            EvalPage.TEACHERS -> {
                _uiState.value = s.copy(
                    page = EvalPage.COURSES,
                    course = null,
                    teacherParent = null,
                    teachers = emptyList(),
                    loading = s.task != null,
                    error = null,
                )
                s.task?.let { loadCourses(it) }
            }
            EvalPage.COURSES -> _uiState.value = s.copy(
                page = EvalPage.TASKS,
                task = null,
                course = null,
                teacherParent = null,
                loading = false,
                error = null,
            )
            EvalPage.TASKS -> {}
        }
    }

    /** 当前页加载失败重试 */
    fun retry() {
        if (!cooldown()) return
        val s = _uiState.value
        when (s.page) {
            EvalPage.TASKS -> loadTasks()
            EvalPage.COURSES -> s.task?.let { loadCourses(it) }
            EvalPage.TEACHERS -> if (s.task != null && s.course != null) {
                loadTeachers(s.task, s.course)
            }
            EvalPage.FORM -> if (s.task != null && s.course != null) loadForm(s.task, s.course)
        }
    }

    fun setSingleAnswer(indexid: Long, optionId: Long) {
        val s = _uiState.value
        if (s.readonly || s.submitting) return
        _uiState.value = s.copy(
            singleAnswers = s.singleAnswers + (indexid to optionId),
            submitError = null,
        )
    }

    fun setTextAnswer(indexid: Long, text: String) {
        val s = _uiState.value
        if (s.readonly || s.submitting) return
        _uiState.value = s.copy(
            textAnswers = s.textAnswers + (indexid to text),
            submitError = null,
        )
    }

    fun requestAutoFill() {
        val s = _uiState.value
        if (s.readonly || s.submitting) return
        if (s.hasUnsupported) {
            _uiState.value = s.copy(message = "包含暂不支持的题型，无法一键填写")
        } else if (s.hasUserInput) {
            _uiState.value = s.copy(showAutoFillConfirm = true)
        } else {
            autoFill()
        }
    }

    fun confirmAutoFill() {
        _uiState.value = _uiState.value.copy(showAutoFillConfirm = false)
        autoFill()
    }

    fun dismissAutoFill() {
        _uiState.value = _uiState.value.copy(showAutoFillConfirm = false)
    }

    private fun autoFill() {
        val current = _uiState.value
        val task = current.task ?: return
        if (current.readonly || current.submitting || current.hasUnsupported) return
        val s = current.copy(submitError = null)
        _uiState.value = s
        val scored = s.questions.filter { it.isscored == "是" && it.type == "单选题" }
        if (scored.isEmpty()) {
            _uiState.value = s.copy(message = "当前没有可自动填写的计分单选题")
            return
        }
        if (scored.any { it.optionarr.isEmpty() }) {
            _uiState.value = s.copy(message = "部分计分单选题没有可用选项，无法自动填写")
            return
        }

        val selected = scored.associate { q ->
            q.indexid to requireNotNull(q.optionarr.maxByOrNull { it.score }).id
        }.toMutableMap()
        var downgradeCount = 0
        if (task.sfqxzdzgf == "是") {
            val candidates = scored.mapNotNull { q ->
                val highest = requireNotNull(q.optionarr.maxByOrNull { it.score })
                val second = q.optionarr.filter { it.score < highest.score }.maxByOrNull { it.score }
                second?.let { DowngradeCandidate(q.indexid, it.id) }
            }
            if (candidates.size < 2) {
                _uiState.value = s.copy(
                    message = "可用于降档的计分单选题不足2道（当前${candidates.size}道），无法一键高分",
                )
                return
            }
            downgradeCount = if (candidates.size == 2) 2 else (2..3).random()
            candidates.shuffled().take(downgradeCount).forEach { candidate ->
                selected[candidate.indexid] = candidate.optionId
            }
        }
        if (task.sfqxzdf == "是" && isAllMinimum(scored, selected)) {
            _uiState.value = s.copy(
                message = if (downgradeCount > 0) {
                    "本次随机降档${downgradeCount}道后会触发全最低分限制，未应用自动填写，请重试或手动调整"
                } else {
                    "当前题目最高分与最低分相同，无法按规则一键满分"
                },
            )
            return
        }

        val comments = s.questions.asSequence()
            .filter { it.type == "问答题" }
            .associate { it.indexid to defaultComment(it.title) }
        _uiState.value = s.copy(
            singleAnswers = s.singleAnswers + selected,
            textAnswers = s.textAnswers + comments,
            message = if (downgradeCount > 0) {
                "已按规则填写高分：随机将${downgradeCount}道计分单选题降为次高档，其余计分题保持最高分，并非全满分。请检查后手动提交"
            } else {
                "已填写最高分选项和中文评语，请检查后手动提交"
            },
        )
    }

    private fun defaultComment(title: String): String {
        val asksForSuggestion = listOf("建议", "改进", "提高", "不足", "问题", "意见").any(title::contains)
        return if (asksForSuggestion) {
            "无"
        } else {
            "教师备课充分，讲解清晰，课程内容安排合理，能够耐心解答问题，并结合实际帮助理解，我在本课程中收获很大。"
        }
    }

    private fun selectedOption(q: EvalQuestion, answers: Map<Long, Long>): EvalOption? =
        q.optionarr.firstOrNull { it.id == answers[q.indexid] }

    private fun isAllMaximum(questions: List<EvalQuestion>, answers: Map<Long, Long>): Boolean =
        questions.isNotEmpty() && questions.all { q ->
            val selected = selectedOption(q, answers) ?: return@all false
            val maximum = q.optionarr.maxOfOrNull { it.score } ?: return@all false
            selected.score == maximum
        }

    private fun isAllMinimum(questions: List<EvalQuestion>, answers: Map<Long, Long>): Boolean =
        questions.isNotEmpty() && questions.all { q ->
            val selected = selectedOption(q, answers) ?: return@all false
            val minimum = q.optionarr.minOfOrNull { it.score } ?: return@all false
            selected.score == minimum
        }

    fun submit() {
        val current = _uiState.value
        val task = current.task ?: return
        val course = current.course ?: return
        if (current.readonly || current.submitting) return
        val s = current.copy(submitError = null)
        _uiState.value = s
        if (s.hasUnsupported) {
            _uiState.value = s.copy(submitError = "包含暂不支持的题型，请前往官方 App 完成评价")
            return
        }

        val firstMissing = s.questions.firstOrNull { q ->
            if (q.isemptyed != "否") return@firstOrNull false
            when (q.type) {
                "单选题" -> selectedOption(q, s.singleAnswers) == null
                "问答题" -> s.textAnswers[q.indexid].isNullOrBlank()
                else -> false
            }
        }
        if (firstMissing != null) {
            _uiState.value = s.copy(
                submitError = "还有必答题未作答，请检查带 * 的题目",
                scrollToQuestion = firstMissing.indexid,
            )
            return
        }

        val scored = s.questions.filter { it.isscored == "是" && it.type == "单选题" }
        if (task.sfqxzdzgf == "是" && isAllMaximum(scored, s.singleAnswers)) {
            _uiState.value = s.copy(submitError = "评价不能全选最高分！")
            return
        }
        if (task.sfqxzdf == "是" && isAllMinimum(scored, s.singleAnswers)) {
            _uiState.value = s.copy(submitError = "评价不能全选最低分！")
            return
        }

        val answers = s.questions.map { q ->
            when (q.type) {
                "单选题" -> {
                    val opt = selectedOption(q, s.singleAnswers)
                    EvalSubmitAnswer(
                        id = s.answerIds[q.indexid] ?: 0,
                        indexid = q.indexid,
                        index_order = q.ordor,
                        index_type = q.type,
                        sfbt = q.isemptyed,
                        index_score = opt?.score ?: 0.0,
                        index_title = opt?.title.orEmpty(),
                        option_id = JsonPrimitive(opt?.id?.toString().orEmpty()),
                    )
                }
                else -> EvalSubmitAnswer(
                    id = s.answerIds[q.indexid] ?: 0,
                    indexid = q.indexid,
                    index_order = q.ordor,
                    index_type = q.type,
                    sfbt = q.isemptyed,
                    index_score = 0.0,
                    index_title = s.textAnswers[q.indexid].orEmpty(),
                    option_id = JsonPrimitive(0),
                )
            }
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(submitting = true)
            val item = EvalRepository.buildSubmitItem(
                course = course,
                task = task,
                questions = s.questions,
                answers = answers,
                isDcpj = !s.readonly && task.sfkydcpj == "是",
            )
            when (val r = repo.submit(course, item)) {
                is EvalRepository.EvalResult.Ok -> {
                    val parent = s.teacherParent
                    if (course.pjtype == "hjjspj" && parent != null) {
                        _uiState.value = _uiState.value.copy(
                            submitting = false,
                            page = EvalPage.TEACHERS,
                            course = parent,
                            loading = true,
                            error = null,
                            questions = emptyList(),
                            singleAnswers = emptyMap(),
                            textAnswers = emptyMap(),
                            answerIds = emptyMap(),
                            message = "提交成功",
                        )
                        refreshTeachersAfterSubmit(task, parent, course)
                    } else {
                        _uiState.value = _uiState.value.copy(
                            submitting = false,
                            page = EvalPage.COURSES,
                            course = null,
                            teacherParent = null,
                            loading = true,
                            error = null,
                            questions = emptyList(),
                            singleAnswers = emptyMap(),
                            textAnswers = emptyMap(),
                            answerIds = emptyMap(),
                            message = "提交成功",
                        )
                        refreshCoursesAfterSubmit(task, course)
                    }
                }
                is EvalRepository.EvalResult.Err -> _uiState.value = _uiState.value.copy(
                    submitting = false,
                    submitError = "提交失败：${r.message.ifBlank { "请稍后重试" }}",
                )
            }
        }
    }

    fun messageShown() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    fun toastShown() {
        _uiState.value = _uiState.value.copy(toast = null)
    }

    fun scrollHandled() {
        _uiState.value = _uiState.value.copy(scrollToQuestion = null)
    }
}
