package li.lyc.lzuapp.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 辅导猫（fdm.lzu.edu.cn）查寝签到 API 客户端。
 *
 * 会话凭证 = Cookie MOD_AUTH_CAS（进程内存，不持久化；CASTGC 一并入罐用于续期）。
 * 认证链（复用 App 的 login_token/gateway_token）：
 *   入口页 302 -> iap/login 302 -> sso?service=loginSuccess?sessionToken=X
 *   -> 网关 getSt(service) 换 ST -> loginSuccess&ticket=ST -> 302 落 MOD_AUTH_CAS。
 * 响应信封 {"code":"0","message":"SUCCESS","datas":{...}}，code 字符串 "0" 为成功。
 */
class FdmApi {

    companion object {
        const val BASE = "https://fdm.lzu.edu.cn/wec-counselor-attendance-apps"
        const val ENTRY = "$BASE/student/mobileStudent/index.html"
        const val UA = "Mozilla/5.0 (Linux; Android 15; wv) MicroMessenger/8.0.47"

        /** 请求体敏感（精确坐标/照片 URL/OSS 凭证）的端点：调试日志不落请求体 */
        private val SENSITIVE_PATHS = listOf("submitSign", "getUploadPolicy", "previewAttachment")
    }

    /** 辅导猫业务异常；[isAuth] = 会话/登录态失效（可重跑认证链后重试） */
    class FdmException(message: String, val isAuth: Boolean = false) : Exception(message)

    /** 进程内存 CookieJar（按 domain|path|name 去重，自动剔除过期项） */
    private class MemoryCookieJar : CookieJar {
        private val store = LinkedHashMap<String, Cookie>()

        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val now = System.currentTimeMillis()
            cookies.forEach { c ->
                val key = "${c.domain}|${c.path}|${c.name}"
                if (c.expiresAt <= now) store.remove(key) else store[key] = c
            }
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            val it = store.entries.iterator()
            while (it.hasNext()) if (it.next().value.expiresAt <= now) it.remove()
            return store.values.filter { it.matches(url) }
        }

        @Synchronized
        fun has(name: String): Boolean =
            store.values.any { it.name == name && it.expiresAt > System.currentTimeMillis() }

