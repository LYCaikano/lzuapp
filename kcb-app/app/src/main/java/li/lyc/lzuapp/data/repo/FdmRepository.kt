package li.lyc.lzuapp.data.repo

import li.lyc.lzuapp.data.AuthStore
import li.lyc.lzuapp.data.api.FdmApi
import li.lyc.lzuapp.data.api.LzuApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** 查寝任务分类（以任务所在的数组为准，不看 signStatus 数字） */
enum class FdmCategory(val label: String) {
    SIGNED("已签到"), UNSIGNED("未签到"), LEAVE("已请假"), REGISTER_LEAVE("免签"),
}

/** 签到时段解析格式（inSignWindow 热路径复用；SimpleDateFormat 非线程安全，ThreadLocal 隔离） */
private val signTimeFormat = object : ThreadLocal<java.text.SimpleDateFormat>() {
    override fun initialValue() = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
}

/** 查寝任务行 */
data class FdmTask(
    val category: FdmCategory,
    val stuSignWid: String,
    val signInstanceWid: String,
    val signWid: String,
    val taskName: String,
    val senderUserName: String,
    /** singleTaskBeginTime/EndTime（"2026-08-28 21:00"） */
    val beginTime: String,
    val endTime: String,
    /** rateSignDate（"2026-08-28 (周五)"） */
    val rateSignDate: String,
    val isMalposition: String,
    val isLate: Boolean,
) {
    /** 当前是否在签到时段内 */
    val inSignWindow: Boolean
        get() {
            val fmt = signTimeFormat.get() ?: return false
            val b = runCatching { fmt.parse(beginTime)?.time }.getOrNull() ?: run {
                android.util.Log.w("FdmRepo", "inSignWindow: beginTime 解析失败: $beginTime")
                return false
            }
            val e = runCatching { fmt.parse(endTime)?.time }.getOrNull() ?: run {
                android.util.Log.w("FdmRepo", "inSignWindow: endTime 解析失败: $endTime")
                return false
            }
            return System.currentTimeMillis() in b..e
        }

    /** 时段显示（"21:00-22:00"） */
    val timeRange: String get() = "${beginTime.substringAfter(" ")}-${endTime.substringAfter(" ")}"

    /** 当前可签：未签任务在时段内；登记类任务在时段内也可签（服务端数组归类可能滞后于时段切换，登记≠免签） */
    val signableNow: Boolean
        get() = (category == FdmCategory.UNSIGNED || category == FdmCategory.REGISTER_LEAVE) && inSignWindow
}

/** 定位围栏点 */
data class FdmFence(val address: String, val longitude: Double, val latitude: Double, val radius: Double)

/** 任务详情（签到流程用） */
data class FdmTaskDetail(
    val signMode: Int,
    val isPhoto: Boolean,
    /** 拍照要求文案（photograph[0].content） */
    val photoTip: String,
    val fences: List<FdmFence>,
    /** signCondition==1：允许范围外签到（填异常原因提交） */
    val allowOutOfFence: Boolean,
    val signInstanceWid: String,
    val taskName: String,
    val senderUserName: String,
    val beginTime: String,
    val endTime: String,
    /** 已签到任务的云端照片 URL（带签名参数，约 5 分钟过期，拿到即用） */
    val signPhotoUrl: String?,
    /** 签到位置文字（已签到任务） */
    val signAddress: String?,
    /** 签到时间（"2026-08-29 22:53"，已签到任务） */
    val signTime: String?,
    /** 签到坐标（BD-09，已签到任务；服务端原样返回提交值） */
    val signLng: Double?,
    val signLat: Double?,
    /** 是否允许更新签到（官方 H5：字段缺失默认允许，显式 false/0 才禁止） */
    val allowUpdate: Boolean,
    /** 最大更新次数（null/0 表示不限） */
    val updateLimit: Int?,
    /** 剩余更新次数 */
    val updateLeftNum: Int?,
) {
    /** 本端不支持的签到模式提示；null = 支持（拍照/定位签到） */
    val unsupportedHint: String?
        get() = when (signMode) {
            1, 2, 4 -> "该任务需扫码签到，请到企业微信完成"
            3 -> "该任务需 IoT 设备签到，请到企业微信完成"
            5 -> "该任务需 NFC 签到，请到企业微信完成"
            else -> null
        }

    /** 是否还能更新签到：允许更新且未超次数（leftNum 存在时用剩余次数判断） */
    val canUpdate: Boolean
        get() = allowUpdate && (updateLimit == null || updateLimit <= 0 || (updateLeftNum ?: 1) > 0)
}

