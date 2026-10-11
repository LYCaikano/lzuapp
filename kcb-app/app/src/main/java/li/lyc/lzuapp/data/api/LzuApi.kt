package li.lyc.lzuapp.data.api

import li.lyc.lzuapp.data.api.model.AccessTokenData
import li.lyc.lzuapp.data.api.model.CourseData
import li.lyc.lzuapp.data.api.model.GatewayResponse
import li.lyc.lzuapp.data.api.model.GradeRecord
import li.lyc.lzuapp.data.api.model.LoginData
import li.lyc.lzuapp.data.api.model.StuXqItem
import li.lyc.lzuapp.data.api.model.UserInfoData
import li.lyc.lzuapp.data.api.model.XlxxData
import li.lyc.lzuapp.util.LzuCrypto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 兰州大学课程表 API 客户端
 *
 * 认证链:
 *   loginV2 (AES) -> login_token + gateway_token
 *   getSt -> ST 票据
 *   accessTokenBySt -> access_token (120s, 仅可兑换一次)
 *   API 请求带 Authorization + Cookie: eusp_token
 */
class LzuApi(private val client: OkHttpClient = defaultClient()) {

    companion object {
        const val GATEWAY = "https://gateway.lzu.edu.cn:9000"
        const val APPSERVICE = "https://appservice.lzu.edu.cn/api"
        const val SERVICE_URL = "https://gateway.lzu.edu.cn:9000/auth/token/logout"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val mediaType = "application/json;charset=UTF-8".toMediaType()

    // ---------- 登录 ----------

    /**
     * 账号密码登录。可能返回 code=20001 需要短信二次认证。
     * 成功返回 LoginData；需验证码时返回的 LoginData.sessionId 非空。
     */
    suspend fun login(username: String, password: String, deviceId: String): Result<LoginData> =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("app_os", JsonPrimitive(2))
                put("name", JsonPrimitive(username))
                put("pwd", JsonPrimitive(password))
                put("device_id", JsonPrimitive(deviceId))
                put("divece_system", JsonPrimitive(android.os.Build.VERSION.RELEASE))
            }.toString()
            gatewayPost<LoginData>(
                path = "/eusp-unify-terminal/app-user/loginV2",
                body = body,
                encryptBody = true,
                decryptResp = true,
                acceptCodes = setOf(1, 20001),
            ).map { gw ->
                gw.data ?: LoginData()
            }
        }

    /** 发送短信验证码（表单，不加密） */
    suspend fun sendPhoneCaptcha(deviceId: String, sessionId: String): Result<GatewayResponse<String>> =
        withContext(Dispatchers.IO) {
            gatewayFormPost("/eusp-unify-terminal/app-user/sendPhoneCaptcha", mapOf(
                "deviceId" to deviceId,
                "sessionId" to sessionId,
            ))
        }

    /** 校验短信验证码（表单，不加密） */
    suspend fun verifyPhoneCaptcha(deviceId: String, sessionId: String, captcha: String): Result<LoginData> =
        withContext(Dispatchers.IO) {
            gatewayFormPost<LoginData>("/eusp-unify-terminal/app-user/verifyPhoneCaptcha", mapOf(
                "deviceId" to deviceId,
                "sessionId" to sessionId,
                "appOs" to "1",
                "captcha" to captcha,
            )).map { gw -> gw.data ?: LoginData() }
        }

    /**
     * 用户信息。真实 App（StartPage.f0）为 **GET** 请求：loginToken 在 URL query，
     * Transfer-Encrypt: true，响应 AES 加密。POST 会被服务端拒绝（code=0）。
     * 响应 data 可能是 JSON 字符串或对象，两种格式都兼容。
     */
    suspend fun userInfo(loginToken: String, gatewayToken: String): Result<JsonObject> =
        withContext(Dispatchers.IO) {
            gatewayGet<JsonElement>(
                path = "/eusp-unify-terminal/app-user/userInfo?loginToken=" +
                    java.net.URLEncoder.encode(loginToken, "UTF-8"),
                authorization = gatewayToken,
                decryptResp = true,
            ).map { gw ->
                when (val d = gw.data) {
                    is JsonPrimitive -> json.parseToJsonElement(d.content) as? JsonObject ?: buildJsonObject {}
                    is JsonObject -> d
                    else -> buildJsonObject {}
                }
            }
        }

    /** 获取 ST 票据 */
    suspend fun getSt(loginToken: String, gatewayToken: String): Result<String> =
        withContext(Dispatchers.IO) {
            val query = "loginToken=" + java.net.URLEncoder.encode(loginToken, "UTF-8") +
                "&serviceId=&service="
            gatewayGet<String>(
                path = "/eusp-unify-terminal/app-user/getSt?$query",
                authorization = gatewayToken,
                decryptResp = true,
            ).map { it.data.orEmpty() }
        }

    /** 获取指定 service 的 ST 票据（如辅导猫 iap/loginSuccess，service 需 urlencode） */
    suspend fun getSt(loginToken: String, gatewayToken: String, service: String): Result<String> =
        withContext(Dispatchers.IO) {
            val query = "loginToken=" + java.net.URLEncoder.encode(loginToken, "UTF-8") +
                "&serviceId=&service=" + java.net.URLEncoder.encode(service, "UTF-8")
            gatewayGet<String>(
                path = "/eusp-unify-terminal/app-user/getSt?$query",
                authorization = gatewayToken,
                decryptResp = true,
            ).map { it.data.orEmpty() }
        }

    /** 按服务目录 service_info_id 签 ST（官方 h0.d() 打开 H5 服务前调用，如宿舍电费 29615） */
    suspend fun getStByServiceId(loginToken: String, gatewayToken: String, serviceId: String): Result<String> =
        withContext(Dispatchers.IO) {
            val query = "loginToken=" + java.net.URLEncoder.encode(loginToken, "UTF-8") +
                "&serviceId=" + java.net.URLEncoder.encode(serviceId, "UTF-8") + "&service="
            gatewayGet<String>(
                path = "/eusp-unify-terminal/app-user/getSt?$query",
                authorization = gatewayToken,
                decryptResp = true,
            ).map { it.data.orEmpty() }
        }

    /** ST 兑换 access_token（120s 有效，仅可兑换一次） */
    suspend fun accessTokenBySt(st: String): Result<AccessTokenData> =
        withContext(Dispatchers.IO) {
            val url = "$APPSERVICE/auth/token/accessTokenBySt?st=${java.net.URLEncoder.encode(st, "UTF-8")}&service=${java.net.URLEncoder.encode(SERVICE_URL, "UTF-8")}"
            val request = Request.Builder().url(url).get().build()
            runCatching {
                client.newCall(request).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw ApiException("HTTP ${resp.code}: $text")
                    val gw = json.decodeFromString<GatewayResponse<AccessTokenData>>(text)
                    if (gw.code != 1) throw ApiException("accessTokenBySt: ${gw.message}")
                    gw.data ?: throw ApiException("accessTokenBySt: empty data")
                }
            }
        }

    // ---------- 课程表 ----------

    /** 校历：当前周 / 学期信息 */
    suspend fun getXlxx(token: String): Result<XlxxData> =
        withContext(Dispatchers.IO) {
            appPost<XlxxData>("/lzu-teaching-research/kcb/getXlxx", token, body = "{}").map { it.data ?: XlxxData() }
        }

    /** 课程表数据（传空课程名返回全部课程） */
    suspend fun getCourses(token: String): Result<List<CourseData>> =
        withContext(Dispatchers.IO) {
            appPost<List<CourseData>>("/lzu-teaching-research/kcb/getKcXxByKcmc", token, body = """{"kcmc":""}""")
                .map { it.data.orEmpty() }
        }

    /** 添加/更新自定义课程 */
    suspend fun addCustomCourse(token: String, course: CourseData): Result<GatewayResponse<JsonElement>> =
        withContext(Dispatchers.IO) {
            appPost<JsonElement>(
                "/lzu-teaching-research/kcb/addorUpdateZdyKc",
                token,
                body = json.encodeToString(li.lyc.lzuapp.data.api.model.AddCourseRequest(listOf(course))),
            )
        }

    /** 删除自定义课程（官方 H5：POST deleZdyKc?kch=xxx，kch 走 URL query 参数） */
    suspend fun deleteCustomCourse(token: String, kch: String): Result<GatewayResponse<JsonElement>> =
        withContext(Dispatchers.IO) {
            appPost<JsonElement>(
                "/lzu-teaching-research/kcb/deleZdyKc?kch=" +
                    java.net.URLEncoder.encode(kch, "UTF-8"),
                token,
                body = "{}",
            )
        }

    /**
     * 按周取课（官方课表 H5 周视图的数据源，2025-09 版起正式课改走这里）：
     * GET getZdyCourse?zc=周次&qsbz=起始日。**注意：该 GET 不能带 Content-Type 头，
     * 否则服务端一律返回 {"code":0,"失败"}**（实测，2026-08-29）。
     * 返回该周有课的课程条目（含正式课；kcrq 为该周具体上课日期，week 为完整周次模式）。
     */
    suspend fun getWeekCourses(token: String, zc: Int, qsbz: Int = 0): Result<List<CourseData>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("$APPSERVICE/lzu-teaching-research/kcb/getZdyCourse?zc=$zc&qsbz=$qsbz")
                    .get()
                    .header("Authorization", token)
                    .header("Cookie", "eusp_token=$token")
                    .build()
                client.newCall(request).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw ApiException("HTTP ${resp.code}: $text")
                    val gw = json.decodeFromString<GatewayResponse<List<CourseData>>>(text)
                    if (gw.code != 1) throw ApiException("getZdyCourse zc=$zc: ${gw.code}: ${gw.message}")
                    gw.data.orEmpty()
                }
            }
        }

    // ---------- 成绩查询（wdcj 命名空间，认证同课表；逆向自 lzu-web-eusp_h5_myscores） ----------

    /** 有成绩的学期列表。注意此处 xqm 语义与课表不同：1=秋季 2=春季 */
    suspend fun getStuXqList(token: String): Result<List<StuXqItem>> =
        withContext(Dispatchers.IO) {
            appPost<List<StuXqItem>>("/lzu-teaching-research/wdcj/getStuXq", token, body = "{}")
                .map { it.data.orEmpty() }
        }

    /**
     * 成绩列表。xn/xqm 传 0 = 全部学期。
     * 响应 message 是总条数；data 以页码字符串为 key（{"1":[...]}），全部合并返回。
     */
    suspend fun getGrades(token: String, xn: Int = 0, xqm: Int = 0): Result<List<GradeRecord>> =
        withContext(Dispatchers.IO) {
            val body = """{"page":1,"size":999,"xn":$xn,"xqm":$xqm}"""
            appPost<Map<String, List<GradeRecord>>>(
                "/lzu-teaching-research/wdcj/getWdcj", token, body = body,
            ).map { gw -> gw.data.orEmpty().values.flatten() }
        }

    /**
     * 邮箱跳转（appMail 卡片）：返回 Coremail 手机端带 sid 的完整 URL。
     * 注意：响应是裸字符串（非 GatewayResponse JSON），sid 短时效，拿到即用。
     */
    suspend fun getMailJumpUrl(token: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("$APPSERVICE/eusp-email-server/service/mail/getJumpMenu")
                    .post("""{"data":{}}""".toRequestBody(mediaType))
                    .header("Authorization", token)
                    .header("Cookie", "eusp_token=$token")
                    .build()
                client.newCall(request).execute().use { resp ->
                    val text = resp.body?.string().orEmpty().trim()
                    if (!resp.isSuccessful) throw ApiException("HTTP ${resp.code}: $text")
                    // 成功是裸 URL 字符串；失败是 GatewayResponse JSON（code 前置给上层重试判定）
                    if (text.startsWith("http")) return@use text
                    val gw = runCatching {
                        json.decodeFromString<GatewayResponse<kotlinx.serialization.json.JsonElement>>(text)
                    }.getOrNull()
                    if (gw != null) throw ApiException("${gw.code}: ${gw.message}")
                    throw ApiException("getJumpMenu: $text")
                }
            }
        }

    // ---------- HTTP 封装 ----------

    /**
     * gateway 网关 POST。AES 加密/解密，[acceptCodes] 之外的 code 视为异常。
     */
    private suspend inline fun <reified T> gatewayPost(
        path: String,
        body: String,
        encryptBody: Boolean = false,
        decryptResp: Boolean = false,
        authorization: String = "",
        acceptCodes: Set<Int> = setOf(1),
    ): Result<GatewayResponse<T>> = runCatching {
        val finalBody = if (encryptBody) LzuCrypto.encryptToHex(body) else body
        val request = Request.Builder()
            .url(GATEWAY + path)
            .post(finalBody.toRequestBody(mediaType))
            .apply {
                if (encryptBody) header("Transfer-Encrypt", "true")
                if (authorization.isNotEmpty()) header("Authorization", authorization)
            }
            .build()
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ApiException("HTTP ${resp.code}: $text")
            val final = if (decryptResp) LzuCrypto.decryptHex(text) else text
            val gw = json.decodeFromString<GatewayResponse<T>>(final)
            if (gw.code !in acceptCodes) throw ApiException("${gw.code}: ${gw.message}")
            gw
        }
    }

    private suspend inline fun <reified T> gatewayGet(
        path: String,
        authorization: String = "",
        decryptResp: Boolean = false,
    ): Result<GatewayResponse<T>> = runCatching {
        val request = Request.Builder()
            .url(GATEWAY + path)
            .get()
            .apply {
                if (authorization.isNotEmpty()) header("Authorization", authorization)
                if (decryptResp) header("Transfer-Encrypt", "true")
            }
            .build()
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ApiException("HTTP ${resp.code}: $text")
            val final = if (decryptResp) LzuCrypto.decryptHex(text) else text
            val gw = json.decodeFromString<GatewayResponse<T>>(final)
            if (gw.code != 1) throw ApiException("${gw.code}: ${gw.message}")
            gw
        }
    }

    private suspend inline fun <reified T> gatewayFormPost(
        path: String,
        params: Map<String, String>,
    ): Result<GatewayResponse<T>> = runCatching {
        val form = FormBody.Builder().apply {
            params.forEach { (k, v) -> add(k, v) }
        }.build()
        val request = Request.Builder()
            .url(GATEWAY + path)
            .post(form)
            .build()
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ApiException("HTTP ${resp.code}: $text")
            val gw = json.decodeFromString<GatewayResponse<T>>(text)
            if (gw.code != 1) throw ApiException("${gw.code}: ${gw.message}")
            gw
        }
    }

    private suspend inline fun <reified T> appPost(path: String, token: String, body: String): Result<GatewayResponse<T>> =
        runCatching {
            val request = Request.Builder()
                .url(APPSERVICE + path)
                .post(body.toRequestBody(mediaType))
                .header("Authorization", token)
                .header("Cookie", "eusp_token=$token")
                .build()
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw ApiException("HTTP ${resp.code}: $text")
                val gw = json.decodeFromString<GatewayResponse<T>>(text)
                if (gw.code != 1) throw ApiException("${gw.code}: ${gw.message}")
                gw
            }
        }
}

class ApiException(message: String) : Exception(message)
