package li.lyc.lzuapp.ui.eval

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceUntilIdle
import li.lyc.lzuapp.data.api.model.EvalAnswer
import li.lyc.lzuapp.data.api.model.EvalCourse
import li.lyc.lzuapp.data.api.model.EvalOption
import li.lyc.lzuapp.data.api.model.EvalQuestion
import li.lyc.lzuapp.data.api.model.EvalSubmitItem
import li.lyc.lzuapp.data.api.model.EvalTask
import li.lyc.lzuapp.data.repo.EvalDataSource
import li.lyc.lzuapp.data.repo.EvalRepository.EvalResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EvalBatchRunnerTest {
    private val task = EvalTask(taskid = 1, taskname = "本学期", currentStatus = "进行中", pjfs = "打分")
    private val question = EvalQuestion(
        indexid = 1, type = "单选题", isscored = "是", isemptyed = "否",
        optionarr = listOf(EvalOption(1, "优秀", 10.0), EvalOption(2, "良好", 8.0), EvalOption(3, "一般", 5.0)),
    )
    private val course = EvalCourse(id = 1, coursecode = "A", coursename = "数据结构")

    private inner class Fake : EvalDataSource {
        var taskList = listOf(task)
        var courseList = listOf(course)
        var teacherList = emptyList<EvalCourse>()
        var questionList = listOf(question)
        var saved = emptyList<EvalAnswer>()
        var submitted = mutableListOf<Pair<EvalCourse, EvalSubmitItem>>()
        var failure = false
        var cancel = false
        var refreshVisible = true
        var onSubmit: () -> Unit = {}
        override suspend fun tasks() = EvalResult.Ok(taskList)
        override suspend fun courses(task: EvalTask) = EvalResult.Ok(courseList.map { c ->
            if (refreshVisible && submitted.any { it.first == c && it.second.taskid == task.taskid }) c.copy(hassubmit = 1, pjjgid = 100) else c
        })
        override suspend fun teachers(task: EvalTask, course: EvalCourse): EvalResult<List<EvalCourse>> {
            check(course.isEvaluated) { "教师必须在课程提交并刷新后查询" }
            return EvalResult.Ok(teacherList)
        }
        override suspend fun questions(indexid: String, pjcoursetype: String) = EvalResult.Ok(questionList)
        override suspend fun savedAnswers(pjjgid: Long) = EvalResult.Ok(saved)
        override suspend fun submit(course: EvalCourse, item: EvalSubmitItem): EvalResult<Unit> {
            if (cancel) throw CancellationException("cancelled")
            submitted.add(course to item)
            onSubmit()
            return if (failure) EvalResult.Err("timeout") else EvalResult.Ok(Unit)
        }
    }

    @Test fun submitsOrdinaryCourseAndSkipsAlreadyEvaluated() = runTest {
        val repo = Fake().apply { courseList = listOf(course, course.copy(id = 2, hassubmit = 1)) }
        val result = EvalBatchRunner(repo).run(null, { false }, {})
        assertEquals(1, result.submitted)
        assertEquals(1, result.skipped)
        assertEquals(10.0, repo.submitted.single().second.evaluateResult.single().index_score, 0.0)
    }

    @Test fun submitsJointCourseBeforeAllPendingTeachers() = runTest {
        val repo = Fake().apply {
            courseList = listOf(course.copy(pjtype = "hjjskc", hjjss = 3))
            teacherList = listOf(
                course.copy(pjtype = "hjjspj", jobnumber = "T1"),
                course.copy(pjtype = "hjjspj", jobnumber = "T2"),
                course.copy(pjtype = "hjjspj", jobnumber = "T3", pjjgid = 20, speakstates = 2),
            )
        }
        val result = EvalBatchRunner(repo).run(null, { false }, {})
        assertEquals(listOf("hjjskc", "hjjspj", "hjjspj"), repo.submitted.map { it.first.pjtype })
        assertEquals(listOf(1, 2, 2), repo.submitted.map { it.second.speakstates })
        assertEquals(3, result.submitted)
        assertEquals(1, result.skipped)
    }

    @Test fun stillVisitsTeachersWhenParentCourseIsAlreadyEvaluated() = runTest {
        val repo = Fake().apply {
            courseList = listOf(course.copy(pjtype = "hjjskc", hassubmit = 1, pjjgid = 100))
            teacherList = listOf(course.copy(pjtype = "hjjspj", jobnumber = "T1"))
        }
        val result = EvalBatchRunner(repo).run(null, { false }, {})
        assertEquals("hjjspj", repo.submitted.single().first.pjtype)
        assertEquals(1, result.skipped)
    }

    @Test fun doesNotSubmitTeachersUntilCourseStateIsVisible() = runTest {
        val repo = Fake().apply {
            courseList = listOf(course.copy(pjtype = "hjjskc"))
            refreshVisible = false
        }
        val result = EvalBatchRunner(repo).run(null, { false }, {})
        assertEquals(1, repo.submitted.size)
        assertTrue(result.result.orEmpty().contains("状态尚未更新"))
    }

    @Test fun filtersTasksByStatusTypeAndRequestedScope() = runTest {
        val repo = Fake().apply {
            taskList = listOf(task, task.copy(taskid = 2), task.copy(taskid = 3, currentStatus = "已结束"), task.copy(taskid = 4, pjfs = "排名打分"))
        }
        EvalBatchRunner(repo).run(2, { false }, {})
        assertEquals(listOf(2L), repo.submitted.map { it.second.taskid })
    }

    @Test fun evaluatesAllActiveScoringTasksFromTaskList() = runTest {
        val repo = Fake().apply {
            taskList = listOf(task, task.copy(taskid = 2), task.copy(taskid = 3, currentStatus = "已结束"), task.copy(taskid = 4, pjfs = "排名打分"))
        }
        val result = EvalBatchRunner(repo).run(null, { false }, {})
        assertEquals(listOf(1L, 2L), repo.submitted.map { it.second.taskid })
        assertEquals(2, result.submitted)
    }

    @Test fun failureStopsWithoutRetryingOrSubmittingNextCourse() = runTest {
        val repo = Fake().apply {
            courseList = listOf(course, course.copy(id = 2))
            failure = true
        }
        val result = EvalBatchRunner(repo).run(null, { false }, {})
        assertEquals(1, repo.submitted.size)
        assertEquals(0, result.submitted)
        assertTrue(result.result.orEmpty().contains("timeout"))
    }

    @Test fun stopFinishesCurrentSubmissionAndDoesNotStartAnother() = runTest {
        var stop = false
        val repo = Fake().apply {
            courseList = listOf(course, course.copy(id = 2))
            onSubmit = { stop = true }
        }
        val result = EvalBatchRunner(repo).run(null, { stop }, {})
        assertEquals(1, repo.submitted.size)
        assertEquals(1, result.submitted)
        assertTrue(result.result.orEmpty().startsWith("已停止"))
    }

    @Test fun unsupportedQuestionsAndMissingRequiredAnswersPreventSubmission() = runTest {
        for (q in listOf(question.copy(type = "多选题"), question.copy(isscored = "否"))) {
            val repo = Fake().apply { questionList = listOf(question, q.copy(indexid = 2)) }
            val result = EvalBatchRunner(repo).run(null, { false }, {})
            assertTrue(repo.submitted.isEmpty())
            assertTrue(result.result.orEmpty().startsWith("已暂停"))
        }
    }

    @Test fun draftAnswerIdsAndRequiredNonScoredAnswersArePreserved() = runTest {
        val repo = Fake().apply {
            courseList = listOf(course.copy(pjjgid = 100, hassubmit = 2))
            questionList = listOf(question, question.copy(indexid = 2, isscored = "否"))
            saved = listOf(EvalAnswer(id = 40, indexid = 1, option_id = 2), EvalAnswer(id = 41, indexid = 2, option_id = 2))
        }
        EvalBatchRunner(repo).run(null, { false }, {})
        val answers = repo.submitted.single().second.evaluateResult
        assertEquals(listOf(40L, 41L), answers.map { it.id })
        assertEquals("2", answers[1].option_id.toString().trim('"'))
    }

    @Test fun coroutineCancellationPropagates() = runTest {
        val repo = Fake().apply { cancel = true }
        try {
            EvalBatchRunner(repo).run(null, { false }, {})
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertTrue(repo.submitted.isEmpty())
        }
    }

    @Test fun autoFillProducesNoSuccessPopupAndKeepsScoreRestrictions() {
        repeat(100) {
            val state = EvalFormLogic.autoFill(EvalUiState(
                task = task.copy(sfqxzdzgf = "是", sfqxzdf = "是"),
                questions = (1L..5L).map { question.copy(indexid = it) },
            ))
            assertNull(state.message)
            assertTrue(state.singleAnswers.values.count { it == 2L } in 2..3)
            assertNull(EvalFormLogic.validate(state).submitError)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun viewModelGuardsDuplicateStartsNavigationAndRefreshWhileRunning() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = Fake()
            val vm = EvalViewModel(repo)
            advanceUntilIdle()
            vm.requestBatch()
            vm.startBatch()
            vm.startBatch()
            runCurrent()
            assertTrue(vm.uiState.value.batch.running)
            vm.openTask(task)
            vm.back()
            vm.retry()
            assertEquals(EvalPage.TASKS, vm.uiState.value.page)
            vm.stopBatch()
            advanceUntilIdle()
            assertEquals(1, repo.submitted.size)
            assertTrue(!vm.uiState.value.batch.running)
            assertTrue(!vm.uiState.value.loading)
            assertTrue(vm.uiState.value.batch.result.orEmpty().startsWith("已停止"))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun insufficientDowngradeCandidatesLeaveFormUnchangedAndExplainFailure() {
        val state = EvalFormLogic.autoFill(EvalUiState(task = task.copy(sfqxzdzgf = "是"), questions = listOf(question)))
        assertTrue(state.singleAnswers.isEmpty())
        assertTrue(state.message.orEmpty().contains("不足2道"))
    }
}
