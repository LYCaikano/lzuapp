package li.lyc.lzuapp.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 兰州大学校园网深澜 Srun Portal 协议（ac_id=2，http://10.10.0.166）。
 *
 * - get_challenge 取 token（60 秒有效）与 client_ip
 * - 登录：{MD5}hmac-md5 + {SRBX1}XXTEA(info) + sha1 chksum
 * - 注销必须走 rad_user_dm（unbind=1）：本校开了 MacAuth 无感知认证，
 *   普通 srun_portal?action=logout 返回 ok 也会被秒级拉回。
 *
 * 注意：门户是内网明文 HTTP，请求必须绑定 Wi-Fi Network（[opener] 注入），
 * 否则未认证时系统会把流量切到蜂窝网络导致超时。
 */
class SrunPortal(
    private val baseUrl: String = "http://10.10.0.166",
    private val acId: String = "2",
    private val opener: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection },
) {

    // ---------- 加解密 ----------

    private object SrunCrypto {
        private const val ALPHA = "LVoJPiCN2R8G90yg+hmFHuacZ1OWMnrsSTXkYpUq/3dlbfKwv6xztjI7DeBE45QA"
        private const val B64STD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

        fun hmacMd5Hex(key: String, msg: String): String {
            val mac = Mac.getInstance("HmacMD5")
            mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacMD5"))
            return mac.doFinal(msg.toByteArray(Charsets.UTF_8)).toHex()
        }

        fun sha1Hex(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8)).toHex()

        /** 用户信息使用 XXTEA 变体编码（标准 delta，32 位掩码，长度附尾）。 */
        fun xxteaEncode(data: String, key: String): ByteArray {
            if (data.isEmpty()) return ByteArray(0)
            val v = strToLongs(data, true)
            val k = strToLongs(key, false)
            while (k.size < 4) k.add(0)
            val n = v.size - 1
            var z = v[n]
            val delta = 0x9E3779B9.toInt()
            var q = 6 + 52 / (n + 1)
            var d = 0
            while (q > 0) {
                q--
                d += delta
                val e = (d ushr 2) and 3
                for (p in 0 until n) {
                    val y = v[p + 1]
                    var m = (z ushr 5) xor (y shl 2)
                    m += ((y ushr 3) xor (z shl 4)) xor (d xor y)
                    m += k[(p and 3) xor e] xor z
                    z = v[p] + m
                    v[p] = z
                }
                val y = v[0]
                var m = (z ushr 5) xor (y shl 2)
                m += ((y ushr 3) xor (z shl 4)) xor (d xor y)
                m += k[(n and 3) xor e] xor z
                z = v[n] + m
                v[n] = z
            }
            val out = ByteArray(v.size * 4)
            for (i in v.indices) {
                val w = v[i]
                out[i * 4] = w.toByte()
                out[i * 4 + 1] = (w ushr 8).toByte()
                out[i * 4 + 2] = (w ushr 16).toByte()
                out[i * 4 + 3] = (w ushr 24).toByte()
            }
            return out
        }

        private fun strToLongs(s: String, appendLen: Boolean): MutableList<Int> {
            val c = s.length
            val v = ArrayList<Int>(c / 4 + 2)
            var i = 0
            while (i < c) {
                val b0 = s[i].code
                val b1 = if (i + 1 < c) s[i + 1].code else 0
                val b2 = if (i + 2 < c) s[i + 2].code else 0
                val b3 = if (i + 3 < c) s[i + 3].code else 0
                v.add(b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24))
                i += 4
            }
            if (appendLen) v.add(c)
            return v
        }

        /** 标准 Base64 编码后替换为自定义字母表。 */
        fun b64Custom(raw: ByteArray): String {
            val sb = StringBuilder()
            var i = 0
            while (i + 3 <= raw.size) {
                val n = (raw[i].toInt() and 0xFF shl 16) or (raw[i + 1].toInt() and 0xFF shl 8) or (raw[i + 2].toInt() and 0xFF)
                sb.append(B64STD[n ushr 18]).append(B64STD[(n ushr 12) and 63])
                    .append(B64STD[(n ushr 6) and 63]).append(B64STD[n and 63])
                i += 3
            }
            when (raw.size - i) {
                1 -> {
                    val n = raw[i].toInt() and 0xFF shl 16
                    sb.append(B64STD[n ushr 18]).append(B64STD[(n ushr 12) and 63]).append("==")
                }
                2 -> {
                    val n = (raw[i].toInt() and 0xFF shl 16) or (raw[i + 1].toInt() and 0xFF shl 8)
                    sb.append(B64STD[n ushr 18]).append(B64STD[(n ushr 12) and 63]).append(B64STD[(n ushr 6) and 63]).append('=')
                }
            }
            return sb.toString().map { c -> if (c == '=') '=' else ALPHA[B64STD.indexOf(c)] }.joinToString("")
        }

        /** 转义 JSON 字符串并保留非 ASCII 字符。 */
        private fun jsonEscape(s: String): String {
            val sb = StringBuilder(s.length + 8)
            for (ch in s) {
                when (ch) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\b' -> sb.append("\\b")
                    '\u000C' -> sb.append("\\f")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
                }
            }
            return sb.toString()
        }

        fun encodeInfo(username: String, password: String, ip: String, acId: String, token: String): String {
            val payload = "{\"username\":\"${jsonEscape(username)}\",\"password\":\"${jsonEscape(password)}\"," +
                "\"ip\":\"${jsonEscape(ip)}\",\"acid\":\"${jsonEscape(acId)}\",\"enc_ver\":\"srun_bx1\"}"
            return "{SRBX1}" + b64Custom(xxteaEncode(payload, token))
        }
    }

    // ---------- 请求封装 ----------

    private val json = Json { ignoreUnknownKeys = true }

    class PortalException(message: String) : Exception(message)

    /** 门户扁平 JSON 响应；ok=error=="ok"；alreadyOnline=该 IP 已在线 */
    data class PortalResult(val raw: JsonObject) {
        val error: String? get() = raw.str("error")
        val errorMsg: String? get() = raw.str("error_msg")
        val ecode: String? get() = raw.str("ecode")
        val sucMsg: String? get() = raw.str("suc_msg")
        val ok: Boolean get() = error == "ok"
        val alreadyOnline: Boolean get() = errorMsg == "ip_already_online_error"
        fun str(key: String): String? = raw.str(key)

        private fun JsonObject.str(k: String): String? =
            (this[k] as? JsonPrimitive)?.contentOrNull
    }

    /** 查询在线状态响应（rad_user_info） */
    data class NetStatus(
        val online: Boolean,
        val username: String = "",
        val domain: String = "",
        val onlineIp: String = "",
        val usedBytes: Long = 0L,
        val errorMsg: String = "",
    )

    private fun get(path: String, params: Map<String, String>): JsonObject {
        val q = LinkedHashMap(params)
        q["callback"] = "jQuery"
        q["_"] = System.currentTimeMillis().toString()
        val url = baseUrl + path + "?" + q.entries.joinToString("&") {
            "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
        val conn = opener(url)
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.useCaches = false
            val text = conn.inputStream.bufferedReader().use { it.readText() }.trim()
            // 剥 JSONP 外壳 jQuery(...)
            val stripped = if (text.startsWith("jQuery(") && text.endsWith(")"))
                text.substring("jQuery(".length, text.length - 1) else text
            return json.decodeFromString(JsonObject.serializer(), stripped)
        } finally {
            conn.disconnect()
        }
    }

    /** 查询当前 IP 在线状态（未在线 error=not_online_error） */
    suspend fun status(): NetStatus = withContext(Dispatchers.IO) {
        val r = get("/cgi-bin/rad_user_info", emptyMap())
        if ((r["error"] as? JsonPrimitive)?.contentOrNull == "ok") {
            NetStatus(
                online = true,
                username = r.str("user_name").orEmpty(),
                domain = r.str("domain").orEmpty(),
                onlineIp = r.str("online_ip").orEmpty(),
                usedBytes = r.str("sum_bytes")?.toLongOrNull() ?: 0L,
            )
        } else {
            NetStatus(online = false, errorMsg = r.str("error_msg").orEmpty())
        }
    }

    /** 登录。[username] 已含域后缀（校园网账号无后缀，eLearning 加 @study）。 */
    suspend fun login(username: String, password: String): PortalResult = withContext(Dispatchers.IO) {
        val ch = get("/cgi-bin/get_challenge", mapOf("username" to username, "ip" to ""))
        if ((ch["error"] as? JsonPrimitive)?.contentOrNull != "ok") {
            return@withContext PortalResult(ch)
        }
        val token = (ch["challenge"] as? JsonPrimitive)?.contentOrNull
            ?: throw PortalException("门户响应缺少 challenge")
        val ip = (ch["client_ip"] as? JsonPrimitive)?.contentOrNull
            ?: (ch["online_ip"] as? JsonPrimitive)?.contentOrNull.orEmpty()

        val hmd5 = SrunCrypto.hmacMd5Hex(token, password)
        val info = SrunCrypto.encodeInfo(username, password, ip, acId, token)
        val n = "200"
        val type = "1"
        val chksum = SrunCrypto.sha1Hex(
            token + username + token + hmd5 + token + acId + token + ip + token + n + token + type + token + info
        )
        PortalResult(
            get(
                "/cgi-bin/srun_portal", linkedMapOf(
                    "action" to "login",
                    "username" to username,
                    "password" to "{MD5}$hmd5",
                    "os" to "android",
                    "name" to "android",
                    "double_stack" to "0",
                    "chksum" to chksum,
                    "info" to info,
                    "ac_id" to acId,
                    "ip" to ip,
                    "n" to n,
                    "type" to type,
                ),
            ),
        )
    }

    /**
     * DM 注销（MacAuth 无感知环境必须走 rad_user_dm，unbind=1 解绑 MAC）。
     * 用户名使用不带域后缀的裸用户名。
     */
    suspend fun logout(fallbackUsername: String): PortalResult = withContext(Dispatchers.IO) {
        val info = get("/cgi-bin/rad_user_info", emptyMap())
        if ((info["error"] as? JsonPrimitive)?.contentOrNull != "ok") {
            return@withContext PortalResult(info)
        }
        val ip = (info["online_ip"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val username = (info["user_name"] as? JsonPrimitive)?.contentOrNull ?: fallbackUsername
        val ts = (System.currentTimeMillis() / 1000).toString()
        val unbind = "1"
        val sign = SrunCrypto.sha1Hex(ts + username + ip + unbind + ts)
        PortalResult(
            get(
                "/cgi-bin/rad_user_dm", linkedMapOf(
                    "ip" to ip, "username" to username, "time" to ts, "unbind" to unbind, "sign" to sign,
                ),
            ),
        )
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
}