        @Synchronized
        fun clear() = store.clear()
    }

    private val jar = MemoryCookieJar()
    private val json = Json { ignoreUnknownKeys = true }
    private val mediaType = "application/json;charset=UTF-8".toMediaType()

    private fun clientBuilder() = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)

    /** 认证链专用：不跟随重定向（逐步读 Location 与 Set-Cookie） */
    private val loginClient = clientBuilder()
        .followRedirects(false).followSslRedirects(false)
        .cookieJar(jar)
        .build()

    /** 业务接口专用：3xx 即会话失效（302 回登录页），不跟随 */
    private val apiClient = clientBuilder()
        .followRedirects(false).followSslRedirects(false)
        .cookieJar(jar)
        .build()

    /** OSS 直传专用：不带辅导猫 cookie */
    private val plainClient = clientBuilder()
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 会话是否可用（MOD_AUTH_CAS 在罐且未过期） */
    fun hasSession(): Boolean = jar.has("MOD_AUTH_CAS")

    /** 会话失效时清罐，下次操作重跑认证链 */
    fun clearSession() = jar.clear()

    // ---------- 认证链 ----------

    /** 无重定向 GET，返回 (HTTP code, Location) */
    private fun getNoRedirect(url: String): Pair<Int, String?> {
        val req = Request.Builder().url(url).get().header("User-Agent", UA).build()
        loginClient.newCall(req).execute().use { resp ->
            return resp.code to resp.header("Location")
        }
    }

    /** 相对/绝对 Location 归一为绝对地址 */
    private fun resolve(base: String, location: String): String =
        base.toHttpUrl().resolve(location)?.toString() ?: location

    private fun queryParam(url: String, name: String): String? =
        Regex("[?&]$name=([^&]*)").find(url)?.groupValues?.get(1)

    /**
     * 跑辅导猫认证链，成功即 MOD_AUTH_CAS 入罐；失败抛异常。
     * getSt 失败（login_token 失效）抛 isAuth=true 的 [FdmException]，由仓库层静默重登录。
     */
    suspend fun fdmLogin(loginToken: String, gatewayToken: String, lzuApi: LzuApi) =
        withContext(Dispatchers.IO) {
            jar.clear()
            // 1) 入口页 -> iap/login
            val (c1, loc1) = getNoRedirect(ENTRY)
            if (loc1.isNullOrBlank()) throw FdmException("查寝服务入口异常($c1)")
            val iapLogin = resolve(ENTRY, loc1)
            // 2) iap/login -> sso/login?service=loginSuccess?sessionToken=X
            val (c2, loc2) = getNoRedirect(iapLogin)
            if (loc2.isNullOrBlank()) throw FdmException("查寝认证跳转异常($c2)")
            val svcEncoded = queryParam(resolve(iapLogin, loc2), "service")
                ?: throw FdmException("查寝认证参数缺失")
            val svc = URLDecoder.decode(svcEncoded, "UTF-8")
            // 3) 网关 getSt 换 ST 票据
            val st = lzuApi.getSt(loginToken, gatewayToken, svc).getOrElse { e ->
                throw if (e is java.io.IOException) e
                else FdmException("登录状态失效", isAuth = true)
            }
            if (st.isBlank()) throw FdmException("登录状态失效", isAuth = true)
            // 4) loginSuccess&ticket=ST -> 302 入口页?ticket=ST-iap:...（Set-Cookie: CASTGC）
            val (_, loc4) = getNoRedirect("$svc&ticket=${URLEncoder.encode(st, "UTF-8")}")
            // 5) 最后一跳回入口页（Set-Cookie: MOD_AUTH_CAS）
            if (!loc4.isNullOrBlank()) getNoRedirect(resolve(svc, loc4))
            if (!jar.has("MOD_AUTH_CAS")) throw FdmException("查寝登录失败")
            dbg("fdmLogin ok, entry hop codes: $c1->$c2")
        }

    /** 调试输出（排查空数据/会话问题用），由 Application 注入文件写入 */
    var debugSink: ((String) -> Unit)? = null

    private fun dbg(msg: String) {
        android.util.Log.d("FdmApi", msg)
        runCatching { debugSink?.invoke(msg) }
    }

    /** 给仓库层用的同一落盘通道 */
    fun logRepo(msg: String) = dbg(msg)

    // ---------- 业务接口 ----------

    /**
     * 查寝 POST JSON。302（会话失效回登录页）或非 "0" code 抛 [FdmException]；
     * 无 code 的 HTML 响应按会话失效处理；网络错误抛 IOException（OkHttp 原生）。
     */
    private suspend fun api(path: String, body: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(BASE + path)
            .post(body.toString().toRequestBody(mediaType))
            .header("User-Agent", UA)
            .header("Content-Type", "application/json;charset=UTF-8")
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
        apiClient.newCall(req).execute().use { resp ->
            if (resp.code in 300..399) throw FdmException("登录状态失效", isAuth = true)
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw FdmException("HTTP ${resp.code}")
            val obj = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                ?: throw FdmException("登录状态失效", isAuth = true)
            val code = (obj["code"] as? JsonPrimitive)?.contentOrNull
            val datasKind = obj["datas"]?.javaClass?.simpleName
            dbg("$path -> code=$code datas=$datasKind len=${text.length}")
            // 空数据时落原始响应定位（code=0 但 datas 缺失的诡异情况）；
            // 签到/上传类端点请求体含精确经纬度、照片 URL 等，不落盘
            if (code == "0" && obj["datas"] !is JsonObject) {
                val reqLog = if (SENSITIVE_PATHS.any { path.contains(it) }) "req=<已脱敏>" else "req=$body"
                dbg("$path EMPTY-DATAS $reqLog resp=${text.take(400)}")
            }
            if (code != "0") {
                val msg = (obj["message"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    .ifBlank { "操作失败" }
                // 会话失效表现为 32 位 hex code（如 350F9D...=无权限）或登录类文案
                val auth = code?.matches(Regex("[0-9A-Fa-f]{32}")) == true ||
                    msg.contains("登录") || msg.contains("权限") || msg.contains("失效")
                throw FdmException(msg, isAuth = auth)
            }
            // 会话脱钩金丝雀：loginV2 重登录会顶掉旧 fdm 会话，之后服务端不报错而是
            // 返回 code=0 + 空壳 datas（实测：{"datas":{"WEC-HASLOGIN":false,"WEC-REDIRECTURL":"/portal/login"}}）——
            // 直接识别该标记，再对必有内容的只读接口做结构兜底，一律按会话失效处理
            // （上层清会话重跑认证链重试）。
            // 注意 getStuAttendacesInOneDay 合法地可以全空（无任务时），不能做金丝雀。
            val datas = obj["datas"] as? JsonObject
            val dead = when {
                (datas?.get("WEC-HASLOGIN") as? JsonPrimitive)?.contentOrNull == "false" -> true
                path.contains("getStuIntervalMonths") ->
                    (datas?.get("rows") as? JsonArray).isNullOrEmpty()
                path.contains("getStuSignInfosByWeekMonth") ->
                    datas == null || (!datas.containsKey("serverDate") && !datas.containsKey("rows"))
                path.contains("getSignStatisticsInfoByMonth") ->
                    datas == null || !datas.containsKey("stuInfo")
                else -> false
            }
            if (dead) {
                dbg("$path DEAD-SESSION（空壳响应）resp=${text.take(200)}")
                throw FdmException("登录状态失效", isAuth = true)
            }
            obj
        }
    }

    /** 信封 datas 节点按对象取（缺失时给空对象） */
    private fun JsonObject.datasObj(): JsonObject = this["datas"] as? JsonObject ?: buildJsonObject {}

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    /** 今日进行中任务 */
    suspend fun runningTasks(): JsonObject =
        api("/student/attendance/getStuAttendacesInOneDay", buildJsonObject {}).datasObj()

    /** 本月按天任务记录 */
    suspend fun monthTasks(month: String): JsonObject =
        api("/student/attendance/getStuSignInfosByWeekMonth", buildJsonObject {
            put("statisticYearMonth", month)
        }).datasObj()

    /** 任务详情（签到模式/拍照要求/定位围栏） */
    suspend fun detail(signInstanceWid: String, signWid: String): JsonObject =
        api("/student/attendance/detailSignInstance", buildJsonObject {
            put("signInstanceWid", signInstanceWid)
            put("signWid", signWid)
        }).datasObj()

    /** 风控校验：true = 触发滑块风控（只能去企业微信完成） */
    suspend fun checkValidation(): Boolean {
        val env = api("/student/attendance/checkValidation", buildJsonObject {
            put("deviceId", "")
            put("qrUuid", "")
        })
        return ((env["datas"] as? JsonObject)?.get("validation") as? JsonPrimitive)?.booleanOrNull == true
    }

    /**
     * 拍照 OSS 直传：getUploadPolicy 取凭证 -> multipart 直传 host -> previewAttachment 取 URL。
     * 返回 submitSign 所需的 signPhotoUrl。
     * 对齐官方前端：object key 恒为 fileName + ".png"（policy 的 starts-with 校验允许；
     * contentType 服务端不下发）；file 字段的 filename 只取 basename；成功判定 200/204（OkHttp isSuccessful 涵盖）。
     */
    suspend fun uploadPhoto(jpeg: ByteArray): String {
        val policy = api("/student/attendance/getUploadPolicy", buildJsonObject {
            put("fileType", 1)
        }).datasObj()
        val host = policy.str("host")
        val form = policy["formData"] as? JsonObject
        if (host.isBlank() || form == null) throw FdmException("获取上传凭证失败")
        val fileName = policy.str("fileName").ifBlank { "photo" }
        val ossKey = "$fileName.png"
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            form.forEach { (k, v) ->
                // key 字段替换为带扩展名的完整 object key（官方同款行为），其余原样透传
                val value = if (k == "key") ossKey else (v as? JsonPrimitive)?.contentOrNull.orEmpty()
                addFormDataPart(k, value)
            }
            addFormDataPart(
                "file", ossKey.substringAfterLast('/'),
                jpeg.toRequestBody("image/jpeg".toMediaType()),
            )
        }.build()
        val req = Request.Builder().url(host).post(multipart).header("User-Agent", UA).build()
        withContext(Dispatchers.IO) {
            plainClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw FdmException("照片上传失败(${resp.code})")
            }
        }
        return previewAttachment(ossKey)
    }

    /** 直传后取照片访问 URL（datas 为 URL 字符串或含 url 的对象，宽容解析） */
    private suspend fun previewAttachment(ossKey: String): String {
        val env = api("/student/attendance/previewAttachment", buildJsonObject {
            put("ossKey", ossKey)
        })
        val url = when (val d = env["datas"]) {
            is JsonPrimitive -> d.contentOrNull
            is JsonObject -> d.str("url")
            else -> null
        }
        return url?.takeIf { it.isNotBlank() } ?: throw FdmException("获取照片地址失败")
    }

    /**
     * 提交签到。全部字段必传（空串占位）；code "0" 成功，
     * 4900000001=二维码不匹配等业务错误由 [api] 统一抛错。
     */
    suspend fun submitSign(
        signInstanceWid: String,
        longitude: Double,
        latitude: Double,
        isMalposition: Int,
        abnormalReason: String,
        signPhotoUrl: String,
        position: String,
    ) {
        api("/student/attendance/submitSign", buildJsonObject {
            put("signInstanceWid", signInstanceWid)
            put("longitude", longitude)
            put("latitude", latitude)
            put("isMalposition", isMalposition)
            put("abnormalReason", abnormalReason)
            put("signPhotoUrl", signPhotoUrl)
            put("position", position)
            put("ticket", "")
            put("uaIsCpadaily", false)
            put("deviceId", "")
            put("systemName", "")
            put("systemVersion", "")
            put("model", "")
        })
    }

    /** 本月统计（已签/未签/请假/免签 + 学生姓名） */
    suspend fun monthStats(month: String): JsonObject =
        api("/student/attendance/getSignStatisticsInfoByMonth", buildJsonObject {
            put("statisticYearMonth", month)
        }).datasObj()

    /** 可查月份列表（轻量只读，登录态真实校验用） */
    suspend fun intervalMonths(): JsonObject =
        api("/student/attendance/getStuIntervalMonths", buildJsonObject {}).datasObj()
}
