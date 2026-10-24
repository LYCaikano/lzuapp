package li.lyc.lzuapp.data.repo

import android.content.Context
import android.util.Log
import li.lyc.lzuapp.data.AuthStore
import li.lyc.lzuapp.data.api.ApiException
import li.lyc.lzuapp.data.api.LzuApi
import li.lyc.lzuapp.data.api.model.CourseData
import li.lyc.lzuapp.data.db.AppDatabase
import li.lyc.lzuapp.data.db.CourseEntity
import li.lyc.lzuapp.data.db.SemesterEntity
import li.lyc.lzuapp.ui.schedule.sectionRange
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.util.UUID

sealed interface LoginResult {
    data class Success(val userName: String, val college: String) : LoginResult
    data class NeedCaptcha(val sessionId: String) : LoginResult
    data class Failed(val message: String, val reason: SyncFailReason = SyncFailReason.OTHER) : LoginResult
}

sealed interface SyncResult {
    data object Success : SyncResult
    data class Failed(val message: String, val reason: SyncFailReason = SyncFailReason.OTHER) : SyncResult
}

/** 同步失败原因分类（用于 UI 状态提示） */
enum class SyncFailReason { LOGIN, NETWORK, OTHER }

/** 清理服务端错误信息的 code 前缀（如 "0: 失败" -> "失败"），避免 UI 显示奇怪字符 */
private fun cleanMsg(msg: String?, fallback: String = "网络错误"): String =
    msg?.replace(Regex("^\\d+:\\s*"), "")?.ifBlank { null } ?: fallback

