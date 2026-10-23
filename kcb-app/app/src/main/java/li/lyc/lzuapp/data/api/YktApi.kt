package li.lyc.lzuapp.data.api

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 兰州大学校园卡（易通 easytong）API 客户端
 *
 * 接口：http://app.lzu.edu.cn:8080/easytong_app/
 * 签名：Sign = MD5( 每个值后都跟 "|"（key 字典序拼接） [+ etToken] )，小写 hex；
 *       Sign/ContentType 不参与签名。body form-urlencoded 带 Time/ContentType 字段，
 *       头 Authorization: etToken。
 * 响应双格式：老接口 XML（EasyTong 根，Code 字符串），新接口 JSON（code 数字）。
 */
class YktApi(private val client: OkHttpClient = defaultClient()) {

    companion object {
        const val BASE = "http://app.lzu.edu.cn:8080/easytong_app"

        /** 不掺 etToken 签名的白名单（url 包含即命中） */
        private val SIGN_NO_TOKEN = listOf(
            "/GetRandomNumber", "/GetRandomNumberNew", "/H5Login",
            "/getYMUserByCode", "/getYMUserByUserID",
        )

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            // 电费链路的 GetPendingItemNew/GetBuildingInfoByAreaNo 服务端实测要约 21s 才返回
            .readTimeout(35, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        private fun md5(s: String): String =
            MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        private fun timeNow(): String =
            SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date())

        /**
         * 一卡通支付密码加密（对齐官方 H5 CryptoJS 实现）：
         * 3DES-CBC/Pkcs7，key = GetRandomNumber(New) 返回的随机数右侧补 '0' 到 24 字节，
         * IV = "12347890"（硬编码），输出标准 Base64。
         * 注意随机数补零后 key 可能退化（K2==K3），SunJCE 不做弱 key 校验，可直接用。
         */
        fun encryptPassword3Des(password: String, random: String): String {
            val keyBytes = (random + "0".repeat(24)).substring(0, 24).toByteArray(Charsets.UTF_8)
            val key = javax.crypto.SecretKeyFactory.getInstance("DESede")
                .generateSecret(javax.crypto.spec.DESedeKeySpec(keyBytes))
            val cipher = javax.crypto.Cipher.getInstance("DESede/CBC/PKCS5Padding")
            cipher.init(
                javax.crypto.Cipher.ENCRYPT_MODE, key,
                javax.crypto.spec.IvParameterSpec("12347890".toByteArray(Charsets.UTF_8)),
            )
            return java.util.Base64.getEncoder()
                .encodeToString(cipher.doFinal(password.toByteArray(Charsets.UTF_8)))
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** 校园卡业务异常；[isAuth] = 签名/登录态问题（可重换 etToken 后重试） */
    class YktException(message: String, val isAuth: Boolean = false) : Exception(message)

    // ---------- 响应模型 ----------

    /** 统一响应：code 归一化为 Int（XML 的 "1" 与 JSON 的 1 都映射成 1） */
    data class YktResponse(
        val code: Int,
        val msg: String,
        /** XML Table 行（每行一个 map）；单行也为 list */
        val table: List<Map<String, String>> = emptyList(),
        /** XML Row 分组（账单按月）：Month -> 行列表 */
        val rows: List<Pair<String, List<Map<String, String>>>> = emptyList(),
        /** 顶层标量字段（XML 子节点或 JSON 顶层） */
        val fields: Map<String, String> = emptyMap(),
        /** JSON 响应的原始顶层对象（电费等 New 接口的 list/dormList 嵌套数组从这里取） */
        val json: JsonObject? = null,
    ) {
        val ok: Boolean get() = code == 1
        fun field(key: String): String? = fields[key]?.takeIf { it.isNotBlank() }
    }

    // ---------- 请求封装 ----------

    /**
     * 签名 POST。[etToken] 为空或非白名单端点时 Sign 不掺 token。
     * 业务失败（code != 1）抛 [YktException]。
     */
    suspend fun call(
        path: String,
        params: Map<String, String> = emptyMap(),
        etToken: String? = null,
    ): YktResponse = withContext(Dispatchers.IO) {
        val r = execute(path, params, etToken)
        if (!r.ok) {
            // 服务端把 etToken 称作 access_token：40004=无效，另有 "access_token已过期" 文案。
            // 这些都按鉴权失败处理，由仓库层清缓存重换 etToken 后重试
            val auth = r.code == 3 || r.code == 40004 || r.msg.contains("未登录") ||
                r.msg.contains("签名") || r.msg.contains("access_token")
            throw YktException(r.msg.ifBlank { "操作失败" }, auth)
        }
        r
    }

    /**
     * 同 [call]，但业务 code != 1 时不抛异常、原样返回响应。
     * 供缴费下单（PayFeeItemNew code: 1=成功 2=密码错误 其它=失败）等需要区分业务码的写操作使用。
     */
    suspend fun callRaw(
        path: String,
        params: Map<String, String> = emptyMap(),
        etToken: String? = null,
    ): YktResponse = withContext(Dispatchers.IO) { execute(path, params, etToken) }

    /** 实际执行：签名 + 请求 + 解析；HTTP 层失败/响应不可解析抛 [YktException] */
    private fun execute(
        path: String,
        params: Map<String, String>,
        etToken: String?,
    ): YktResponse {
        val p = LinkedHashMap(params)
        p.putIfAbsent("Time", timeNow())
        val signBase = p.keys.sorted().joinToString("") { "${p[it]}|" }
        val useToken = !etToken.isNullOrEmpty() && SIGN_NO_TOKEN.none { path.contains(it) }
        p["Sign"] = md5(if (useToken) signBase + etToken else signBase)
        p["ContentType"] = "application/json"

        val body = FormBody.Builder().apply { p.forEach { (k, v) -> add(k, v) } }.build()
        val request = Request.Builder().url(BASE + path).post(body).apply {
            if (!etToken.isNullOrEmpty()) header("Authorization", etToken)
        }.build()

        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw YktException("HTTP ${resp.code}")
            // 200 但返回 HTML 错误页等无法解析的情况：统一转业务异常，不把解析栈/乱码抛给上层
            return try {
                parse(text.trim())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                throw YktException("服务异常")
            }
        }
    }

    /** 解析响应：XML（EasyTong 根）或 JSON */
    private fun parse(text: String): YktResponse =
        if (text.startsWith("<?xml") || text.startsWith("<EasyTong")) parseXml(text) else parseJson(text)

    private fun parseJson(text: String): YktResponse {
        val obj = json.decodeFromString<JsonObject>(text)
        fun str(key: String): String? =
            (obj[key] as? JsonPrimitive)?.contentOrNull
        val code = str("code")?.toIntOrNull() ?: str("Code")?.toIntOrNull() ?: -1
        val msg = str("msg") ?: str("Msg") ?: ""
        val fields = obj.entries.mapNotNull { (k, v) ->
            (v as? JsonPrimitive)?.contentOrNull?.let { k to it }
        }.toMap()
        return YktResponse(code = code, msg = msg, fields = fields, json = obj)
    }

    /**
     * XML 解析：
     * - 顶层叶子节点 -> fields
     * - 顶层 <Table> -> table 行
     * - 顶层 <Row>（账单按月分组，内含 Month + 多个 Table）-> rows
     * 注意：<Table> 内可能嵌套 <Row>（如 GetWalletMoney 的 isSetDiscount），按嵌套跳过。
     */
    private fun parseXml(text: String): YktResponse {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(StringReader(text))
        }
        val fields = LinkedHashMap<String, String>()
        val table = mutableListOf<Map<String, String>>()
        val rows = mutableListOf<Pair<String, List<Map<String, String>>>>()
        var code = -1
        var msg = ""

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "Table" -> table.add(readElement(parser, "Table"))
                    "Row" -> {
                        // Row: <Month>yyyyMM</Month> + <Table>×N，消耗到 </Row>
                        var month = ""
                        val monthRows = mutableListOf<Map<String, String>>()
                        var closed = false
                        while (!closed) {
                            when (parser.next()) {
                                XmlPullParser.START_TAG -> when (parser.name) {
                                    "Month" -> month = parser.nextText().orEmpty().trim()
                                    "Table" -> monthRows.add(readElement(parser, "Table"))
                                    else -> skipElement(parser)
                                }
                                XmlPullParser.END_TAG -> if (parser.name == "Row") closed = true
                                XmlPullParser.END_DOCUMENT -> closed = true
                            }
                        }
                        rows.add(month to monthRows)
                    }
                    else -> {
                        // 顶层叶子字段
                        val name = parser.name
                        if (name != "EasyTong") {
                            val value = readLeafText(parser, name)
                            fields[name] = value
                            when (name) {
                                "Code" -> code = value.toIntOrNull() ?: -1
                                "Msg" -> msg = value
                            }
                        }
                    }
                }
            }
            event = parser.next()
        }
        return YktResponse(code = code, msg = msg, table = table, rows = rows, fields = fields)
    }

    /** 读取元素文本（当前在 START_TAG），消耗到其 END_TAG；嵌套子元素内容忽略 */
    private fun readLeafText(parser: XmlPullParser, name: String): String {
        val sb = StringBuilder()
        var nested = 0
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> nested++
                XmlPullParser.TEXT -> if (nested == 0) sb.append(parser.text)
                XmlPullParser.END_TAG -> {
                    if (nested == 0 && parser.name == name) break
                    if (nested > 0) nested--
                }
                XmlPullParser.END_DOCUMENT -> break
            }
        }
        return sb.toString().trim()
    }

    /** 跳过当前元素（START_TAG 位置），消耗到对应 END_TAG */
    private fun skipElement(parser: XmlPullParser) {
        var nested = 1
        while (nested > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> nested++
                XmlPullParser.END_TAG -> nested--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    /** 读取当前元素（START_TAG 位置）的所有 子标签:文本，消耗到对应 END_TAG */
    private fun readElement(parser: XmlPullParser, rootName: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> map[parser.name] = readLeafText(parser, parser.name)
                XmlPullParser.END_TAG -> if (parser.name == rootName) return map
                XmlPullParser.END_DOCUMENT -> return map
            }
        }
    }
}
