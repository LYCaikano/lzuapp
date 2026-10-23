package li.lyc.lzuapp.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import li.lyc.lzuapp.data.api.model.EvalAnswer
import li.lyc.lzuapp.data.api.model.EvalCourse
import li.lyc.lzuapp.data.api.model.EvalQuestion
import li.lyc.lzuapp.data.api.model.EvalSubmitItem
import li.lyc.lzuapp.data.api.model.EvalTask
import li.lyc.lzuapp.data.api.model.JwqeLoginData
import li.lyc.lzuapp.data.api.model.JwqePage
import li.lyc.lzuapp.data.api.model.JwqeResponse
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 学生评教（jwqe）API 客户端（逆向自官方 H5，2026-10）
 *
 * 接口：https://jwqe.lzu.edu.cn:8080/api，全部 POST。
 * 认证：doLogin 用 CAS ST 换 accessToken（此请求不带 Authorization）；
 *       之后业务请求带 `Authorization: Bearer <accessToken>`。
 * 响应：HTTP 200 + body code==200（个别接口 0 也算成功，宽容处理）；code!=200 时 message 为错误文案。
 *       HTTP 401、或 message 含「授权失效」「已在其他地方登录」= 鉴权失效（可重新 doLogin 自愈）。
 * 提交类接口 body 为 JSON 数组且 URL 末尾追加 ?a=1（Content-Type: application/json）。
 */
class JwqeApi(private val client: OkHttpClient = defaultClient()) {

    companion object {
        const val BASE = "https://jwqe.lzu.edu.cn:8080/api"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()

        /** 鉴权失效文案（授权失效/异地登录被挤） */
        private fun isAuthMsg(msg: String): Boolean =
            msg.contains("授权失效") || msg.contains("已在其他地方登录")
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMedia = "application/json".toMediaType()

    /** 评教业务异常；[isAuth] = 授权失效/被挤（可重新 doLogin 自愈一次） */
    class JwqeException(message: String, val isAuth: Boolean = false) : Exception(message)

    // ---------- 登录 ----------

    /** CAS ST 登录换 accessToken（UUID）；成功判定 code==200 && message==""，token 为空视为失败 */
    suspend fun doLogin(st: String): String = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("userToken", st)
            .add("clientid", "")
            .add("dataType", "foreEnd")
            .add("type", "mobile")
            .build()
        val r = decode<JwqeResponse<JwqeLoginData>>(execute("/manage/cas/doLogin", form, token = null))
        val token = r.data?.accessToken.orEmpty()
        if ((r.code == 200 || r.code == 0) && token.isNotEmpty()) return@withContext token
        throw JwqeException(r.message.ifBlank { "评教登录失败" }, isAuthMsg(r.message))
    }

    // ---------- 业务接口 ----------

    /** 评教任务列表 */
    suspend fun getTasks(token: String): List<EvalTask> = withContext(Dispatchers.IO) {
        pagePost("/xspj/xspj/getXspjtask", emptyMap(), token)
    }

    /** 任务下的评教课程 */
    suspend fun getCourses(token: String, taskid: String): List<EvalCourse> = withContext(Dispatchers.IO) {
        pagePost("/xspj/xspj/getXspjStudentCourses", mapOf("taskid" to taskid), token)
    }

    /** 指标体系题目（indexid 原样传任务的逗号串即可，服务端按 pjcoursetype 匹配） */
    suspend fun getQuestions(token: String, indexid: String, pjcoursetype: String): List<EvalQuestion> =
        withContext(Dispatchers.IO) {
            pagePost(
                "/xspj/xspj/getXspjTindexSystem",
                mapOf("indexid" to indexid, "pjcoursetype" to pjcoursetype), token,
            )
        }

    /** 已提交答案（回填已评/暂存），pjjgid 来自课程条目 */
    suspend fun getAnswers(token: String, pjjgid: Long): List<EvalAnswer> = withContext(Dispatchers.IO) {
        pagePost("/xspj/xspj/getevaluateResultId", mapOf("id" to pjjgid.toString()), token)
    }

    /** 提交评教（JSON 数组 body + ?a=1，每元素一门课）；成功判定 code==200 */
    suspend fun submit(token: String, items: List<EvalSubmitItem>) = withContext(Dispatchers.IO) {
        val body = json.encodeToString(items).toRequestBody(jsonMedia)
        val r = decode<JwqeResponse<JsonElement>>(execute("/xspj/xspj/saveStudentComment?a=1", body, token))
        if (r.code != 200 && r.code != 0) {
            throw JwqeException(r.message.ifBlank { "提交失败" }, isAuthMsg(r.message))
        }
    }

    // ---------- HTTP 封装 ----------

    /** form-urlencoded POST 并取出 data.pageData 列表；业务失败抛 [JwqeException] */
    private inline fun <reified T> pagePost(path: String, params: Map<String, String>, token: String): List<T> {
        val form = FormBody.Builder().apply { params.forEach { (k, v) -> add(k, v) } }.build()
        val r = decode<JwqeResponse<JwqePage<T>>>(execute(path, form, token))
        if (r.code != 200 && r.code != 0) {
            throw JwqeException(r.message.ifBlank { "操作失败" }, isAuthMsg(r.message))
        }
        return r.data?.pageData.orEmpty()
    }

    /** 实际执行请求；HTTP 401 按鉴权失效抛出 */
    private fun execute(path: String, body: RequestBody, token: String?): String {
        val request = Request.Builder().url(BASE + path).post(body).apply {
            if (!token.isNullOrEmpty()) header("Authorization", "Bearer $token")
        }.build()
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (resp.code == 401) throw JwqeException("授权失效", isAuth = true)
            if (!resp.isSuccessful) throw JwqeException("HTTP ${resp.code}")
            return text
        }
    }

    /** 解析响应；不可解析（HTML 错误页等）统一转业务异常，不把解析栈/乱码抛给上层 */
    private inline fun <reified T> decode(text: String): T =
        try {
            json.decodeFromString<T>(text)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw JwqeException("服务异常")
        }
}
