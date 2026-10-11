package li.lyc.lzuapp.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.runBlocking
import li.lyc.lzuapp.KcbApplication

/** 开机自启：仅当用户开启过自动登录且已配置账号时才重新注册 Wi-Fi 事件回调 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val container = (context.applicationContext as? KcbApplication)?.container ?: return
        // goAsync 里读 DataStore（毫秒级），避免阻塞主线程广播
        val pending = goAsync()
        Thread {
            try {
                val c = runBlocking { container.authStore.current() }
                if (c.netAutoLogin && c.activeNetAccount != null) {
                    NetAutoLogin.syncState(context, true)
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
