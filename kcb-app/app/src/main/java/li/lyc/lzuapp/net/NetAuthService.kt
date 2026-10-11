package li.lyc.lzuapp.net

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import li.lyc.lzuapp.KcbApplication
import li.lyc.lzuapp.MainActivity
import li.lyc.lzuapp.R

/**
 * 校园网自动登录短时前台服务：仅在一次认证期间存活（几秒），
 * 认证完即停——平时无常驻通知、无常驻进程。
 *
 * 触发链：NetAutoLogin（PendingIntent 网络回调，系统代持）→ NetCallbackReceiver
 * 预检（校园网可达且未在线）→ startForegroundService 拉起本服务。
 * 通知只在三个时机出现：正在登录 / 登录成功（10s 自动消失）/ 登录失败（可划掉）。
 * 认证途中用户手动断开 Wi-Fi 则静默收场，不弹失败通知。
 */
class NetAuthService : Service() {

    companion object {
        const val CHANNEL_ID = "net_auth"
        const val NOTIF_ID = 10

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, NetAuthService::class.java))
        }

        fun createChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "校园网自动登录", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository by lazy { (application as KcbApplication).container.netRepository }

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        showForeground("正在登录校园网…")
        scope.launch {
            val msg = repository.ensureOnline(repository.wifiNetwork())
            val wifiGone = repository.wifiNetwork() == null
            when {
                // 认证途中手动断开 Wi-Fi：静默撤掉通知，不打扰
                wifiGone && !msg.startsWith("已") && !msg.startsWith("该 IP") ->
                    stopForeground(STOP_FOREGROUND_REMOVE)
                // 认证期间用户关闭了开关/清了凭据：静默
                msg.startsWith("自动登录已关闭") || msg.startsWith("未配置上网账号") ->
                    stopForeground(STOP_FOREGROUND_REMOVE)
                // 成功（含已在线）：短时展示后自动消失
                msg.startsWith("已") || msg.startsWith("该 IP") -> {
                    stopForeground(STOP_FOREGROUND_DETACH)
                    showResult(msg, autoDismiss = true)
                }
                // 失败：留通知（可划掉）
                else -> {
                    stopForeground(STOP_FOREGROUND_DETACH)
                    showResult(msg, autoDismiss = false)
                }
            }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(text: String, ongoing: Boolean, autoDismiss: Boolean): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_wifi)
            .setContentTitle("校园网")
            .setContentText(text)
            .setOngoing(ongoing)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(autoDismiss)
            .apply { if (autoDismiss) setTimeoutAfter(10_000) }
            .setContentIntent(pi)
            .build()
    }

    private fun showForeground(text: String) {
        val n = buildNotification(text, ongoing = true, autoDismiss = false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun showResult(text: String, autoDismiss: Boolean) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIF_ID, buildNotification(text, ongoing = false, autoDismiss = autoDismiss))
    }
}
