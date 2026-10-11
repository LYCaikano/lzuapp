package li.lyc.lzuapp.net

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.runBlocking
import li.lyc.lzuapp.KcbApplication

/**
 * 校园网自动登录的注册入口：用 PendingIntent 版 NetworkCallback 监听 Wi-Fi 事件。
 * 回调由系统代持（App 进程不在也能收到广播），无常驻服务、零轮询，最省电。
 */
object NetAutoLogin {

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, NetCallbackReceiver::class.java).setAction(NetCallbackReceiver.ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** 按开关注册/注销 Wi-Fi 事件回调（幂等） */
    fun syncState(context: Context, enabled: Boolean) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val pi = pendingIntent(context)
        runCatching { cm.unregisterNetworkCallback(pi) } // 先注销，防重复注册
        if (enabled) {
            NetAuthService.createChannel(context)
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            runCatching { cm.registerNetworkCallback(req, pi) }
        }
    }
}

/**
 * Wi-Fi 事件接收器：预检（已配置 + 防抖 + 在校园网且未在线）通过才拉起认证服务。
 * 手动断开 Wi-Fi / 非校园网 / 已在线 都静默返回，不弹任何通知。
 */
class NetCallbackReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION = "li.lyc.lzuapp.action.CAMPUS_NETWORK_EVENT"
        private const val DEBOUNCE_MS = 30_000L

        @Volatile
        private var lastAttemptAt = 0L
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val container = (context.applicationContext as? KcbApplication)?.container ?: return
        val pending = goAsync()
        Thread {
            try {
                val c = runBlocking { container.authStore.current() }
                if (!c.netAutoLogin || c.activeNetAccount == null) return@Thread
                val now = System.currentTimeMillis()
                if (now - lastAttemptAt < DEBOUNCE_MS) return@Thread
                val repo = container.netRepository
                if (repo.wifiNetwork() == null) return@Thread // 断开事件/无 Wi-Fi
                when (val r = runBlocking { repo.status() }) {
                    is li.lyc.lzuapp.data.repo.NetRepository.NetResult.Ok ->
                        if (r.data.online) return@Thread // 已在线，无需动作
                    // 门户不可达（非校园网）：静默
                    is li.lyc.lzuapp.data.repo.NetRepository.NetResult.Err -> return@Thread
                }
                lastAttemptAt = now
                NetAuthService.start(context)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
