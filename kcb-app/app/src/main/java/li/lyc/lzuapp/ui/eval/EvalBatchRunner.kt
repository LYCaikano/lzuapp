package li.lyc.lzuapp.ui.eval

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import li.lyc.lzuapp.data.api.model.EvalCourse
import li.lyc.lzuapp.data.api.model.EvalTask
import li.lyc.lzuapp.data.repo.EvalDataSource
import li.lyc.lzuapp.data.repo.EvalRepository
import li.lyc.lzuapp.data.repo.EvalRepository.EvalResult
import li.lyc.lzuapp.data.repo.hasDraft
import li.lyc.lzuapp.data.repo.targetKey
import li.lyc.lzuapp.data.repo.uniqueTargets

/** Stop requests take effect between operations, never by aborting an in-flight submission. */
data class EvalBatchState(
    val running: Boolean = false,
    val stopping: Boolean = false,
    val submitted: Int = 0,
    val skipped: Int = 0,
    val protected: Int = 0,
    val current: String = "",
    val result: String? = null,
)

data class EvalBatchPlan(
    val coursesByTask: Map<EvalTask, List<EvalCourse>>,
    val pendingCourses: Int,
    val pendingTeachers: Int,
    val protected: Int,
    val unknownTeacherCourses: Int,
    val teachersByParent: Map<List<String>, List<EvalCourse>> = emptyMap(),
) {
    companion object { const val MAX_SUBMISSIONS = 20 }
}

internal class EvalBatchRunner(private val repo: EvalDataSource) {
    /** Read-only, bounded discovery; no form, draft or submission is changed. */
    suspend fun preview(taskId: Long?): EvalBatchPlan {
        val tasks = repo.tasks().valueOrThrow().filter {
            it.currentStatus == "进行中" && it.pjfs == "打分" && (taskId == null || it.taskid == taskId)
        }.distinctBy { it.taskid }
        check(tasks.size <= 10) { "进行中的任务超过10个，请进入单个任务预览" }
        var courses = 0
        var teachers = 0
        var protected = 0
        var unknown = 0
        val plan = linkedMapOf<EvalTask, List<EvalCourse>>()
        val teacherSnapshots = linkedMapOf<List<String>, List<EvalCourse>>()
        for (task in tasks) {
            val rows = repo.courses(task).valueOrThrow().uniqueTargets()
            check(plan.values.sumOf { it.size } + rows.size <= 100) { "预览超过100门课程，请进入单个任务操作" }
            plan[task] = rows
            for (course in rows) {
                val guarded = !course.isEvaluated && (course.hasDraft || repo.wasBatchAttempted(task, course))
                if (!course.isEvaluated) {
                    if (guarded) protected++ else courses++
                }
                if (course.pjtype == "hjjskc" && !guarded) {
                    if (course.isEvaluated) {
                        val rows = repo.teachers(task, course).valueOrThrow().uniqueTargets()
                        teacherSnapshots[listOf(task.taskid.toString()) + course.targetKey()] = rows
                        for (teacher in rows) {
                            if (!teacher.isEvaluated) {
                                if (teacher.hasDraft || repo.wasBatchAttempted(task, teacher)) protected++ else teachers++
                            }
                        }
                    } else {
                        // Teacher listing may require a submitted course. Label these counts as estimates.
                        val remaining = course.remainingTeachers ?: course.hjjss
                        if (remaining == null) unknown++ else teachers += remaining.coerceAtLeast(0)
                    }
                }
            }
        }
        return EvalBatchPlan(plan, courses, teachers, protected, unknown, teacherSnapshots)
    }

