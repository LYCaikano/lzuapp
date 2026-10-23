package li.lyc.lzuapp.ui.eval

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import li.lyc.lzuapp.data.api.model.EvalCourse
import li.lyc.lzuapp.data.api.model.EvalQuestion
import li.lyc.lzuapp.data.api.model.EvalSubmitAnswer
import li.lyc.lzuapp.data.api.model.EvalTask
import li.lyc.lzuapp.data.repo.EvalRepository

/** 评教页内三级导航：任务列表 → 课程列表 → 评教表单 */
enum class EvalPage { TASKS, COURSES, FORM }

data class EvalUiState(
    val page: EvalPage = EvalPage.TASKS,
    val loading: Boolean = true,
    val error: String? = null,
    /** 一次性 snackbar 消息（校验失败/提交结果） */
    val message: String? = null,
    /** 一次性 toast（任务类型不支持等轻提示） */
    val toast: String? = null,
    val tasks: List<EvalTask> = emptyList(),
    /** 当前任务（课程列表/表单页） */
    val task: EvalTask? = null,
    val courses: List<EvalCourse> = emptyList(),
    /** 当前课程（表单页） */
    val course: EvalCourse? = null,
    val questions: List<EvalQuestion> = emptyList(),
    /** 已评课只读回显 */
    val readonly: Boolean = false,
    /** 单选题作答：题目 indexid → 选项 id */
    val singleAnswers: Map<Long, Long> = emptyMap(),
    /** 问答题作答：题目 indexid → 文本 */
    val textAnswers: Map<Long, String> = emptyMap(),
    val submitting: Boolean = false,
    /** 校验失败后需滚动到的题目 indexid，UI 消费后清零 */
    val scrollToQuestion: Long? = null,
) {
    /** 含暂不支持的题型（打分/多选/填空）：展示提示并阻止提交，防漏答错提交 */
    val hasUnsupported: Boolean
        get() = questions.any { it.type != "单选题" && it.type != "问答题" }
}

class EvalViewModelFactory(private val repo: EvalRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return EvalViewModel(repo) as T
    }
}

