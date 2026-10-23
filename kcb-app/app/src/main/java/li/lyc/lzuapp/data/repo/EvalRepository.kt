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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
) {

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
        if (first.isSuccess) return@withLock first
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
            Result.failure(if (e is java.io.IOException) e else Exception(e.message ?: "评教登录失败"))
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
    suspend fun tasks(): EvalResult<List<EvalTask>> = withEvalRetry { jwqeApi.getTasks(it) }

    /** 任务下的评教课程 */
    suspend fun courses(taskid: Long): EvalResult<List<EvalCourse>> =
        withEvalRetry { jwqeApi.getCourses(it, taskid.toString()) }

    /** 指标体系题目（indexid 传任务原始逗号串即可） */
    suspend fun questions(indexid: String, pjcoursetype: String): EvalResult<List<EvalQuestion>> =
        withEvalRetry { jwqeApi.getQuestions(it, indexid, pjcoursetype) }

    /** 已提交答案（回填已评/暂存） */
    suspend fun savedAnswers(pjjgid: Long): EvalResult<List<EvalAnswer>> =
        withEvalRetry { jwqeApi.getAnswers(it, pjjgid) }

    /**
     * 提交评教（每元素一门课，可一次多门）。写操作网络错误不重试，防重复提交。
     * 条目用 [buildSubmitItem] 组装。
     */
    suspend fun submit(items: List<EvalSubmitItem>): EvalResult<Unit> =
        withEvalRetry(retryOnNetwork = false) { jwqeApi.submit(it, items) }

    companion object {
        /** 服务目录中「学生评教」的 service_info_id */
        private const val SERVICE_ID = "29562"

        /**
         * 组装一门课的提交条目：tevaluateResultid 新提交=0/修改=原 pjjgid；
         * totalscore 为各题 index_score 合计（对齐官方 H5 的 toFixed(2)，0 时传 "0"）。
         */
        fun buildSubmitItem(
            course: EvalCourse,
            task: EvalTask,
            answers: List<EvalSubmitAnswer>,
        ): EvalSubmitItem {
            val total = answers.sumOf { it.index_score }
            return EvalSubmitItem(
                totalscore = if (total == 0.0) "0"
                else BigDecimal(total).setScale(2, RoundingMode.HALF_UP).toPlainString(),
                classno = course.classno,
                coursecode = course.coursecode,
                coursename = course.coursename,
                jobnumber = course.jobnumber,
                tevaluateResultid = course.pjjgid ?: 0,
                taskid = task.taskid.toString(),
                studentid = course.studentid,
                studentname = course.studentname,
                teachername = course.teachername,
                yearterm = course.yearterm.toString(),
                courseorgname = course.courseorgname,
                courseorgcode = course.courseorgcode,
                pjcoursetype = course.pjcoursetype,
                commit_time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()),
                evaluateResult = answers,
            )
        }
    }
}
