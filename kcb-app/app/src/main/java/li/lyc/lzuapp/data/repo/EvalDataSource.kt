package li.lyc.lzuapp.data.repo

import li.lyc.lzuapp.data.api.model.EvalAnswer
import li.lyc.lzuapp.data.api.model.EvalCourse
import li.lyc.lzuapp.data.api.model.EvalQuestion
import li.lyc.lzuapp.data.api.model.EvalSubmitItem
import li.lyc.lzuapp.data.api.model.EvalTask
import li.lyc.lzuapp.data.repo.EvalRepository.EvalResult

/** Evaluation operations shared by the interactive form and batch runner. */
interface EvalDataSource {
    suspend fun tasks(): EvalResult<List<EvalTask>>
    suspend fun courses(task: EvalTask): EvalResult<List<EvalCourse>>
    suspend fun teachers(task: EvalTask, course: EvalCourse): EvalResult<List<EvalCourse>>
    suspend fun questions(indexid: String, pjcoursetype: String): EvalResult<List<EvalQuestion>>
    suspend fun savedAnswers(pjjgid: Long): EvalResult<List<EvalAnswer>>
    suspend fun wasBatchAttempted(task: EvalTask, course: EvalCourse): Boolean
    suspend fun submit(course: EvalCourse, item: EvalSubmitItem, batch: Boolean = false): EvalResult<Unit>
}
