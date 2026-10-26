package li.lyc.lzuapp

import android.app.Application
import android.content.Context
import androidx.room.Room
import li.lyc.lzuapp.data.AuthStore
import li.lyc.lzuapp.data.api.FdmApi
import li.lyc.lzuapp.data.api.JwqeApi
import li.lyc.lzuapp.data.api.LzuApi
import li.lyc.lzuapp.data.api.YktApi
import li.lyc.lzuapp.data.db.AppDatabase
import li.lyc.lzuapp.data.repo.CourseRepository
import li.lyc.lzuapp.data.repo.EvalRepository
import li.lyc.lzuapp.data.repo.FdmRepository
import li.lyc.lzuapp.data.repo.YktRepository
class KcbApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // osmdroid 初始化：加载配置；UA 设成浏览器 UA（高德瓦片按 UA 防盗链）
        org.osmdroid.config.Configuration.getInstance().apply {
            load(this@KcbApplication, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            userAgentValue =
                "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        }
        container = AppContainer(this)
        // 高德定位 SDK 合规接口（必须先于 SDK 任何初始化/调用）
        runCatching {
            com.amap.api.location.AMapLocationClient.updatePrivacyShow(this, true, true)
            com.amap.api.location.AMapLocationClient.updatePrivacyAgree(this, true)
        }
        // fdm 调试日志落盘（该机 logcat 缓冲旋转极快，排查空数据问题用；>64KB 自动截断）
        // 仅 debug 包启用，release 下 sink 保持 null 不落盘
        if (BuildConfig.DEBUG) {
            container.fdmApi.debugSink = { msg ->
                runCatching {
                    val f = java.io.File(filesDir, "fdm_debug.log")
                    if (f.length() > 64 * 1024) f.writeText("")
                    f.appendText(
                        "${java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())} $msg\n",
                    )
                }
            }
        }
    }
}

class AppContainer(context: Context) {
    val api: LzuApi = LzuApi()
    val authStore: AuthStore = AuthStore(context)
    val database: AppDatabase = Room.databaseBuilder(
        context,
        AppDatabase::class.java,
        "kcb.db",
    ).addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3).build()
    val repository: CourseRepository = CourseRepository(context, api, authStore, database)
    val yktApi: YktApi = YktApi()
    val yktRepository: YktRepository = YktRepository(authStore, api, yktApi, repository)
    private val evalSafety = li.lyc.lzuapp.data.repo.EvalSafetyStore(context)
    val jwqeApi: JwqeApi = JwqeApi(gate = li.lyc.lzuapp.data.api.EvalRequestGate(evalSafety))
    val evalRepository: EvalRepository = EvalRepository(authStore, api, jwqeApi, repository, evalSafety)
    val fdmApi: FdmApi = FdmApi()
    val fdmRepository: FdmRepository = FdmRepository(authStore, api, fdmApi, repository)
    val netRepository: li.lyc.lzuapp.data.repo.NetRepository =
        li.lyc.lzuapp.data.repo.NetRepository(context, authStore)
    val gradeRepository: li.lyc.lzuapp.data.repo.GradeRepository =
        li.lyc.lzuapp.data.repo.GradeRepository(api, repository)
    val mailRepository: li.lyc.lzuapp.data.repo.MailRepository =
        li.lyc.lzuapp.data.repo.MailRepository(api, repository)
}
