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
import li.lyc.lzuapp.data.repo.targetKey
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
        var claimAllowed = true
        var failure = false
        var cancel = false
        var courseReads = 0
        var teacherReads = 0
        var refreshVisible = true
        var onSubmit: () -> Unit = {}
        var onQuestions: () -> Unit = {}
        val journal = mutableSetOf<List<String>>()
        override suspend fun wasBatchAttempted(task: EvalTask, course: EvalCourse) = (listOf(task.taskid.toString()) + course.targetKey()) in journal
        override suspend fun tasks() = EvalResult.Ok(taskList)
        override suspend fun courses(task: EvalTask): EvalResult<List<EvalCourse>> {
            courseReads++
            return EvalResult.Ok(courseList.map { c ->
            if (refreshVisible && submitted.any { it.first == c && it.second.taskid == task.taskid }) c.copy(hassubmit = 1, pjjgid = 100) else c
        })
        }
        override suspend fun teachers(task: EvalTask, course: EvalCourse): EvalResult<List<EvalCourse>> {
            teacherReads++
            check(course.isEvaluated) { "教师必须在课程提交并刷新后查询" }
            return EvalResult.Ok(teacherList)
        }
        override suspend fun questions(indexid: String, pjcoursetype: String): EvalResult<List<EvalQuestion>> {
            onQuestions()
            return EvalResult.Ok(questionList)
        }
        override suspend fun savedAnswers(pjjgid: Long) = EvalResult.Ok(saved)
        override suspend fun submit(course: EvalCourse, item: EvalSubmitItem, batch: Boolean): EvalResult<Unit> {
            if (!claimAllowed) return EvalResult.Err("已被其他流程认领")
            val key = listOf(item.taskid.toString()) + course.targetKey()
            if (batch && key in journal) return EvalResult.Err("结果待核对")
            journal.add(key)
            if (cancel) throw CancellationException("cancelled")
            submitted.add(course to item)
            onSubmit()
            return if (failure) EvalResult.Err("timeout") else EvalResult.Ok(Unit)
        }
    }

    @Test fun submitsOrdinaryCourseAndSkipsAlreadyEvaluated() = runTest {
        val repo = Fake().apply { courseList = listOf(course, course.copy(id = 2, coursecode = "B", hassubmit = 1)) }
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
            courseList = listOf(course, course.copy(id = 2, coursecode = "B"))
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
            courseList = listOf(course, course.copy(id = 2, coursecode = "B"))
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

    @Test fun draftsAreProtectedWithoutLoadingOrOverwritingAnswers() = runTest {
        val repo = Fake().apply {
            courseList = listOf(course.copy(pjjgid = 100, hassubmit = 2))
            onQuestions = { error("Draft must not be opened for autofill") }
        }
        val plan = EvalBatchRunner(repo).preview(null)
        assertEquals(1, plan.protected)
        val result = EvalBatchRunner(repo).run(null, { false }, {}, plan)
        assertEquals(1, result.protected)
        assertTrue(repo.submitted.isEmpty())
    }

    @Test fun duplicateTasksAndRowsSubmitOnlyOnceEvenWhenServerStaysStale() = runTest {
        val repo = Fake().apply {
            taskList = listOf(task, task)
            courseList = listOf(course, course.copy(id = 999))
            refreshVisible = false
        }
        val result = EvalBatchRunner(repo).run(null, { false }, {})
        assertEquals(1, result.submitted)
        EvalBatchRunner(repo).run(null, { false }, {})
        assertEquals(1, repo.submitted.size)
    }

    @Test fun newDraftOrEvaluationDuringFormLoadingWinsOverOldSnapshot() = runTest {
        for (newState in listOf(course.copy(hassubmit = 1), course.copy(hassubmit = 2, pjjgid = 100))) {
            val repo = Fake().apply { onQuestions = { courseList = listOf(newState) } }
            EvalBatchRunner(repo).run(null, { false }, {})
            assertTrue(repo.submitted.isEmpty())
        }
    }

    @Test fun previewDoesNotSubmitAndRunStopsAtTwentyWrites() = runTest {
        val repo = Fake().apply { courseList = (1L..25L).map { course.copy(id = it, coursecode = "C$it") } }
        val runner = EvalBatchRunner(repo)
        val plan = runner.preview(null)
        assertEquals(25, plan.pendingCourses)
        assertTrue(repo.submitted.isEmpty())
        val result = runner.run(null, { false }, {}, plan)
        assertEquals(20, repo.submitted.size)
        assertTrue(result.result.orEmpty().contains("20项上限"))
    }

    @Test fun unknownFailedWriteStaysProtectedOnNextRun() = runTest {
        val repo = Fake().apply { failure = true; refreshVisible = false }
        EvalBatchRunner(repo).run(null, { false }, {})
        repo.failure = false
        val result = EvalBatchRunner(repo).run(null, { false }, {})
        assertEquals(1, repo.submitted.size)
        assertEquals(1, result.protected)
    }

    @Test fun duplicatePendingAndDraftRowsAlwaysProtectDraft() = runTest {
        val repo = Fake().apply { courseList = listOf(course, course.copy(id = 999, hassubmit = 2, pjjgid = 10)) }
        EvalBatchRunner(repo).run(null, { false }, {})
        assertTrue(repo.submitted.isEmpty())
    }

    @Test fun submittedParentJournalDoesNotHideItsPendingTeachersOnNextPreview() = runTest {
        val parent = course.copy(pjtype = "hjjskc", hassubmit = 1, pjjgid = 10)
        val repo = Fake().apply {
            courseList = listOf(parent)
            teacherList = listOf(course.copy(pjtype = "hjjspj", jobnumber = "T1"))
        }
        repo.journal.add(listOf(task.taskid.toString()) + parent.targetKey())
        val runner = EvalBatchRunner(repo)
        val plan = runner.preview(null)
        assertEquals(1, plan.pendingTeachers)
        runner.run(null, { false }, {}, plan)
        assertEquals("hjjspj", repo.submitted.single().first.pjtype)
    }

    @Test fun failedAtomicClaimProtectsTargetEvenWhenEarlierReadsWerePending() = runTest {
        val repo = Fake().apply { claimAllowed = false }
        val result = EvalBatchRunner(repo).run(null, { false }, {})
        assertTrue(repo.submitted.isEmpty())
        assertTrue(result.result.orEmpty().contains("已被其他流程认领"))
    }

    @Test fun hundredCoursesWithOnePendingNeedOnlyOneRefreshBeforeWrite() = runTest {
        val repo = Fake().apply {
            courseList = (1L..100L).map { course.copy(id = it, coursecode = "C$it", hassubmit = if (it == 100L) 0 else 1) }
        }
        val runner = EvalBatchRunner(repo)
        val plan = runner.preview(null)
        runner.run(null, { false }, {}, plan)
        assertEquals(1, repo.submitted.size)
        assertEquals(2, repo.courseReads) // One preview + one write-time refresh, independent of skipped count.
    }

    @Test fun hundredTeachersWithOnePendingReusePreviewAndRefreshOnlyBeforeWrite() = runTest {
        val repo = Fake().apply {
            courseList = listOf(course.copy(pjtype = "hjjskc", hassubmit = 1, pjjgid = 10))
            teacherList = (1L..100L).map { course.copy(
                pjtype = "hjjspj", jobnumber = "T$it", pjjgid = if (it == 100L) null else it,
                speakstates = if (it == 100L) 1 else 2,
            ) }
        }
        val runner = EvalBatchRunner(repo)
        val plan = runner.preview(null)
        runner.run(null, { false }, {}, plan)
        assertEquals(1, repo.submitted.size)
        assertEquals(2, repo.teacherReads)
        assertEquals(1, repo.courseReads)
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
            advanceUntilIdle()
            repo.onSubmit = {
                assertTrue(vm.uiState.value.blocksBack)
                vm.openTask(task)
                vm.back()
                vm.retry()
                vm.startBatch()
                vm.stopBatch()
            }
            vm.startBatch()
            vm.startBatch()
            runCurrent()
            assertEquals(EvalPage.TASKS, vm.uiState.value.page)
            advanceUntilIdle()
            assertEquals(1, repo.submitted.size)
            assertTrue(!vm.uiState.value.batch.running)
            assertTrue(!vm.uiState.value.loading)
            assertTrue(vm.uiState.value.batch.result.orEmpty().startsWith("已停止"))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun singleSubmissionConsumesBackInsteadOfLettingHostExit() {
        assertTrue(EvalUiState(page = EvalPage.FORM, submitting = true).blocksBack)
        assertTrue(EvalUiState(page = EvalPage.TASKS, batchPreparing = true).blocksBack)
        assertTrue(!EvalUiState(page = EvalPage.FORM).blocksBack)
    }

    @Test fun insufficientDowngradeCandidatesLeaveFormUnchangedAndExplainFailure() {
        val state = EvalFormLogic.autoFill(EvalUiState(task = task.copy(sfqxzdzgf = "是"), questions = listOf(question)))
        assertTrue(state.singleAnswers.isEmpty())
        assertTrue(state.message.orEmpty().contains("不足2道"))
    }
}