class EvalViewModel(private val repo: EvalRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(EvalUiState())
    val uiState: StateFlow<EvalUiState> = _uiState.asStateFlow()

    private var lastActionAt = 0L

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
        _uiState.value = _uiState.value.copy(page = EvalPage.COURSES, task = task)
        loadCourses(task)
    }

    private fun loadCourses(task: EvalTask) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            when (val r = repo.courses(task.taskid)) {
                is EvalRepository.EvalResult.Ok ->
                    _uiState.value = _uiState.value.copy(loading = false, courses = r.data)
                is EvalRepository.EvalResult.Err ->
                    _uiState.value = _uiState.value.copy(loading = false, error = r.message)
            }
        }
    }

    /** 打开课程表单：未评（任务进行中）进入作答，已评进入只读回显 */
    fun openCourse(course: EvalCourse) {
        if (!cooldown()) return
        val task = _uiState.value.task ?: return
        if (course.hassubmit != 1 && task.currentStatus != "进行中") return
        _uiState.value = _uiState.value.copy(
            page = EvalPage.FORM,
            course = course,
            readonly = course.hassubmit == 1,
            questions = emptyList(),
            singleAnswers = emptyMap(),
            textAnswers = emptyMap(),
        )
        loadForm(task, course)
    }

    private fun loadForm(task: EvalTask, course: EvalCourse) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            val questions = when (val r = repo.questions(task.indexid, course.pjcoursetype)) {
                is EvalRepository.EvalResult.Ok -> r.data.sortedBy { it.ordor }
                is EvalRepository.EvalResult.Err -> {
                    _uiState.value = _uiState.value.copy(loading = false, error = r.message)
                    return@launch
                }
            }
            // 已评/暂存回填：单选 index_title=选项 title，问答 index_title=文本
            val saved = course.pjjgid?.let { pid ->
                when (val r = repo.savedAnswers(pid)) {
                    is EvalRepository.EvalResult.Ok -> r.data
                    is EvalRepository.EvalResult.Err -> emptyList()
                }
            }.orEmpty().associateBy { it.indexid }
            val single = mutableMapOf<Long, Long>()
            val text = mutableMapOf<Long, String>()
            for (q in questions) {
                val a = saved[q.indexid] ?: continue
                when (q.type) {
                    "单选题" -> q.optionarr.firstOrNull { it.title == a.index_title }
                        ?.let { single[q.indexid] = it.id }
                    "问答题" -> text[q.indexid] = a.index_title
                }
            }
            _uiState.value = _uiState.value.copy(
                loading = false, questions = questions,
                singleAnswers = single, textAnswers = text,
            )
        }
    }

    /** 表单 → 课程 → 任务逐层退；任务层的退出由页面 onBack 处理 */
    fun back() {
        when (_uiState.value.page) {
            EvalPage.FORM -> _uiState.value =
                _uiState.value.copy(page = EvalPage.COURSES, loading = false, error = null)
            EvalPage.COURSES -> _uiState.value =
                _uiState.value.copy(page = EvalPage.TASKS, loading = false, error = null)
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
            EvalPage.FORM -> if (s.task != null && s.course != null) loadForm(s.task, s.course)
        }
    }

    fun setSingleAnswer(indexid: Long, optionId: Long) {
        if (_uiState.value.readonly) return
        _uiState.value = _uiState.value.copy(
            singleAnswers = _uiState.value.singleAnswers + (indexid to optionId),
        )
    }

    fun setTextAnswer(indexid: Long, text: String) {
        if (_uiState.value.readonly) return
        _uiState.value = _uiState.value.copy(
            textAnswers = _uiState.value.textAnswers + (indexid to text),
        )
    }

    fun submit() {
        val s = _uiState.value
        val task = s.task ?: return
        val course = s.course ?: return
        if (s.readonly || s.submitting || s.hasUnsupported) return
        // 必答校验：isemptyed=="否" 的题必须已作答，失败则滚动到第一道未答题
        val firstMissing = s.questions.firstOrNull { q ->
            if (q.isemptyed != "否") return@firstOrNull false
            when (q.type) {
                "单选题" -> s.singleAnswers[q.indexid] == null
                "问答题" -> s.textAnswers[q.indexid].isNullOrBlank()
                else -> false
            }
        }
        if (firstMissing != null) {
            _uiState.value = _uiState.value.copy(
                message = "还有必答题未作答",
                scrollToQuestion = firstMissing.indexid,
            )
            return
        }
        val answers = s.questions.map { q ->
            when (q.type) {
                "单选题" -> {
                    val opt = q.optionarr.firstOrNull { it.id == s.singleAnswers[q.indexid] }
                    EvalSubmitAnswer(
                        indexid = q.indexid.toString(), index_order = q.ordor,
                        index_type = q.type, sfbt = q.isemptyed,
                        index_score = opt?.score ?: 0.0,
                        index_title = opt?.title.orEmpty(),
                        option_id = opt?.id?.toString() ?: "0",
                    )
                }
                else -> EvalSubmitAnswer(
                    indexid = q.indexid.toString(), index_order = q.ordor,
                    index_type = q.type, sfbt = q.isemptyed,
                    index_score = 0.0,
                    index_title = s.textAnswers[q.indexid].orEmpty(),
                )
            }
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(submitting = true)
            val item = EvalRepository.buildSubmitItem(course, task, answers)
            when (val r = repo.submit(listOf(item))) {
                is EvalRepository.EvalResult.Ok -> {
                    // 成功后退回课程列表并刷新
                    _uiState.value = _uiState.value.copy(
                        submitting = false, page = EvalPage.COURSES, message = "提交成功",
                    )
                    loadCourses(task)
                }
                is EvalRepository.EvalResult.Err ->
                    _uiState.value = _uiState.value.copy(submitting = false, message = r.message)
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
