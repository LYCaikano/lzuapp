package li.lyc.lzuapp.data.repo

import li.lyc.lzuapp.data.AuthStore
import li.lyc.lzuapp.data.api.JwqeApi
import li.lyc.lzuapp.data.api.LzuApi
import li.lyc.lzuapp.data.api.model.EvalAnswer
import li.lyc.lzuapp.data.api.model.EvalCourse
import li.lyc.lzuapp.data.api.model.EvalQuestion
import li.lyc.lzuapp.data.api.model.EvalSubmitAnswer
import li.lyc.lzuapp.data.api.model.EvalSubmitItem
import li.lyc.lzuapp.data.api.model.EvalTask
import li.lyc.lzuapp.data.api.model.EvalTeacherRecord
import li.lyc.lzuapp.data.api.model.EvalTeacherQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 学生评教仓库：jwqe accessToken 生命周期管理 + 业务调用。
 * token 无固定 TTL，靠 401/「授权失效」驱动刷新；失效链 = getStByServiceId(29562) + doLogin，
 * getSt 失败（loginToken 失效）→ 静默重新登录一次再试。与 YktRepository 同款自愈模式。
 */
class EvalRepository(
    private val authStore: AuthStore,
    private val lzuApi: LzuApi,
    private val jwqeApi: JwqeApi,
    private val courseRepo: CourseRepository,
    private val safety: EvalSafetyStore,
) : EvalDataSource {

    sealed interface EvalResult<out T> {
        data class Ok<T>(val data: T) : EvalResult<T>
        data class Err(val message: String, val reason: SyncFailReason = SyncFailReason.OTHER) : EvalResult<Nothing>
    }

    /** ST 票据一次性：换 accessToken 必须串行 */
    private val tokenMutex = Mutex()

    /**
     * 确保 accessToken 有效。失效链：缓存为空 → getSt + doLogin；
     * getSt 失败（登录态被挤）→ 静默重新登录 → 再签 ST → doLogin。
     */
    private suspend fun ensureToken(): Result<String> = tokenMutex.withLock {
        val s = authStore.current()
        if (s.jwqeToken.isNotEmpty()) return@withLock Result.success(s.jwqeToken)
        val first = loginLocked()
        if (first.isSuccess || first.exceptionOrNull() is li.lyc.lzuapp.data.api.EvalRateLimitedException) return@withLock first
        // 首次失败：静默重新登录后重试一次
        val relogin = courseRepo.login(s.username, s.password)
        if (relogin !is LoginResult.Success) {
            return@withLock Result.failure(
                if (relogin is LoginResult.Failed && relogin.reason == SyncFailReason.NETWORK)
                    java.io.IOException("无网络")
                else Exception("登录状态失效")
            )
        }
        loginLocked()
    }

    /** getSt(29562) → doLogin → 缓存 token */
    private suspend fun loginLocked(): Result<String> {
        val cur = authStore.current()
        if (!cur.isLoggedIn) return Result.failure(Exception("未登录"))
        val st = lzuApi.getStByServiceId(cur.loginToken, cur.gatewayToken, SERVICE_ID).getOrElse {
            return Result.failure(if (it is java.io.IOException) it else Exception("登录状态失效"))
        }
        if (st.isEmpty()) return Result.failure(Exception("登录状态失效"))
        return try {
            val token = jwqeApi.doLogin(st)
            authStore.saveJwqeToken(token)
            Result.success(token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 操作前静默校验登录态 + 失效自动重试：
     * - 鉴权失效（401/授权失效/被挤）：清缓存重新 doLogin 重试 1 次
     * - 网络错误（IOException）：读操作最多共试 3 次（间隔 500ms），仍失败才报"无网络"；
     *   写操作（[retryOnNetwork]=false，如提交评教）不重试，防重复提交
     */
    private suspend fun <T> withEvalRetry(
        retryOnNetwork: Boolean = true,
        block: suspend (token: String) -> T,
    ): EvalResult<T> {
        var authRetried = false
        var networkAttempts = 0
        val maxNetworkAttempts = if (retryOnNetwork) 3 else 1
        while (true) {
            var usedToken: String? = null
            try {
                val token = ensureToken().getOrThrow()
                usedToken = token
                return EvalResult.Ok(block(token))
            } catch (e: CancellationException) {
                // 协程取消（ViewModel 销毁等）必须向上传播，不能转成错误结果继续重试
                throw e
            } catch (e: JwqeApi.JwqeException) {
                if (e.isAuth && !authRetried) {
                    authRetried = true
                    tokenMutex.withLock {
                        // 只清自己这次用的 token：并发场景下别把别协程刚换的新 token 顶掉
                        if (usedToken == null || authStore.current().jwqeToken == usedToken) {
                            authStore.clearJwqeToken()
                        }
                    }
                    continue
                }
                return EvalResult.Err(e.message ?: "操作失败")
            } catch (e: java.io.IOException) {
                networkAttempts++
                if (networkAttempts < maxNetworkAttempts) {
                    kotlinx.coroutines.delay(500)
                    continue
                }
                return EvalResult.Err("无网络", SyncFailReason.NETWORK)
            } catch (e: Exception) {
                return EvalResult.Err(e.message ?: "操作失败")
            }
        }
    }

    /** 评教任务列表（pjfs="排名打分" 的任务暂不支持，字段保留供 UI 提示） */
    override suspend fun tasks(): EvalResult<List<EvalTask>> = withEvalRetry { jwqeApi.getTasks(it) }

    /** 按任务发布方式选择普通课程或合讲课程查询 */
    override suspend fun courses(task: EvalTask): EvalResult<List<EvalCourse>> = withEvalRetry {
        val isJointTask = task.objecttask == "当前" && task.taskfbfs == "根据合讲教师数评价"
        val courses = if (isJointTask) {
            jwqeApi.getStudentEvaluation(it, task.taskid.toString()).map { course ->
                course.copy(
                    teachername = course.joblist.orEmpty().joinToString("、"),
                    pjtype = "hjjskc",
                )
            }
        } else {
            jwqeApi.getCourses(it, task.taskid.toString())
        }
        courses.map(::normalizeCourse)
    }

    /** 合讲教师查询，保留父课程信息及教师自己的评价状态 */
    override suspend fun teachers(task: EvalTask, course: EvalCourse): EvalResult<List<EvalCourse>> = withEvalRetry {
        val records = linkedMapOf<Pair<String?, String>, EvalTeacherRecord>()
        var page = 1
        do {
            check(page <= 20) { "教师分页超过20页，请手动检查" }
            val query = EvalTeacherQuery(task.taskid, course.yearterm, course.coursecode, page++, 50, course.pjcoursetype)
            val batch = jwqeApi.getJobnumberEvaluation(it, query)
            val added = mergeTeacherPage(records, batch)
            check(batch.isEmpty() || added > 0 || batch.size < 50) { "教师列表分页未更新，请稍后重试" }
        } while (batch.size == 50)
        records.values.map { teacher ->
            normalizeCourse(
                course.copy(
                    teachername = teacher.teachername,
                    jobnumber = teacher.jobnumber,
                    pjjgid = teacher.pjjgid,
                    hassubmit = 0,
                    pjtype = "hjjspj",
                    speakstates = teacher.speakstates?.takeIf { it != 0 } ?: 1,
                    joblist = null,
                )
            )
        }
    }

    /** 指标体系题目（indexid 传任务原始逗号串即可） */
    override suspend fun questions(indexid: String, pjcoursetype: String): EvalResult<List<EvalQuestion>> =
        withEvalRetry { jwqeApi.getQuestions(it, indexid, pjcoursetype) }

    /** 已提交答案（回填已评/暂存） */
    override suspend fun savedAnswers(pjjgid: Long): EvalResult<List<EvalAnswer>> =
        withEvalRetry { jwqeApi.getAnswers(it, pjjgid) }

    override suspend fun wasBatchAttempted(task: EvalTask, course: EvalCourse): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            safety.attempted(authStore.current().username, task.taskid, course)
        }

    override suspend fun markBatchAttempted(task: EvalTask, course: EvalCourse): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            safety.markAttempted(authStore.current().username, task.taskid, course)
        }

    /** 单个表单只调用其对应保存链；网络失败不重试，防重复提交 */
    override suspend fun submit(course: EvalCourse, item: EvalSubmitItem): EvalResult<Unit> =
        withEvalRetry(retryOnNetwork = false) { jwqeApi.submit(it, listOf(item), course.pjtype) }

    companion object {
        /** 服务目录中「学生评教」的 service_info_id */
        private const val SERVICE_ID = "29562"

        private fun normalizeCourse(course: EvalCourse): EvalCourse = course.copy(
            classno = course.classno?.takeIf { it.isNotEmpty() },
            jobnumber = course.jobnumber?.takeIf { it.isNotEmpty() },
        )

        /**
         * tevaluateResultid 仅在表单 speakstates==1 或 op==dcpj 时恢复非零 pjjgid，
         * 零按官方 pjjgid||null 归一为 null，其他情况为 0；总分只累计计分题。
         * 对齐官方 H5：总分零为数值 0，非零为 toFixed(2) 字符串。
         */
        fun buildSubmitItem(
            course: EvalCourse,
            task: EvalTask,
            questions: List<EvalQuestion>,
            answers: List<EvalSubmitAnswer>,
            isDcpj: Boolean,
        ): EvalSubmitItem {
            val scoredIds = questions.asSequence()
                .filter { it.isscored == "是" }
                .map { it.indexid }
                .toSet()
            val total = answers.asSequence()
                .filter { it.indexid in scoredIds }
                .fold(BigDecimal.ZERO) { sum, answer -> sum.add(BigDecimal.valueOf(answer.index_score)) }
            val totalscore = if (total.compareTo(BigDecimal.ZERO) == 0) {
                JsonPrimitive(0)
            } else {
                JsonPrimitive(total.setScale(2, RoundingMode.HALF_UP).toPlainString())
            }
            val tevaluateResultid: Long? = if (course.speakstates == 1 || isDcpj) {
                course.pjjgid?.takeIf { it != 0L }
            } else {
                0L
            }
            return EvalSubmitItem(
                totalscore = totalscore,
                classno = course.classno,
                coursecode = course.coursecode,
                coursename = course.coursename,
                jobnumber = course.jobnumber,
                tevaluateResultid = tevaluateResultid,
                taskid = task.taskid,
                studentid = course.studentid,
                studentname = course.studentname,
                teachername = course.teachername,
                yearterm = course.yearterm,
                courseorgname = course.courseorgname,
                courseorgcode = course.courseorgcode,
                pjcoursetype = course.pjcoursetype,
                commit_time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()),
                evaluateResult = answers,
                speakstates = when (course.pjtype) {
                    "hjjskc" -> 1
                    "hjjspj" -> 2
                    else -> null
                },
            )
        }
    }
}
