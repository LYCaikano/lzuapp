package li.lyc.lzuapp.data.api

import android.annotation.SuppressLint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * 深澜自助服务平台（https://login.lzu.edu.cn:8800，Yii/PHP）客户端。
 * 逆向自 Portal.js toSelfService 与 lzunet/lzuself.py，接口文档见 ~/lzunet/README.md。
 *
 * - SSO 免验证码：GET /site/sso?data=base64("zh-CN:{用户名}:{md5(密码)}")，Set-Cookie 拿会话
 * - 在线设备：GET /home，解析在线信息表（rad_online_id / IP / 上线时间 / 产品 / user_mac）
 * - 强制下线：POST /home/delete?id={rad_online_id}&user_mac={mac}，表单带 csrf token
 *
 * 注意：该站点是校内自签证书，TLS 校验放宽仅限此 host 的专用连接（不影响 App 其它请求）。
 */
class LzuSelfService {

    class SelfServiceException(message: String) : Exception(message)

    /** 在线设备（自助平台 /home 在线信息表一行） */
    data class NetDevice(
        val id: String,
        val username: String,
        val ip: String,
        val since: String,
        val product: String,
        val mac: String,
    )

    /** SSO 会话：cookie + csrf token（/home 拉取后填充） */
    private class Session(var cookie: String, var csrfParam: String = "_csrf-8800", var csrfToken: String = "")

    // 校内自签证书：仅本类连接使用，host 仍锁定 login.lzu.edu.cn
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }
    private val sslContext: SSLContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(trustAll), java.security.SecureRandom())
    }
    @SuppressLint("BadHostnameVerifier")
    private val hostVerifier = HostnameVerifier { hostname, _ ->
        hostname.equals(HOST, ignoreCase = true)
    }

    private fun connect(url: String, cookie: String? = null): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        if (conn is HttpsURLConnection) {
            conn.sslSocketFactory = sslContext.socketFactory
            conn.hostnameVerifier = hostVerifier
        }
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.useCaches = false
        conn.instanceFollowRedirects = false // 302 由调用方处理（SSO/下线都是重定向语义）
        if (!cookie.isNullOrEmpty()) conn.setRequestProperty("Cookie", cookie)
        return conn
    }

    private fun md5Hex(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** SSO 登录拿会话 Cookie。账号密码错误会 302 回登录页（无 Set-Cookie）。 */
    private fun ssoLogin(username: String, password: String): Session {
        val data = android.util.Base64.encodeToString(
            "zh-CN:$username:${md5Hex(password)}".toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP,
        )
        val conn = connect("$BASE/site/sso?data=${URLEncoder.encode(data, "UTF-8")}")
        try {
            conn.responseCode // 触发请求
            val cookie = conn.headerFields.entries
                .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
                .flatMap { it.value }
                .mapNotNull { it.substringBefore(';').takeIf(String::isNotBlank) }
                .firstOrNull { it.startsWith("PHPSESSID") }
                ?: throw SelfServiceException("自助服务登录失败（请检查上网账号密码）")
            return Session(cookie)
        } finally {
            conn.disconnect()
        }
    }

    private fun httpGet(path: String, s: Session): String {
        val conn = connect(BASE + path, s.cookie)
        try {
            if (conn.responseCode !in 200..399) throw SelfServiceException("自助服务异常（HTTP ${conn.responseCode}）")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** 拉取 /home：刷新 csrf token 并解析在线设备列表 */
    private fun fetchHome(s: Session): Pair<String, List<NetDevice>> {
        val page = httpGet("/home", s)
        Regex("""<meta name="csrf-param" content="([^"]+)"""").find(page)
            ?.let { s.csrfParam = it.groupValues[1] }
        s.csrfToken = Regex("""<meta name="csrf-token" content="([^"]+)"""")
            .find(page)?.groupValues?.get(1)
            ?: throw SelfServiceException("自助服务页面异常")
        return page to parseDevices(page)
    }

    /** 解析在线设备表：data-key=rad_online_id，列 0=用户名 1=IP 3=上线时间 10=产品，下线链接带 user_mac */
    private fun parseDevices(page: String): List<NetDevice> {
        val rowRe = Regex(
            """data-key="(\d+)">[\s\S]*?data-col-seq="0">([^<]*)</td>[\s\S]*?data-col-seq="1">([^<]*)</td>[\s\S]*?data-col-seq="3">([^<]*)</td>[\s\S]*?data-col-seq="10">([^<]*)</td>[\s\S]*?/home/delete\?id=\d+&(?:amp;)?user_mac=([^"'\s]+)""",
        )
        return rowRe.findAll(page).map { m ->
            NetDevice(
                id = m.groupValues[1],
                username = m.groupValues[2].trim(),
                ip = m.groupValues[3].trim(),
                since = m.groupValues[4].trim(),
                product = m.groupValues[5].trim(),
                mac = java.net.URLDecoder.decode(m.groupValues[6], "UTF-8"),
            )
        }.toList()
    }

    /** 查询在线设备列表（自动完成 SSO 登录） */
    suspend fun onlineDevices(username: String, password: String): List<NetDevice> =
        withContext(Dispatchers.IO) {
            val s = ssoLogin(username, password)
            fetchHome(s).second
        }

    /** 强制下线指定设备。成功返回 true（Yii 删除后 302 回 /home）。 */
    suspend fun kickDevice(username: String, password: String, device: NetDevice): Boolean =
        withContext(Dispatchers.IO) {
            val s = ssoLogin(username, password)
            fetchHome(s) // 刷新 csrf token
            val url = "$BASE/home/delete?id=${device.id}&user_mac=${URLEncoder.encode(device.mac, "UTF-8")}"
            val conn = connect(url, s.cookie)
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                val body = "${s.csrfParam}=${URLEncoder.encode(s.csrfToken, "UTF-8")}"
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                conn.responseCode in 200..399
            } finally {
                conn.disconnect()
            }
        }

    companion object {
        private const val HOST = "login.lzu.edu.cn"
        private const val BASE = "https://$HOST:8800"
    }
}
