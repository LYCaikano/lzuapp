package li.lyc.lzuapp.data.api

import li.lyc.lzuapp.data.api.model.CoremailResponse
import li.lyc.lzuapp.data.api.model.MailAttrs
import li.lyc.lzuapp.data.api.model.MailDetail
import li.lyc.lzuapp.data.api.model.MailFolder
import li.lyc.lzuapp.data.api.model.MailFolderStats
import li.lyc.lzuapp.data.api.model.MailMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Coremail 手机端（hxphone）JSON API 客户端。
 * 端点：POST https://mail.lzu.edu.cn/coremail/s/?func=<func>&sid=<sid>
 * 只需 sid（从 getJumpMenu 跳转 URL 解析），不依赖 Cookie。
 * 响应 {"code":"S_OK","var":...}；code 以 "FA_" 开头多为会话失效/权限问题。
 */
class CoremailApi(private val client: OkHttpClient = LzuApi.defaultClient()) {

    companion object {
        const val BASE = "https://mail.lzu.edu.cn/coremail/s/"
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val mediaType = "text/x-json".toMediaType()

    /** 账号信息（邮箱地址、姓名） */
    suspend fun getAttrs(sid: String): Result<MailAttrs> =
        call("user:getAttrs", """{"attrIds":["email","true_name","pref_quota"]}""", sid)

    /** 全部文件夹 */
    suspend fun getAllFolders(sid: String): Result<List<MailFolder>> =
        call("mbox:getAllFolders", "{}", sid)

    /** 各文件夹邮件数/未读数，key 为 fid 字符串 */
    suspend fun getFolderStats(sid: String, fids: List<Int>): Result<Map<String, MailFolderStats>> =
        call(
            "mbox:getFolderStats",
            """{"fids":[${fids.joinToString(",")}],"stats":["unread"]}""",
            sid,
        )

    /** 邮件列表（start 起 limit 条，按日期倒序） */
    suspend fun listMessages(sid: String, fid: Int, start: Int, limit: Int): Result<List<MailMessage>> =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("fid", fid); put("start", start); put("limit", limit)
                put("order", "date"); put("desc", true)
            }.toString()
            call("mbox:listMessages", body, sid)
        }

    /** 读取邮件详情（markRead 置已读）。返回 html/text 正文 + 附件列表 */
    suspend fun readMessage(sid: String, id: String, markRead: Boolean = true): Result<MailDetail> =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("id", id)
                put("mode", "html")
                put("filterHeads", true)
                put("filterImages", false)
                put("autoName", true)
                put("markRead", markRead)
                put("supportTNEF", true)
                putJsonObject("returnHeaders") { put("Sender", "A") }
            }.toString()
            call("mbox:readMessage", body, sid)
        }

    /**
     * 发送邮件（官方两步：compose continue 拿 composeId -> compose deliver）。
     * 已实测：收件人是字符串数组；发送成功自动存入「已发送」。
     */
    suspend fun sendMail(sid: String, to: List<String>, subject: String, content: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val composeId = call<String>("mbox:compose", """{"action":"continue"}""", sid).getOrThrow()
                val toJson = to.joinToString(",") { "\"${it.replace("\"", "")}\"" }
                val attrs = buildJsonObject {
                    put("id", composeId)
                    put("subject", subject)
                    put("content", content)
                    put("isHtml", true)
                    put("saveSentCopy", true)
                    put("charset", "gbk")
                }
                // attrs 里 to/cc/bcc/attachments 需要数组，buildJsonObject 拼数组啰嗦，直接字符串模板
                val attrsRaw = attrs.toString().removeSuffix("}") +
                    ""","to":[$toJson],"cc":[],"bcc":[],"attachments":[]}"""
                val body = """{"id":"$composeId","attrs":$attrsRaw,""" +
                    """"returnInfo":false,"autosaveHitCounter":true,"action":"deliver"}"""
                callUnit("mbox:compose", body, sid).getOrThrow()
            }
        }

    /** 移入已删除（fid=4；官方对回收站内邮件用 deleteMessages 彻底删除，这里只做移入） */
    suspend fun moveToTrash(sid: String, id: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val body = """{"ids":["$id"],"attrs":{"fid":4}}"""
            callUnit("mbox:updateMessageInfos", body, sid)
        }

    /** 不读 var 的调用（deliver/updateMessageInfos 响应可能无 var 字段） */
    private suspend fun callUnit(func: String, body: String, sid: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "$BASE?func=$func&sid=$sid"
                val request = Request.Builder()
                    .url(url)
                    .post(body.toRequestBody(mediaType))
                    .header("Accept", "text/x-json")
                    .header("X-CM-SERVICE", "PHONE")
                    .build()
                client.newCall(request).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw ApiException("HTTP ${resp.code}")
                    val code = Json.parseToJsonElement(text)
                        .let { (it as? kotlinx.serialization.json.JsonObject)?.get("code") }
                        ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                        ?: throw ApiException("$func: bad response")
                    if (code != "S_OK" && code != "S_PARTIAL_OK") throw ApiException("$code: $func")
                }
            }
        }

    private suspend inline fun <reified T> call(
        func: String,
        body: String,
        sid: String,
    ): Result<T> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$BASE?func=$func&sid=$sid"
            val request = Request.Builder()
                .url(url)
                .post(body.toRequestBody(mediaType))
                .header("Accept", "text/x-json")
                .header("X-CM-SERVICE", "PHONE")
                .build()
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw ApiException("HTTP ${resp.code}")
                val r = json.decodeFromString<CoremailResponse<T>>(text)
                if (r.code != "S_OK" && r.code != "S_PARTIAL_OK") {
                    throw ApiException("${r.code}: $func")
                }
                r.result ?: throw ApiException("$func: empty var")
            }
        }
    }
}