/** 某天的任务列表（本月记录） */
data class FdmDayTasks(val dayInMonth: String, val tasks: List<FdmTask>)

/** 本月统计 */
data class FdmStats(
    val totalSignNum: Int,
    val signNum: Int,
    val unSignNum: Int,
    val leaveNum: Int,
    val registerLeaveNum: Int,
    val stuName: String,
)

/** 本月数据：按天记录（倒序） + 统计 */
data class FdmMonthData(val days: List<FdmDayTasks>, val stats: FdmStats?)

/**
 * 辅导猫查寝仓库：MOD_AUTH_CAS 会话生命周期管理 + 业务调用。
 * 模式同 YktRepository：每个操作前经 [withFdmRetry] 静默校验会话
 * （失效 → 重跑认证链；认证链依赖的 login_token 失效 → 静默重登录后再试），
 * 读操作网络错误重试 2 次，写操作不重试防重复提交。
 */
class FdmRepository(
    private val authStore: AuthStore,
    private val lzuApi: LzuApi,
    private val fdmApi: FdmApi,
    private val courseRepo: CourseRepository,
) {

    sealed interface FdmResult<out T> {
        data class Ok<T>(val data: T) : FdmResult<T>
        data class Err(val message: String, val reason: SyncFailReason = SyncFailReason.OTHER) : FdmResult<Nothing>
    }

    /** 认证链必须串行（cookie 罐共享，getSt 票据一次性） */
    private val sessionMutex = Mutex()

    fun log(msg: String) = fdmApi.logRepo(msg)

    /**
     * 确保 MOD_AUTH_CAS 会话可用。失效链：重跑认证链；
     * 认证链失败（多为 login_token 被挤）→ 静默重新登录 → 再跑一遍。
     */
    private suspend fun ensureFdmSession(): Result<Unit> = sessionMutex.withLock { ensureSessionLocked() }

    /** [ensureFdmSession] 的锁内实现（调用方必须已持有 [sessionMutex]） */
    private suspend fun ensureSessionLocked(): Result<Unit> {
        if (fdmApi.hasSession()) {
            log("ensureSession: 内存会话在，直接用")
            return Result.success(Unit)
        }
        log("ensureSession: 无内存会话，跑认证链")
        val first = loginLocked()
        if (first.isSuccess) {
            log("ensureSession: 认证链成功")
            return first
        }
        log("ensureSession: 认证链失败(${first.exceptionOrNull()?.message})，尝试静默重登录")
        android.util.Log.w("FdmRepo", "fdmLogin failed: ${first.exceptionOrNull()?.message}，尝试静默重登录")
        if (first.exceptionOrNull() is java.io.IOException) return first
        val s = authStore.current()
        val relogin = courseRepo.login(s.username, s.password)
        if (relogin !is LoginResult.Success) {
            log("ensureSession: 静默重登录失败")
            return Result.failure(
                if (relogin is LoginResult.Failed && relogin.reason == SyncFailReason.NETWORK)
                    java.io.IOException("无网络")
                else Exception("登录状态失效")
            )
        }
        log("ensureSession: 静默重登录成功，重跑认证链")
        return loginLocked()
    }

    /**
     * 清会话必须走 [sessionMutex]：否则在途旧请求返回 dead-session 后，
     * 会把别协程刚建好的新会话清掉（或清到认证链半路），造成连环重登录。
     */
    private suspend fun clearSession() = sessionMutex.withLock { fdmApi.clearSession() }

    /** 退出登录：清 fdm 内存会话（cookie 罐），防换账号后串号 */
    suspend fun logout() = clearSession()

    private suspend fun loginLocked(): Result<Unit> {
        val cur = authStore.current()
        if (!cur.isLoggedIn) return Result.failure(Exception("未登录"))
        return runCatching { fdmApi.fdmLogin(cur.loginToken, cur.gatewayToken, lzuApi) }
            .onFailure { if (it is CancellationException) throw it }
    }

    /**
     * 操作前静默校验会话 + 失效自动重试：
     * - 会话/登录态错误（isAuth）：清会话重跑认证链，重试 1 次
     * - 网络错误（IOException）：读操作最多共试 3 次（间隔 500ms）报"无网络"；
     *   写操作（[retryOnNetwork]=false，如提交签到）不重试，防重复提交
     */
    private suspend fun <T> withFdmRetry(
        retryOnNetwork: Boolean = true,
        block: suspend () -> T,
    ): FdmResult<T> {
        var authRetried = false
        var networkAttempts = 0
        val maxNetworkAttempts = if (retryOnNetwork) 3 else 1
        while (true) {
            try {
                ensureFdmSession().getOrThrow()
                return FdmResult.Ok(block())
            } catch (e: CancellationException) {
                // 协程取消（ViewModel 销毁等）必须向上传播，不能转成错误结果继续重试
                throw e
            } catch (e: FdmApi.FdmException) {
                android.util.Log.w("FdmRepo", "api err auth=${e.isAuth} msg=${e.message}")
                if (e.isAuth && !authRetried) {
                    authRetried = true
                    clearSession()
                    continue
                }
                return FdmResult.Err(e.message ?: "操作失败")
            } catch (e: java.io.IOException) {
                android.util.Log.w("FdmRepo", "io err: ${e.message}")
                networkAttempts++
                if (networkAttempts < maxNetworkAttempts) {
                    kotlinx.coroutines.delay(500)
                    continue
                }
                return FdmResult.Err("无网络", SyncFailReason.NETWORK)
            } catch (e: Exception) {
                return FdmResult.Err(e.message ?: "操作失败")
            }
        }
    }

    /**
     * 登录态真实校验（进入查寝页/刷新时调用）：会话在内存不代表服务端有效，
     * 发一次轻量只读请求（getStuIntervalMonths）确认；失效由 withFdmRetry 自动重跑认证链。
     */
    suspend fun checkLoginState(): FdmResult<Unit> = withFdmRetry { fdmApi.intervalMonths() }

    /**
     * 手动刷新用：无论当前会话是否有效，都清掉重跑认证链（参照课表 forceRefresh 的
     * 主动重登录策略）；login_token 也被挤时 ensureFdmSession 会先静默重登录 App 侧。
     * 网络抖动重试 1 次（间隔 500ms），避免瞬时失败误报"登录状态失效"。
     */
    suspend fun forceRelogin(): FdmResult<Unit> {
        var lastErr: Throwable? = null
        repeat(2) { attempt ->
            // 清会话与重跑认证链收进同一把锁，避免与在途请求的清/建会话互相顶
            val r = sessionMutex.withLock {
                fdmApi.clearSession()
                ensureSessionLocked()
            }
            if (r.isSuccess) return FdmResult.Ok(Unit)
            val e = r.exceptionOrNull()
            if (e is java.io.IOException && attempt == 0) {
                kotlinx.coroutines.delay(500)
                return@repeat
            }
            lastErr = e
        }
        return FdmResult.Err(
            lastErr?.message ?: "登录状态失效",
            if (lastErr is java.io.IOException) SyncFailReason.NETWORK else SyncFailReason.LOGIN,
        )
    }

    /** 今日进行中任务（getStuAttendacesInOneDay） */
    suspend fun runningTasks(): FdmResult<List<FdmTask>> = withFdmRetry {
        parseTasks(fdmApi.runningTasks())
    }

    /** 本月数据：按天任务记录（倒序） + 统计 */
    suspend fun monthData(month: String): FdmResult<FdmMonthData> = withFdmRetry {
        val rows = (fdmApi.monthTasks(month)["rows"] as? JsonArray).orEmpty()
        val days = rows.mapNotNull { el ->
            (el as? JsonObject)?.let { row ->
                FdmDayTasks(row.str("dayInMonth"), parseTasks(row))
            }
            // dayInMonth 若为非零填充日期字符串，字符串排序会乱：取数字部分按数值排序，解析失败兜底原串
        }.sortedWith(compareByDescending<FdmDayTasks> {
            it.dayInMonth.filter { c -> c.isDigit() }.toLongOrNull()
        }.thenByDescending { it.dayInMonth })
        FdmMonthData(days, fdmApi.monthStats(month).toStats())
    }

    /** 任务详情（签到模式/拍照要求/定位围栏） */
    suspend fun taskDetail(task: FdmTask): FdmResult<FdmTaskDetail> = withFdmRetry {
        fdmApi.detail(task.signInstanceWid, task.signWid).toDetail()
    }

    /**
     * 提交签到（写操作，网络错误不重试）：
     * checkValidation 风控校验 -> 有照片先 OSS 直传 -> submitSign。
     * [isMalposition] true=当前位置在所有围栏外（UI 侧已用 haversine 判定）。
     */
    suspend fun sign(
        detail: FdmTaskDetail,
        longitude: Double,
        latitude: Double,
        isMalposition: Boolean,
        reason: String,
        photo: ByteArray?,
    ): FdmResult<Unit> = withFdmRetry(retryOnNetwork = false) {
        if (fdmApi.checkValidation()) {
            throw FdmApi.FdmException("触发风控验证，请到企业微信完成本次签到")
        }
        val photoUrl = if (photo != null) fdmApi.uploadPhoto(photo) else ""
        fdmApi.submitSign(
            signInstanceWid = detail.signInstanceWid,
            longitude = longitude,
            latitude = latitude,
            isMalposition = if (isMalposition) 1 else 0,
            abnormalReason = if (isMalposition) reason else "",
            signPhotoUrl = photoUrl,
            position = nearestFence(detail.fences, longitude, latitude)?.address.orEmpty(),
        )
    }

    // ---------- 解析 ----------

    /** 任务数组解析：分类以所在数组为准 */
    private fun parseTasks(obj: JsonObject): List<FdmTask> {
        val out = mutableListOf<FdmTask>()
        fun add(key: String, cat: FdmCategory) {
            (obj[key] as? JsonArray)?.forEach { el ->
                (el as? JsonObject)?.let { out.add(it.toTask(cat)) }
            }
        }
        add("signedTasks", FdmCategory.SIGNED)
        add("unSignedTasks", FdmCategory.UNSIGNED)
        add("leaveTasks", FdmCategory.LEAVE)
        add("registerLeaveTasks", FdmCategory.REGISTER_LEAVE)
        return out
    }

    private fun JsonObject.toTask(cat: FdmCategory) = FdmTask(
        category = cat,
        stuSignWid = str("stuSignWid"),
        signInstanceWid = str("signInstanceWid"),
        signWid = str("signWid"),
        taskName = str("taskName"),
        senderUserName = str("senderUserName"),
        beginTime = str("singleTaskBeginTime"),
        endTime = str("singleTaskEndTime"),
        rateSignDate = str("rateSignDate"),
        isMalposition = str("isMalposition"),
        isLate = str("late") == "1",
    )

    private fun JsonObject.toDetail(): FdmTaskDetail {
        val fences = (this["signPlaceSelected"] as? JsonArray)?.mapNotNull { el ->
            (el as? JsonObject)?.let { p ->
                val lng = p.str("longitude").toDoubleOrNull()
                val lat = p.str("latitude").toDoubleOrNull()
                if (lng == null || lat == null) null
                else FdmFence(
                    address = p.str("address"),
                    longitude = lng,
                    latitude = lat,
                    radius = p.str("radius").toDoubleOrNull() ?: 0.0,
                )
            }
        }.orEmpty()
        val photoTip = (this["photograph"] as? JsonArray)?.firstOrNull()
            ?.let { (it as? JsonObject)?.str("content") }.orEmpty()
        return FdmTaskDetail(
            signMode = num("signMode"),
            isPhoto = num("isPhoto") == 1,
            photoTip = photoTip,
            fences = fences,
            allowOutOfFence = num("signCondition") == 1,
            signInstanceWid = str("signInstanceWid"),
            taskName = str("taskName"),
            senderUserName = str("senderUserName"),
            beginTime = str("singleTaskBeginTime"),
            endTime = str("singleTaskEndTime"),
            // 已签到任务的回显字段（未签到时为空）
            signPhotoUrl = str("signPhotoUrl").ifBlank { null },
            signAddress = str("signAddress").ifBlank { null },
            signTime = str("signTime").ifBlank { null },
            signLng = str("longitude").toDoubleOrNull(),
            signLat = str("latitude").toDoubleOrNull(),
            // 官方 H5：isAllowUpdate 缺失时默认允许（detail.isAllowUpdate || !0）
            allowUpdate = (this["isAllowUpdate"] as? JsonPrimitive)?.let {
                it.content != "0" && !it.content.equals("false", true)
            } ?: true,
            updateLimit = (this["updateLimit"] as? JsonPrimitive)?.intOrNull,
            updateLeftNum = (this["leftNum"] as? JsonPrimitive)?.intOrNull,
        )
    }

    private fun JsonObject.toStats() = FdmStats(
        totalSignNum = num("totalSignNum"),
        signNum = num("signNum"),
        unSignNum = num("unSignNum"),
        leaveNum = num("leaveNum"),
        registerLeaveNum = num("registerLeaveNum"),
        stuName = (this["stuInfo"] as? JsonObject)?.str("stuName").orEmpty(),
    )

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.num(key: String): Int =
        (this[key] as? JsonPrimitive)?.intOrNull ?: 0

    companion object {
        /** haversine 球面距离（米），地球半径 6371004m */
        fun distanceMeters(lng1: Double, lat1: Double, lng2: Double, lat2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLng = Math.toRadians(lng2 - lng1)
            val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
            return 2 * 6371004.0 * asin(sqrt(a))
        }

        /** 是否在任一围栏范围内（distance <= radius） */
        fun inAnyFence(fences: List<FdmFence>, lng: Double, lat: Double): Boolean =
            fences.any { distanceMeters(lng, lat, it.longitude, it.latitude) <= it.radius }

        /** 距当前位置最近的围栏（position 传其 address） */
        fun nearestFence(fences: List<FdmFence>, lng: Double, lat: Double): FdmFence? =
            fences.minByOrNull { distanceMeters(lng, lat, it.longitude, it.latitude) }

        /**
         * 围栏圆内随机一点（收缩到 80% 半径内，避开边界抖动），返回 [lng, lat]（与围栏同坐标系）。
         * 均匀取面积（r 开方），移植自 fudaomao-auto-check-in run.py random_point_in_fence。
         */
        fun randomPointInFence(fence: FdmFence): DoubleArray {
            val r = fence.radius * 0.8 * sqrt(java.util.Random().nextDouble())
            val theta = java.util.Random().nextDouble() * 2 * Math.PI
            val dLat = r * cos(theta) / 111320.0
            val latCos = cos(Math.toRadians(fence.latitude))
            val dLng = r * sin(theta) / (111320.0 * (if (latCos == 0.0) 1e-9 else latCos))
            return doubleArrayOf(fence.longitude + dLng, fence.latitude + dLat)
        }
    }
}
