package li.lyc.lzuapp.ui.eval

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import li.lyc.lzuapp.data.api.model.EvalCourse
import li.lyc.lzuapp.data.api.model.EvalTask
import li.lyc.lzuapp.data.repo.EvalDataSource
import li.lyc.lzuapp.data.repo.EvalRepository
import li.lyc.lzuapp.data.repo.EvalRepository.EvalResult

/** Stop requests take effect between operations, never by aborting an in-flight submission. */
data class EvalBatchState(
    val running: Boolean = false,
    val stopping: Boolean = false,
    val submitted: Int = 0,
    val skipped: Int = 0,
    val current: String = "",
    val result: String? = null,
)

internal class EvalBatchRunner(private val repo: EvalDataSource) {
    suspend fun run(
        taskId: Long?,
        shouldStop: () -> Boolean,
        onProgress: (EvalBatchState) -> Unit,
    ): EvalBatchState {
        var progress = EvalBatchState(running = true)
        fun publish() = onProgress(progress)
        fun checkpoint() {
            if (shouldStop()) throw BatchStopped()
        }
        suspend fun evaluate(task: EvalTask, course: EvalCourse) {
            checkpoint()
            progress = progress.copy(current = "${task.taskname} · ${course.coursename} · ${course.teachername}")
            publish()
            if (course.isEvaluated) {
                progress = progress.copy(skipped = progress.skipped + 1)
                publish()
                return
            }
            val questions = repo.questions(task.indexid, course.pjcoursetype).valueOrThrow()
                .sortedBy { it.ordor }
            check(questions.isNotEmpty()) { "没有评价题目，请手动检查" }
            check(questions.all { it.type == "单选题" || it.type == "问答题" }) { "存在不支持的题型，请手动评价" }
            val saved = course.pjjgid?.takeIf { it != 0L }?.let {
                repo.savedAnswers(it).valueOrThrow()
            }.orEmpty()
            val form = EvalFormLogic.autoFill(EvalUiState(
                task = task,
                course = course,
                questions = questions,
                singleAnswers = saved.associate { it.indexid to it.option_id },
                textAnswers = saved.associate { it.indexid to it.index_title },
                answerIds = saved.associate { it.indexid to it.id },
            ))
            check(form.message == null) { requireNotNull(form.message) }
            val validated = EvalFormLogic.validate(form)
            check(validated.submitError == null) { requireNotNull(validated.submitError) }
            checkpoint()
            repo.submit(course, EvalRepository.buildSubmitItem(
                course, task, questions, EvalFormLogic.answers(form), task.sfkydcpj == "是",
            )).valueOrThrow()
            progress = progress.copy(submitted = progress.submitted + 1)
            publish()
            // Pace requests; do not retry writes whose server-side outcome may be unknown.
            delay(500)
        }
        publish()
        return try {
            val tasks = repo.tasks().valueOrThrow().filter {
                it.currentStatus == "进行中" && it.pjfs == "打分" && (taskId == null || it.taskid == taskId)
            }
            for (task in tasks) {
                checkpoint()
                val courses = repo.courses(task).valueOrThrow()
                for (course in courses) {
                    evaluate(task, course)
                    if (course.pjtype == "hjjskc") {
                        checkpoint()
                        // A new joint-course evaluation must become visible before fetching its teachers.
                        var parent = course
                        if (!course.isEvaluated) {
                            repeat(3) { attempt ->
                                if (!parent.isEvaluated) {
                                    checkpoint()
                                    if (attempt > 0) delay(1000)
                                    parent = repo.courses(task).valueOrThrow().firstOrNull {
                                        it.coursecode == course.coursecode && it.yearterm == course.yearterm &&
                                            it.pjcoursetype == course.pjcoursetype && it.id == course.id
                                    } ?: error("课程刷新后未找到，请手动检查")
                                }
                            }
                            check(parent.isEvaluated) { "课程提交后状态尚未更新，请稍后刷新再继续" }
                        }
                        val teachers = repo.teachers(task, parent).valueOrThrow()
                        check(teachers.isNotEmpty()) { "教师列表为空，请手动检查" }
                        for (teacher in teachers) evaluate(task, teacher)
                    }
                }
            }
            checkpoint()
            progress.copy(running = false, result = "批量评教完成")
        } catch (_: BatchStopped) {
            progress.copy(running = false, result = "已停止，已提交的评价已保留")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            progress.copy(running = false, result = "已暂停：${e.message}。请刷新并检查当前对象后再继续")
        }.also(onProgress)
    }

    private class BatchStopped : RuntimeException()

    private fun <T> EvalResult<T>.valueOrThrow(): T = when (this) {
        is EvalResult.Ok -> data
        is EvalResult.Err -> error(message)
    }
}