    suspend fun run(
        taskId: Long?,
        shouldStop: () -> Boolean,
        onProgress: (EvalBatchState) -> Unit,
        plan: EvalBatchPlan? = null,
    ): EvalBatchState {
        var progress = EvalBatchState(running = true)
        val submittedTargets = mutableSetOf<List<String>>()
        val courseCache = mutableMapOf<Long, List<EvalCourse>>()
        val teacherCache = mutableMapOf<List<String>, List<EvalCourse>>()
        val questionCache = mutableMapOf<Pair<String, String>, List<li.lyc.lzuapp.data.api.model.EvalQuestion>>()
        fun parentKey(task: EvalTask, parent: EvalCourse) = listOf(task.taskid.toString()) + parent.targetKey()
        fun publish() = onProgress(progress)
        fun checkpoint() { if (shouldStop()) throw BatchStopped() }
        suspend fun guarded(task: EvalTask, course: EvalCourse): Boolean {
            if (course.isEvaluated) {
                progress = progress.copy(skipped = progress.skipped + 1)
            } else if (course.hasDraft || repo.wasBatchAttempted(task, course)) {
                progress = progress.copy(protected = progress.protected + 1)
            } else return false
            publish()
            return true
        }
        suspend fun fresh(task: EvalTask, target: EvalCourse, parent: EvalCourse?): EvalCourse {
            checkpoint()
            val rows = (if (parent == null) repo.courses(task) else repo.teachers(task, parent)).valueOrThrow().uniqueTargets()
            if (parent == null) courseCache[task.taskid] = rows else teacherCache[parentKey(task, parent)] = rows
            return rows.firstOrNull { it.targetKey() == target.targetKey() }
                ?: error("当前评价对象已变化，请重新预览")
        }
        suspend fun evaluate(task: EvalTask, target: EvalCourse, parent: EvalCourse? = null) {
            checkpoint()
            progress = progress.copy(current = "${task.taskname} · ${target.coursename} · ${target.teachername}")
            publish()
            val cached = if (parent == null) courseCache[task.taskid] else teacherCache[parentKey(task, parent)]
            val course = if (target.isEvaluated || target.hasDraft) target else
                cached?.firstOrNull { it.targetKey() == target.targetKey() } ?: target
            if (guarded(task, course)) return
            if (progress.submitted >= EvalBatchPlan.MAX_SUBMISSIONS) throw BatchLimit()
            val questionKey = task.indexid to course.pjcoursetype
            val questions = questionCache[questionKey] ?: repo.questions(task.indexid, course.pjcoursetype)
                .valueOrThrow().sortedBy { it.ordor }.also { questionCache[questionKey] = it }
            check(questions.isNotEmpty()) { "没有评价题目，请手动检查" }
            check(questions.all { it.type == "单选题" || it.type == "问答题" }) { "存在不支持的题型，请手动评价" }
            val form = EvalFormLogic.autoFill(EvalUiState(task = task, course = course, questions = questions))
            check(form.message == null) { requireNotNull(form.message) }
            val validated = EvalFormLogic.validate(form)
            check(validated.submitError == null) { requireNotNull(validated.submitError) }
            // Recheck after loading the form. Drafts or new submissions always win over our snapshot.
            val latest = fresh(task, course, parent)
            if (guarded(task, latest)) return
            checkpoint()
            repo.submit(latest, EvalRepository.buildSubmitItem(
                latest, task, questions, EvalFormLogic.answers(form), task.sfkydcpj == "是",
            ), batch = true).valueOrThrow()
            submittedTargets.add(listOf(task.taskid.toString()) + latest.targetKey())
            progress = progress.copy(submitted = progress.submitted + 1)
            publish()
        }
        publish()
        return try {
            val confirmed = plan ?: preview(taskId)
            courseCache.putAll(confirmed.coursesByTask.mapKeys { it.key.taskid })
            teacherCache.putAll(confirmed.teachersByParent)
            checkpoint()
            val activeTasks = repo.tasks().valueOrThrow().filter {
                it.currentStatus == "进行中" && it.pjfs == "打分"
            }.associateBy { it.taskid }
            for ((oldTask, courses) in confirmed.coursesByTask) {
                val task = activeTasks[oldTask.taskid] ?: error("任务状态已变化，请重新预览")
                for (course in courses.uniqueTargets()) {
                    evaluate(task, course)
                    if (course.pjtype == "hjjskc") {
                        var parent = courseCache[task.taskid]?.firstOrNull { it.targetKey() == course.targetKey() } ?: course
                        val justSubmitted = parentKey(task, course) in submittedTargets
                        if (!parent.isEvaluated && !justSubmitted) continue
                        if (justSubmitted && !parent.isEvaluated) {
                            parent = fresh(task, course, null)
                        }
                        if (!parent.isEvaluated && justSubmitted) {
                            repeat(2) {
                                if (!parent.isEvaluated) {
                                    checkpoint()
                                    delay(1000)
                                    parent = fresh(task, course, null)
                                }
                            }
                        }
                        // A protected draft/unknown result must not be auto-submitted to unlock teachers.
                        if (!parent.isEvaluated) {
                            check((listOf(task.taskid.toString()) + parent.targetKey()) !in submittedTargets) { "课程提交后状态尚未更新，请稍后刷新再继续" }
                            if (parent.hasDraft || repo.wasBatchAttempted(task, parent)) continue
                            error("课程提交后状态尚未更新，请稍后刷新再继续")
                        }
                        val teachers = teacherCache[parentKey(task, parent)] ?: repo.teachers(task, parent)
                            .valueOrThrow().uniqueTargets().also { teacherCache[parentKey(task, parent)] = it }
                        check(teachers.isNotEmpty()) { "教师列表为空，请手动检查" }
                        for (teacher in teachers) evaluate(task, teacher, parent)
                    }
                }
            }
            checkpoint()
            progress.copy(running = false, result = "本批次完成；受保护对象请手动核对")
        } catch (_: BatchLimit) {
            progress.copy(running = false, result = "已达到单次20项上限；请重新预览后继续")
        } catch (_: BatchStopped) {
            progress.copy(running = false, result = "已停止，已提交的评价已保留")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            progress.copy(running = false, result = "已暂停：${e.message}。当前提交结果可能待确认，请手动核对")
        }.also(onProgress)
    }

    private class BatchStopped : RuntimeException()
    private class BatchLimit : RuntimeException()
    private fun <T> EvalResult<T>.valueOrThrow(): T = when (this) {
        is EvalResult.Ok -> data
        is EvalResult.Err -> error(message)
    }
}
