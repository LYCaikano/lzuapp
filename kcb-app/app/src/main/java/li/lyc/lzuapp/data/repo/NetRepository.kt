package li.lyc.lzuapp.data.repo

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.delay
import li.lyc.lzuapp.data.AuthStore
import li.lyc.lzuapp.data.api.LzuSelfService
import li.lyc.lzuapp.data.api.SrunPortal
import java.net.HttpURLConnection
import java.net.URL

/**
 * 校园网（深澜 Portal + 自助服务）仓库。
 * 门户是内网明文 HTTP（10.10.0.166），所有门户请求绑定 Wi-Fi Network 发送；
 * 自助服务（login.lzu.edu.cn:8800）走默认网络。
 */
class NetRepository(private val context: Context, private val authStore: AuthStore) {

    sealed interface NetResult<out T> {
        data class Ok<T>(val data: T) : NetResult<T>
        data class Err(val message: String) : NetResult<Nothing>
    }

    /** 账号类型（门户"账号认证"域下拉框）：校园网账号无后缀，eLearning 加 @study */
    enum class AccountType(val label: String, val domain: String, val storage: String) {
        CAMPUS("校园网账号", "", "campus"),
        ELEARNING("eLearning 账号", "@study", "study");

        companion object {
            fun fromStorage(s: String): AccountType = entries.firstOrNull { it.storage == s } ?: CAMPUS
        }
    }

    private val selfService = LzuSelfService()

    /** 当前 Wi-Fi Network（无 Wi-Fi 时门户不可达） */
    fun wifiNetwork(): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    /** 门户请求绑定 Wi-Fi（未认证时系统默认路由会把流量切蜂窝，导致超时） */
    private fun portalOn(network: Network?): SrunPortal = SrunPortal { url ->
        val u = URL(url)
        (network?.openConnection(u) ?: u.openConnection()) as HttpURLConnection
    }

    private fun fail(e: Exception): NetResult.Err = NetResult.Err(
        when (e) {
            is java.net.SocketTimeoutException, is java.net.UnknownHostException,
            is java.net.ConnectException, -> "不在校园网环境或网络异常"
            is SrunPortal.PortalException -> e.message ?: "门户响应异常"
            is LzuSelfService.SelfServiceException -> e.message ?: "自助服务异常"
            else -> "网络异常，请稍后重试"
        },
    )

    /** 查询当前设备在线状态 */
    suspend fun status(): NetResult<SrunPortal.NetStatus> = try {
        NetResult.Ok(portalOn(wifiNetwork()).status())
    } catch (e: Exception) {
        fail(e)
    }

    /** 登录校园网（[username] 不含域后缀，由账号类型拼接） */
    suspend fun login(username: String, password: String, type: AccountType): NetResult<String> = try {
        val r = portalOn(wifiNetwork()).login(username + type.domain, password)
        when {
            r.ok || r.sucMsg != null -> NetResult.Ok("登录成功")
            r.alreadyOnline -> NetResult.Ok("该设备已在线")
            else -> NetResult.Err("登录失败：${r.ecode ?: r.error ?: ""} ${r.errorMsg ?: ""}".trim())
        }
    } catch (e: Exception) {
        fail(e)
    }

    /** 注销当前 IP 会话（DM 注销 + 解绑无感知 MAC） */
    suspend fun logout(username: String): NetResult<String> = try {
        val r = portalOn(wifiNetwork()).logout(username)
        if (r.ok) NetResult.Ok("已注销")
        else NetResult.Err("注销失败：${r.errorMsg ?: r.error ?: "未知错误"}")
    } catch (e: Exception) {
        fail(e)
    }

    /** 在线设备列表（自助服务 SSO，用指定账户凭据） */
    suspend fun onlineDevices(username: String, password: String): NetResult<List<LzuSelfService.NetDevice>> {
        if (username.isBlank() || password.isBlank()) return NetResult.Err("请先填写上网账号密码")
        return try {
            NetResult.Ok(selfService.onlineDevices(username, password))
        } catch (e: Exception) {
            fail(e)
        }
    }

    /** 强制下线指定设备（用指定账户凭据） */
    suspend fun kickDevice(username: String, password: String, device: LzuSelfService.NetDevice): NetResult<Unit> {
        if (username.isBlank() || password.isBlank()) return NetResult.Err("请先填写上网账号密码")
        return try {
            if (selfService.kickDevice(username, password, device)) NetResult.Ok(Unit)
            else NetResult.Err("下线失败，请重试")
        } catch (e: Exception) {
            fail(e)
        }
    }

    /**
     * 前台服务用：掉线则自动重登（登录返回成功后复核在线，防 MacAuth 竞态假成功）。
     * 内部串行化；返回一行通知文案。
     */
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

    suspend fun ensureOnline(network: Network?): String {
        val c = authStore.current()
        if (!c.netAutoLogin) return "自动登录已关闭"
        val acc = c.activeNetAccount ?: return "未配置上网账号"
        if (acc.password.isBlank()) return "未配置上网账号"
        if (!busy.compareAndSet(false, true)) return "已有登录任务进行中"
        return try {
            val portal = portalOn(network)
            val st = portal.status()
            if (st.online) {
                "已在线：${st.onlineIp}"
            } else {
                val type = AccountType.fromStorage(acc.type)
                var lastMsg = "未知错误"
                for (attempt in 0 until 3) {
                    val r = portal.login(acc.username + type.domain, acc.password)
                    when {
                        r.alreadyOnline -> return "该 IP 已在线"
                        r.ok || r.sucMsg != null -> {
                            delay(2_000)
                            val v = runCatching { portal.status() }.getOrNull()
                            if (v?.online == true) return "已自动登录：${v.onlineIp}"
                            lastMsg = "登录返回成功但复核未在线"
                        }
                        else -> lastMsg = "登录失败：${r.ecode ?: ""} ${r.errorMsg ?: ""}".trim()
                    }
                    if (attempt < 2) delay((attempt + 1) * 2_000L)
                }
                lastMsg
            }
        } catch (e: Exception) {
            fail(e).message
        } finally {
            busy.set(false)
        }
    }
}