class CourseRepository(
    private val context: Context,
    private val api: LzuApi,
    private val authStore: AuthStore,
    private val db: AppDatabase,
) {

    // ---------- 登录 ----------

    /** 第一步：账号密码登录 */
    suspend fun login(username: String, password: String): LoginResult {
        val deviceId = ensureDeviceId()
        authStore.saveCredentials(username, password, deviceId)
        return api.login(username, password, deviceId).fold(
            onSuccess = { data ->
                if (data.loginToken != null && data.gatewayToken != null) {
                    authStore.saveLoginResult(data.loginToken, data.gatewayToken)
                    val info = fetchAndSaveUserInfo(data.loginToken, data.gatewayToken)
                    LoginResult.Success(
                        userName = info.first,
                        college = info.second,
                    )
                } else {
                    data.sessionId?.let { authStore.saveSessionId(it) }
                    LoginResult.NeedCaptcha(data.sessionId.orEmpty())
                }
            },
            onFailure = { e ->
                Log.e("Kcb", "login failed", e)
                // 登录错误详情落盘仅 debug 包（含堆栈/服务端报错，release 完全不写）
                if (li.lyc.lzuapp.BuildConfig.DEBUG) runCatching {
                    // 超过 64KB 截断重写，防止无限增长
                    val f = context.getFileStreamPath("login_err.log")
                    val mode = if (f != null && f.length() > 64 * 1024) Context.MODE_PRIVATE
                    else Context.MODE_APPEND
                    context.openFileOutput("login_err.log", mode).use {
                        it.write(("${System.currentTimeMillis()} ${e::class.java.name}: ${e.message}\n").toByteArray())
                        e.stackTraceToString().forEach { line ->
                            it.write((line + "\n").toByteArray())
                        }
                    }
                }
                LoginResult.Failed(
                    cleanMsg(e.message),
                    if (e is java.io.IOException) SyncFailReason.NETWORK else SyncFailReason.OTHER,
                )
            },
        )
    }

    /** 第二步：发送验证码 */
    suspend fun sendCaptcha(): Boolean {
        val s = authStore.current()
        return api.sendPhoneCaptcha(s.deviceId, s.sessionId).fold(
            onSuccess = { it.code == 1 },
            onFailure = { false },
        )
    }

    /** 第三步：提交验证码完成登录 */
    suspend fun verifyCaptcha(captcha: String): LoginResult {
        val s = authStore.current()
        return api.verifyPhoneCaptcha(s.deviceId, s.sessionId, captcha).fold(
            onSuccess = { data ->
                if (data.loginToken != null && data.gatewayToken != null) {
                    authStore.saveLoginResult(data.loginToken, data.gatewayToken)
                    val info = fetchAndSaveUserInfo(data.loginToken, data.gatewayToken)
                    LoginResult.Success(
                        userName = info.first,
                        college = info.second,
                    )
                } else LoginResult.Failed("验证码验证失败")
            },
            onFailure = { LoginResult.Failed(
                cleanMsg(it.message),
                if (it is java.io.IOException) SyncFailReason.NETWORK else SyncFailReason.OTHER,
            ) },
        )
    }

    /** 拉取用户信息并写入 DataStore，返回 (姓名, 学院)。最多等 8s，失败不阻塞登录。 */
    private suspend fun fetchAndSaveUserInfo(loginToken: String, gatewayToken: String): Pair<String, String> {
        val u = runCatching {
            withTimeout(8_000) { api.userInfo(loginToken, gatewayToken) }
        }.getOrNull()?.getOrNull() ?: return "" to ""
        fun str(key: String): String =
            (u[key] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        val name = str("xm")
        val college = str("dwmc")
        val xykh = str("xykh")
        authStore.saveUserInfo(userName = name, college = college, xykh = xykh)
        return name to college
    }

    /**
     * 用当前登录态补拉用户信息（自愈：登录时被服务端风控挡住后，
     * 下次打开 App 静默刷新时补上姓名/学院）。
     */
    suspend fun refreshUserInfo() {
        val s = authStore.current()
        if (!s.isLoggedIn || s.loginToken.isEmpty() || s.gatewayToken.isEmpty()) return
        runCatching { fetchAndSaveUserInfo(s.loginToken, s.gatewayToken) }
    }

    private suspend fun ensureDeviceId(): String {
        val s = authStore.current()
        if (s.deviceId.isNotEmpty()) return s.deviceId
        val id = UUID.randomUUID().toString()
        authStore.saveCredentials(s.username, s.password, id)
        return id
    }

    // ---------- 课表 ----------

    /** 课程流（本地缓存） */
    fun observeCourses(): Flow<List<CourseEntity>> = db.courseDao().observeAll()

    fun observeSemester(): Flow<SemesterEntity?> = db.semesterDao().observe()

    // ---------- 隐藏系统课程（纯本地，不参与同步） ----------

    fun observeHidden(): Flow<List<li.lyc.lzuapp.data.db.HiddenCourseEntity>> =
        db.hiddenCourseDao().observeAll()

    /** 隐藏课程。weeks 含 0 = 全部周（清掉旧条目只留 0）；否则按周逐条写入 */
    suspend fun hideCourse(kch: String, weeks: Set<Int>) {
        if (weeks.isEmpty()) return
        if (0 in weeks) {
            db.hiddenCourseDao().deleteByKch(kch)
            db.hiddenCourseDao().insertAll(listOf(li.lyc.lzuapp.data.db.HiddenCourseEntity(kch, 0)))
        } else {
            db.hiddenCourseDao().insertAll(
                weeks.sorted().map { li.lyc.lzuapp.data.db.HiddenCourseEntity(kch, it) },
            )
        }
    }

    /** 取消隐藏指定周（0 = 取消"全部周"那条） */
    suspend fun unhideCourse(kch: String, weeks: Set<Int>) {
        weeks.forEach { db.hiddenCourseDao().delete(kch, it) }
    }

    /** 取消该课全部隐藏 */
    suspend fun unhideCourseAll(kch: String) = db.hiddenCourseDao().deleteByKch(kch)

    /** 当前学期键（学年, 学期序号），未知时为 null 分量 */
    suspend fun currentSemesterKey(): Pair<String?, Int?> =
        db.semesterDao().get().let { it?.xn to it?.xqm?.toIntOrNull() }

    /** 用本地校历补缺学期信息的自定义课（离线启动时也能正确归入当前学期） */
    suspend fun backfillMissingSemester() {
        val (xn, xqm) = currentSemesterKey()
        if (!xn.isNullOrEmpty() && xqm != null) db.courseDao().backfillCustomSemester(xn, xqm)
    }

    private val refreshMutex = Mutex()

    /** ST 票据仅可兑换一次：ensureAccessToken 必须串行，否则并发兑换会失败 */
    private val tokenMutex = Mutex()

    /**
     * 确保 access_token 有效，失效时自动重新认证。串行执行，等待中的调用在锁内复查 token 有效性避免重复兑换。
     * 优先轻量续约（现有 loginToken 直接换 ST）；loginToken 失效才完整重登录——
     * 每次 loginV2 都会累积服务端风控概率（20001 验证码），能少登就少登。
     */
    private suspend fun ensureAccessToken(): Result<String> = tokenMutex.withLock {
        val s = authStore.current()
        if (s.accessValid) return@withLock Result.success(s.accessToken)
        refreshAccessTokenLocked()
    }

    /**
     * 换 access_token（须在 tokenMutex 内调用）：
     * 1) 现有 loginToken/gatewayToken 直接 getSt + 兑换（轻量）；
     * 2) loginToken 失效（非网络错误）才走 loginV2 完整重登录；
     * 网络错误直接失败（重登录同样打不通，白耗一次风控额度）。
     */
    private suspend fun refreshAccessTokenLocked(): Result<String> {
        val s = authStore.current()
        if (s.loginToken.isNotEmpty() && s.gatewayToken.isNotEmpty()) {
            // 轻量续约最多试 3 次：明确拿到 token 或确定 loginToken 已死（非网络错误）才停
            var lastErr: Throwable? = null
            var authDead = false
            for (attempt in 0..2) {
                val light = runCatching {
                    val st = api.getSt(s.loginToken, s.gatewayToken).getOrThrow()
                    api.accessTokenBySt(st).getOrThrow().accessToken.orEmpty()
                }
                light.onSuccess {
                    if (it.isNotEmpty()) {
                        authStore.saveAccessToken(it)
                        return Result.success(it)
                    }
                }
                val e = light.exceptionOrNull() ?: continue
                lastErr = e
                if (e !is java.io.IOException) {
                    authDead = true // 服务端明确拒绝（如 10004），重试无意义
                    break
                }
                if (attempt < 2) kotlinx.coroutines.delay(800) // 网络抖动稍候重试
            }
            if (!authDead && lastErr != null) return Result.failure(lastErr!!)
        }
        val relogin = login(s.username, s.password)
        if (relogin !is LoginResult.Success) {
            return Result.failure(
                if (relogin is LoginResult.Failed && relogin.reason == SyncFailReason.NETWORK)
                    java.io.IOException("无网络")
                else Exception("登录态失效")
            )
        }
        val cur = authStore.current()
        val st = api.getSt(cur.loginToken, cur.gatewayToken).getOrElse { return Result.failure(it) }
        val at = api.accessTokenBySt(st).getOrElse { return Result.failure(it) }
        authStore.saveAccessToken(at.accessToken.orEmpty())
        return Result.success(at.accessToken.orEmpty())
    }

    /**
     * 供成绩/邮箱等其它模块复用：确保有效 access_token（内部串行，轻量续约优先）。
     */
    suspend fun validAccessToken(): Result<String> = ensureAccessToken()

    /** access_token 被服务端拒绝（10004 等）时强制续约（跳过有效期缓存检查） */
    suspend fun forceAccessToken(): Result<String> = tokenMutex.withLock { refreshAccessTokenLocked() }

    /**
     * 静默更新：有有效 access_token 时直接同步；否则重新走登录流程。
     * 并发去重：已有刷新在进行时直接返回（ST 票据仅可兑换一次，避免双兑换失败）。
     * 失败不抛错（保留缓存），返回结果供 UI 提示。
     */
    suspend fun silentRefresh(): SyncResult {
        if (!refreshMutex.tryLock()) return SyncResult.Success
        try {
            return doRefresh()
        } finally {
            refreshMutex.unlock()
        }
    }

    /** 主动同步（带 UI 反馈，排队等待进行中的刷新） */
    suspend fun forceRefresh(): SyncResult = refreshMutex.withLock { doRefresh() }

    /**
     * 同步入口。登录态类失败（被挤掉/风控瞬态）时，间隔 500ms 后台静默重试一整轮，
     * 仍失败才把「登录状态失效」报给 UI（避免瞬态抖动误报）。
     */
    private suspend fun doRefresh(): SyncResult {
        val r = doRefreshOnce()
        if (r is SyncResult.Failed && r.reason == SyncFailReason.LOGIN) {
            kotlinx.coroutines.delay(500)
            val retry = doRefreshOnce()
            if (retry is SyncResult.Success) return retry
        }
        return r
    }

    private suspend fun doRefreshOnce(): SyncResult {
        val s = authStore.current()
        if (!s.isLoggedIn) return SyncResult.Failed("未登录", SyncFailReason.LOGIN)

        val token = if (s.accessValid) {
            s.accessToken
        } else {
            val r = ensureAccessToken()
            r.getOrElse {
                return SyncResult.Failed(
                    if (it is java.io.IOException) "无网络" else "登录状态失效",
                    if (it is java.io.IOException) SyncFailReason.NETWORK else SyncFailReason.LOGIN,
                )
            }
        }

        // 先正常同步；接口报错（登录态被挤掉/风控/限流）时强制重新登录后重试一次。
        // 失败绝不吞掉报成功，也绝不因此清空本地缓存。
        val first = runCatching { syncCourses(token) }
        if (first.isSuccess) {
            refreshUserInfo()
            authStore.touchSync()
            return SyncResult.Success
        }
        val e = first.exceptionOrNull()!!
        if (e is java.io.IOException) {
            return SyncResult.Failed("无网络", SyncFailReason.NETWORK)
        }

        val retry = runCatching {
            val t = forceReauth().getOrThrow()
            syncCourses(t)
        }
        return if (retry.isSuccess) {
            refreshUserInfo()
            authStore.touchSync()
            SyncResult.Success
        } else {
            val e2 = retry.exceptionOrNull()!!
            SyncResult.Failed(
                if (e2 is java.io.IOException) "无网络" else "登录状态失效，请重新登录",
                if (e2 is java.io.IOException) SyncFailReason.NETWORK else SyncFailReason.LOGIN,
            )
        }
    }

    /** 强制重新认证并换取新 access_token（登录态被挤掉/失效时的自愈；轻量续约优先） */
    private suspend fun forceReauth(): Result<String> = tokenMutex.withLock {
        refreshAccessTokenLocked()
    }

    /** 手动刷新登录状态：重新登录并补拉用户信息（设置页刷新按钮） */
    suspend fun refreshLoginState(): SyncResult {
        val s = authStore.current()
        if (s.username.isEmpty()) return SyncResult.Failed("未登录", SyncFailReason.LOGIN)
        return when (val r = login(s.username, s.password)) {
            is LoginResult.Success -> SyncResult.Success
            is LoginResult.NeedCaptcha -> SyncResult.Failed("需要短信验证，请退出后重新登录", SyncFailReason.LOGIN)
            is LoginResult.Failed -> SyncResult.Failed(r.message, r.reason)
        }
    }

    /**
     * 拉取课程并写库（保留自定义课程；课表页按当前学期过滤显示）。
     * 数据源（2026-08 实测校正）：
     * - 正式课：`getZdyCourse?zc=N` 按周拉取（官方 H5 周视图数据源），条目带完整 week 模式，
     *   kch 无唯一性（多为 "0"）→ 用内容哈希生成确定性本地主键，跨周去重。
     * - 自定义课：`getKcXxByKcmc`（真实 kch / color / create_user_id）。
     */
    private suspend fun syncCourses(token: String) {
        val xlxx = api.getXlxx(token).getOrElse { null }
        if (xlxx != null) {
            db.semesterDao().upsert(
                SemesterEntity(
                    xn = xlxx.xn,
                    xqm = xlxx.xqm,
                    xq = xlxx.xq,
                    currentWeek = xlxx.dqrqszzc?.toIntOrNull() ?: 1,
                    totalWeeks = xlxx.zzs?.toIntOrNull() ?: 20,
                    semesterStart = xlxx.ksrq,
                )
            )
        }
        // 缺学期信息的历史本地自定义课补到当前学期
        val semXn = xlxx?.xn
        val semXqm = xlxx?.xqm?.toIntOrNull()
        if (!semXn.isNullOrEmpty() && semXqm != null) {
            db.courseDao().backfillCustomSemester(semXn, semXqm)
        }

        // 自定义课（真实 kch/颜色/创建人）
        val customRemote = api.getCourses(token).getOrThrow()

        // 正式课：逐周拉取（服务端无全量接口）。限流并发 4——串行 20+ 次请求在弱网下
        // 会逼近 access_token 的 120s 寿命红线；任一周失败整轮抛错走自愈，
        // 绝不用半拉子数据覆盖本地缓存。
        val totalWeeks = xlxx?.zzs?.toIntOrNull()
            ?: db.semesterDao().get()?.totalWeeks ?: 20
        val officialByKey = java.util.concurrent.ConcurrentHashMap<String, CourseData>()
        coroutineScope {
            val permits = Semaphore(4)
            (1..totalWeeks).map { zc ->
                async {
                    permits.withPermit {
                        api.getWeekCourses(token, zc).getOrThrow().forEach { c ->
                            // 自定义课若也出现在周视图里，以 getKcXxByKcmc 的为准（有真实 kch/颜色）
                            if (c.createUserId == null) officialByKey.putIfAbsent(officialKey(c), c)
                        }
                    }
                }
            }.forEach { it.await() }
        }

        // 空响应保护：三路全空且校历也失败 = 服务端异常，抛错保留本地缓存
        if (officialByKey.isEmpty() && customRemote.isEmpty() && xlxx == null) {
            throw ApiException("课表数据异常（空响应），已保留本地缓存")
        }

        val customLocal = db.courseDao().getCustomCourses().associateBy { it.kch }
        val myNo = authStore.current().username

        val officialEntities = officialByKey.values.map { c ->
            CourseEntity(
                // 正式课 kch 服务端不具备唯一性（多为 "0"）：内容哈希做主键，跨同步稳定
                kch = "off_" + md5(officialKey(c)),
                kcmc = c.kcmc.orEmpty(),
                jsxm = c.jsxm,
                jc = c.jc ?: "00000000000000",
                skjsl = c.skjsl,
                skxql = c.skxql?.toIntOrNull() ?: 1,
                week = normalizeWeek(c.week),
                bs = c.bs ?: "1",
                xykh = c.xykh,
                xn = c.xn,
                xqm = c.xqm,
                status = c.status ?: 1,
                color = c.color,
                skjc = c.skjc,
                xf = c.xf,
                weekFb = c.weekFb,
                // kcrq 是"该周具体上课日期"（每次课一条），不是补课标记——必须置空，
                // 否则会被 inWeek() 当成单次补课课处理
                kcrq = null,
                createUserId = null,
                isCustom = false,
            )
        }
        val customEntities = customRemote.map { c ->
            CourseEntity(
                kch = c.kch ?: UUID.randomUUID().toString(),
                kcmc = c.kcmc.orEmpty(),
                jsxm = c.jsxm,
                jc = c.jc ?: "00000000000000",
                skjsl = c.skjsl,
                skxql = c.skxql?.toIntOrNull() ?: 1,
                week = c.week.orEmpty(),
                bs = c.bs ?: "1",
                xykh = c.xykh,
                xn = c.xn,
                xqm = c.xqm,
                status = c.status ?: 1,
                color = c.color,
                skjc = c.skjc,
                xf = c.xf,
                weekFb = c.weekFb,
                kcrq = c.kcrq,
                // 服务端自定义课：创建人 = 当前学号（官方 App 添加的课本端可编辑/删除）
                createUserId = c.createUserId,
                isCustom = customLocal.containsKey(c.kch) || (c.createUserId != null && c.createUserId == myNo),
            )
        }
        val entities = officialEntities + customEntities
        db.courseDao().upsertAll(entities)
        // 移除服务端已不存在的同步课程（保留本地自定义）
        val syncedIds = entities.map { it.kch }.toSet()
        db.courseDao().getAll().filter { !it.isCustom && it.kch !in syncedIds }
            .forEach { db.courseDao().deleteByKch(it.kch) }
        // 本地补传成功的课程（local* 副本）与云端自定义课重复：以云端为准清理（同学期内比较）。
        // 只跟云端自定义课比（正式课与自定义课同名同时段属巧合，不能误删）
        val remoteKeys = customEntities.map { CourseKey(it.kcmc, it.skxql, it.jc, it.xn, it.xqm) }.toSet()
        db.courseDao().getAll().filter {
            it.isCustom && it.kch.startsWith("local") &&
                CourseKey(it.kcmc, it.skxql, it.jc, it.xn, it.xqm) in remoteKeys
        }.forEach { db.courseDao().deleteByKch(it.kch) }
    }

    /** 正式课内容去重键（kcrq 每次课不同，不参与；week 归一化后参与） */
    private fun officialKey(c: CourseData): String = listOf(
        c.kcmc.orEmpty(), c.jsxm.orEmpty(), c.skxql.orEmpty(), c.jc.orEmpty(),
        normalizeWeek(c.week), c.bs.orEmpty(), c.skjsl.orEmpty(),
        c.xn.orEmpty(), c.xqm?.toString().orEmpty(),
    ).joinToString("|")

    /** week 归一化：",12,14," 与 "12,14" 统一为 "12,14"（空段/非数字丢弃，升序） */
    private fun normalizeWeek(week: String?): String =
        week.orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }
            .distinct().sorted().joinToString(",")

    private fun md5(s: String): String =
        java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** 课程去重键：同学期、同时间、同名视为同课 */
    private data class CourseKey(
        val kcmc: String,
        val skxql: Int,
        val jc: String,
        val xn: String?,
        val xqm: Int?,
    )

    // ---------- 自定义课程 ----------

    /**
     * 查询某天某节次范围内是否已有**自定义课程**（用于添加/编辑时冲突校验；编辑时排除自身）。
     * 与官方 H5 一致：只校验自定义课之间，正式课不参与冲突判定。
     * 按学期隔离：不同学期的同名同时段课程互不冲突。
     * [weeks] 非空时要求周次集合有交集才算冲突（错开周次/单双周的课互不冲突）。
     */
    suspend fun findConflict(
        day: Int,
        start: Int,
        end: Int,
        excludeKch: String? = null,
        xn: String? = null,
        xqm: Int? = null,
        weeks: Set<Int>? = null,
    ): CourseEntity? =
        observeCourses().first().firstOrNull { c ->
            c.isCustom && c.skxql == day && c.kch != excludeKch &&
                c.xn == xn && c.xqm == xqm &&
                c.sectionRange().let { (s, e) -> start <= e && s <= end } &&
                (weeks == null || parseWeeks(c.week).intersect(weeks).isNotEmpty())
        }

    /** week 字符串 ",12,14," -> {12, 14}（容错空段/非数字） */
    private fun parseWeeks(week: String): Set<Int> =
        week.split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()

    /** 生成本地自定义课程 ID（时间戳+随机） */
    private fun localKch(): String = "local${System.currentTimeMillis()}${(1000..9999).random()}"

    /**
     * 静默校验登录态后执行云端操作：
     * 每次尝试先确保 access_token 有效（失效自动重新认证）；
     * 操作失败且为非网络错误（如 10004 未登录/风控/限流）时，强制重新登录后再试，
     * 共 [times] 次。网络错误（IOException）立即抛出，不做无谓重试。
     */
    private suspend fun <T> withLoginRetry(times: Int, block: suspend (String) -> T): T {
        var lastError: Exception? = null
        repeat(times) {
            try {
                val token = ensureAccessToken().getOrThrow()
                return block(token)
            } catch (e: Exception) {
                lastError = e
                if (e is java.io.IOException) throw e
                forceReauth().getOrElse { throw it }
            }
        }
        throw lastError ?: Exception("操作失败")
    }

    /** 添加自定义课程（本地保存 + 同步到服务端；云端失败时回滚本地插入，可重试） */
    suspend fun addCustomCourse(course: CourseEntity): SyncResult =
        addCustomCourse(course, attempts = 2)

    private suspend fun addCustomCourse(course: CourseEntity, attempts: Int): SyncResult {
        // 缺学期信息时归入当前学期（新课程总是属于当前学期）
        val cur = currentSemesterKey()
        val entity = course.copy(
            kch = if (course.kch.isEmpty()) localKch() else course.kch,
            isCustom = true,
            xn = course.xn ?: cur.first,
            xqm = course.xqm ?: cur.second,
        )
        db.courseDao().insert(entity)
        return try {
            val r = withLoginRetry(attempts) { token ->
                api.addCustomCourse(token, entity.toApiModel()).getOrThrow()
            }
            if (r.code == 1) {
                SyncResult.Success
            } else {
                Log.e("Kcb", "addCustomCourse 云端拒绝: ${r.message}")
                db.courseDao().delete(entity)
                SyncResult.Failed("云端保存失败（${r.message}），请重试")
            }
        } catch (e: Exception) {
            Log.e("Kcb", "addCustomCourse exception", e)
            db.courseDao().delete(entity)
            SyncResult.Failed(
                if (e is java.io.IOException) "无网络，请检查网络后重试"
                else "云端保存失败，请重试：${cleanMsg(e.message)}"
            )
        }
    }

    /**
     * 删除自定义课程（官方接口：POST deleZdyKc?kch=xxx，query 参数；登录态失效自动自愈重试）。
     * 本地 local* 副本可能在添加成功后、下次同步前已有云端孪生（真实雪花 kch）：
     * 直接拿 local kch 调云端必败、同步后课程"复活"。删除时先按 CourseKey 找到云端孪生，
     * 本地与云端一并删除。
     */
    suspend fun deleteCustomCourse(course: CourseEntity): SyncResult =
        deleteCustomCourse(course, attempts = 2)

    private suspend fun deleteCustomCourse(course: CourseEntity, attempts: Int): SyncResult {
        // 云端真实 kch 列表：自身（非 local 的自定义课）或全部云端孪生（local 副本）。
        // 注意排除正式课（isCustom=false 的 off_*）：同名同时段的正式课只是巧合，
        // 拿它的合成 kch 调云端删除必败且会误删本地正式课。
        val remoteKchs: List<String> = if (course.kch.startsWith("local")) {
            val key = CourseKey(course.kcmc, course.skxql, course.jc, course.xn, course.xqm)
            db.courseDao().getAll().filter {
                it.isCustom && !it.kch.startsWith("local") &&
                    CourseKey(it.kcmc, it.skxql, it.jc, it.xn, it.xqm) == key
            }.map { it.kch }
        } else listOf(course.kch)

        db.courseDao().delete(course)
        remoteKchs.forEach { db.courseDao().deleteByKch(it) }

        // 纯本地课程（从未上传成功）：本地删除即完成
        if (remoteKchs.isEmpty()) return SyncResult.Success

        return try {
            withLoginRetry(attempts) { token ->
                remoteKchs.forEach { kch ->
                    val r = api.deleteCustomCourse(token, kch).getOrThrow()
                    if (r.code != 1) throw ApiException("云端删除失败（${r.message}）")
                }
            }
            SyncResult.Success
        } catch (e: Exception) {
            Log.e("Kcb", "deleteCustomCourse exception", e)
            SyncResult.Failed(
                if (e is java.io.IOException) "无网络，课程已从本地移除但云端未删除"
                else "${cleanMsg(e.message)}，课程可能再次出现"
            )
        }
    }

    /**
     * 编辑自定义课程 = 删除旧课程（云端+本地）再重新添加（覆盖式）。
     * 删除与添加各自后台最多试 3 次（含登录态自愈重试）；
     * 目标时段已有其他自定义课则失败提示（不覆盖他人课程）。
     * 数据安全：添加失败时把原课程恢复到本地（云端已删的会在下次同步被清掉，
     * 恢复本地副本避免用户数据凭空消失）。
     */
    suspend fun updateCustomCourse(course: CourseEntity): SyncResult {
        val oldKch = course.kch
        val old = db.courseDao().getByKch(oldKch)
        if (old == null) return addCustomCourse(course.copy(kch = ""))
        val del = deleteCustomCourse(old, attempts = 3)
        if (del is SyncResult.Failed) {
            // 删除是"先本地后云端"：云端失败时本地已删，恢复本地副本避免课程凭空消失
            runCatching { db.courseDao().insert(old) }
            return SyncResult.Failed("删除原课程失败（${del.message}），未修改")
        }
        val add = addCustomCourse(course.copy(kch = ""), attempts = 3)
        if (add is SyncResult.Failed) {
            runCatching { db.courseDao().insert(old) }
            return SyncResult.Failed("${add.message}（原课程已恢复到本地）")
        }
        return add
    }

    suspend fun logout() {
        authStore.logout()
        // 清空全部课程与校历（含服务端自定义课，防止换账号残留）
        db.courseDao().deleteAll()
        db.semesterDao().clear()
    }

    private fun CourseEntity.toApiModel() = CourseData(
        // 本地生成的 kch 服务端无法解析（Long 类型），置空让服务端新建
        kch = if (kch.startsWith("local")) "" else kch,
        kcmc = kcmc,
        jsxm = jsxm,
        jc = jc,
        skjsl = skjsl,
        skxql = skxql.toString(),
        // 服务端要求无首尾逗号（官方 H5 为 join(",")）
        week = week.trim(','),
        bs = bs,
        xykh = xykh,
        xn = xn,
        xqm = xqm,
        status = status,
        color = color,
        skjc = skjc,
        xf = xf,
        weekFb = weekFb,
        kcrq = kcrq,
    )
}
