package li.lyc.lzuapp

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import li.lyc.lzuapp.data.db.CourseEntity
import li.lyc.lzuapp.theme.KcbTheme
import li.lyc.lzuapp.theme.ThemeColor
import li.lyc.lzuapp.theme.ThemeMode
import li.lyc.lzuapp.ui.addcourse.AddCourseScreen
import li.lyc.lzuapp.ui.dorm.DormScreen
import li.lyc.lzuapp.ui.dorm.DormViewModel
import li.lyc.lzuapp.ui.dorm.DormViewModelFactory
import li.lyc.lzuapp.ui.login.LoginScreen
import li.lyc.lzuapp.ui.profile.ProfileScreen
import li.lyc.lzuapp.ui.schedule.ScheduleScreen
import li.lyc.lzuapp.ui.schedule.ScheduleViewModel
import li.lyc.lzuapp.ui.schedule.ScheduleViewModelFactory
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {

    private val container: AppContainer by lazy { (application as KcbApplication).container }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ComponentActivity 的 enableEdgeToEdge 使用 SystemBarStyle.auto，自动跟随系统深色切换
        // 系统栏图标颜色；App 内手动覆盖主题时由 KcbTheme 的 SideEffect 修正。
        enableEdgeToEdge()
        // 关闭导航栏半透明 scrim，让底栏颜色延伸到底部（edge-to-edge 规范）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        setContent {
            val authState by container.authStore.state.collectAsStateWithLifecycle(initialValue = null)
            val themeMode = ThemeMode.fromStorage(authState?.themeMode)
            val themeColor = ThemeColor.fromStorage(authState?.themeColor)
            // 登录页（及未登录加载态）无论系统深色/用户配色如何，一律蓝色浅色
            val loggedIn = authState?.isLoggedIn == true
            KcbTheme(
                themeMode = if (loggedIn) themeMode else ThemeMode.LIGHT,
                themeColor = if (loggedIn) themeColor else ThemeColor.BLUE,
            ) {
                val semester by container.repository.observeSemester().collectAsStateWithLifecycle(initialValue = null)
                var showAddCourse by remember { mutableStateOf(false) }
                var editCourse by remember { mutableStateOf<CourseEntity?>(null) }
                var showYktBill by remember { mutableStateOf(false) }
                var showYktRecharge by remember { mutableStateOf(false) }
                // 「应用」聚合页的子页面（查寝/校园网/成绩/邮箱；宿舍电费是独立 Activity 见 onOpenElec）
                var showDorm by remember { mutableStateOf(false) }
                var showNet by remember { mutableStateOf(false) }
                var showGrade by remember { mutableStateOf(false) }
                var showMail by remember { mutableStateOf(false) }
                var showEval by remember { mutableStateOf(false) }
                var selectedTab by remember { mutableIntStateOf(0) }
                val snackbarHostState = remember { SnackbarHostState() }
                // 与课表页共享同一个 Activity 作用域 ViewModel（init 中触发静默更新）
                val scheduleVm: ScheduleViewModel = viewModel(
                    factory = ScheduleViewModelFactory(container.repository, container.authStore)
                )
                // 查寝页 ViewModel（Activity 作用域：snackbar 统一在 Activity 层播报）
                val dormVm: DormViewModel = viewModel(
                    factory = DormViewModelFactory(container.fdmRepository, applicationContext)
                )
                // 校园网页 ViewModel（同上）
                val netVm: li.lyc.lzuapp.ui.net.NetViewModel = viewModel(
                    factory = li.lyc.lzuapp.ui.net.NetViewModelFactory(
                        container.netRepository, container.authStore,
                    )
                )

                // 同步状态播报提升到 Activity 层：切换 tab 不中断、不消失；每次停留 2 秒
                LaunchedEffect(Unit) {
                    scheduleVm.syncEvents.collect {
                        withTimeoutOrNull(2_000) { snackbarHostState.showSnackbar(it) }
                    }
                }
                // 查寝页提示（签到成功/失败等），同样 2 秒
                LaunchedEffect(Unit) {
                    dormVm.events.collect {
                        withTimeoutOrNull(2_000) { snackbarHostState.showSnackbar(it) }
                    }
                }
                // 校园网页提示（登录/注销/下线结果等），同样 2 秒
                LaunchedEffect(Unit) {
                    netVm.events.collect {
                        withTimeoutOrNull(2_000) { snackbarHostState.showSnackbar(it) }
                    }
                }
                // 历史明文敏感字段一次性升级为 Keystore 加密（幂等）
                LaunchedEffect(Unit) { container.authStore.migrateEncryption() }
                // 校园网自动登录 Wi-Fi 事件回调按开关同步（开启过才注册，否则确保注销）
                LaunchedEffect(Unit) {
                    val c = container.authStore.current()
                    li.lyc.lzuapp.net.NetAutoLogin.syncState(
                        this@MainActivity,
                        c.netAutoLogin && c.activeNetAccount != null,
                    )
                }

                // 添加/编辑课程页：系统返回手势/返回键回到课表页（而非直接退出到桌面）
                BackHandler(enabled = showAddCourse) {
                    showAddCourse = false
                    editCourse = null
                }
                // 校园卡子页面返回（收银台是独立 Activity，见 onOpenCashier）
                BackHandler(enabled = showYktBill || showYktRecharge) {
                    showYktBill = false
                    showYktRecharge = false
                }
                // 「应用」子页面返回（查寝/校园网/成绩/邮箱/评教；邮箱与评教内部层级由各自的 BackHandler 优先接管）
                BackHandler(enabled = showDorm || showNet || showGrade || showMail || showEval) {
                    showDorm = false
                    showNet = false
                    showGrade = false
                    showMail = false
                    showEval = false
                }

                when {
                    authState == null -> {
                        // DataStore 首次读取中
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    authState?.isLoggedIn == true -> {
                        when {
                            // 子页面优先级：充值 > 账单 > 查寝 > 校园网 > 添加课程
                            showYktRecharge -> li.lyc.lzuapp.ui.ykt.RechargeScreen(
                                repository = container.yktRepository,
                                onBack = { showYktRecharge = false },
                                onOpenCashier = { url ->
                                    // 独立纯 View Activity（Compose 内嵌 WebView 在本机不渲染）
                                    startActivity(
                                        android.content.Intent(
                                            this@MainActivity,
                                            li.lyc.lzuapp.ui.ykt.CashierActivity::class.java,
                                        ).putExtra(
                                            li.lyc.lzuapp.ui.ykt.CashierActivity.EXTRA_URL, url,
                                        ),
                                    )
                                },
                            )
                            showYktBill -> li.lyc.lzuapp.ui.ykt.BillScreen(
                                repository = container.yktRepository,
                                onBack = { showYktBill = false },
                            )
                            showDorm -> Box(Modifier.fillMaxSize()) {
                                // 子页面独占全屏：自己挂 SnackbarHost（tab 脚手架此时不在组合中）
                                DormScreen(
                                    vm = dormVm,
                                    onBack = { showDorm = false },
                                    modifier = Modifier.navigationBarsPadding(),
                                )
                                SnackbarHost(
                                    snackbarHostState,
                                    Modifier.align(Alignment.BottomCenter).safeDrawingPadding(),
                                )
                            }
                            showNet -> Box(Modifier.fillMaxSize()) {
                                li.lyc.lzuapp.ui.net.NetScreen(
                                    repository = container.netRepository,
                                    authStore = container.authStore,
                                    onAutoLoginChanged = { enabled ->
                                        li.lyc.lzuapp.net.NetAutoLogin.syncState(this@MainActivity, enabled)
                                    },
                                    onBack = { showNet = false },
                                    vm = netVm,
                                    modifier = Modifier.navigationBarsPadding(),
                                )
                                SnackbarHost(
                                    snackbarHostState,
                                    Modifier.align(Alignment.BottomCenter).safeDrawingPadding(),
                                )
                            }
                            showGrade -> li.lyc.lzuapp.ui.grade.GradeScreen(
                                repository = container.gradeRepository,
                                onBack = { showGrade = false },
                                modifier = Modifier.navigationBarsPadding(),
                            )
                            showMail -> li.lyc.lzuapp.ui.mail.MailScreen(
                                repository = container.mailRepository,
                                onBack = { showMail = false },
                                modifier = Modifier.navigationBarsPadding(),
                            )
                            showEval -> li.lyc.lzuapp.ui.eval.EvalScreen(
                                repository = container.evalRepository,
                                onBack = { showEval = false },
                                modifier = Modifier.navigationBarsPadding(),
                            )
                            showAddCourse -> {
                            // 编辑历史学期课程时，周次选择器需覆盖该课程已有周次
                            val editMaxWeek = editCourse?.week?.split(",")
                                ?.mapNotNull { it.toIntOrNull() }?.maxOrNull() ?: 0
                            AddCourseScreen(
                                repository = container.repository,
                                totalWeeks = maxOf(semester?.totalWeeks ?: 20, editMaxWeek),
                                editCourse = editCourse,
                                onBack = {
                                    showAddCourse = false
                                    editCourse = null
                                },
                            )
                            }
                            else -> {
                            Scaffold(
                                // 各子页面（TopAppBar/NavigationBar）自行处理系统栏 inset，外层不重复 padding
                                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                                snackbarHost = { SnackbarHost(snackbarHostState) },
                                bottomBar = {
                                    NavigationBar {
                                        NavigationBarItem(
                                            selected = selectedTab == 0,
                                            onClick = { selectedTab = 0 },
                                            icon = { Icon(Icons.Default.CalendarMonth, contentDescription = "课表") },
                                            label = { Text("课表") },
                                        )
                                        NavigationBarItem(
                                            selected = selectedTab == 1,
                                            onClick = { selectedTab = 1 },
                                            icon = { Icon(Icons.Default.QrCode, contentDescription = "校园卡") },
                                            label = { Text("校园卡") },
                                        )
                                        NavigationBarItem(
                                            selected = selectedTab == 2,
                                            onClick = { selectedTab = 2 },
                                            icon = { Icon(Icons.Default.Apps, contentDescription = "应用") },
                                            label = { Text("应用") },
                                        )
                                        NavigationBarItem(
                                            selected = selectedTab == 3,
                                            onClick = { selectedTab = 3 },
                                            icon = { Icon(Icons.Default.Settings, contentDescription = "设置") },
                                            label = { Text("设置") },
                                        )
                                    }
                                },
                            ) { padding ->
                                when (selectedTab) {
                                    0 -> ScheduleScreen(
                                        vm = scheduleVm,
                                        onAddCourse = { showAddCourse = true },
                                        onEditCourse = {
                                            editCourse = it
                                            showAddCourse = true
                                        },
                                        modifier = Modifier.padding(padding),
                                    )
                                    1 -> li.lyc.lzuapp.ui.ykt.YktScreen(
                                        repository = container.yktRepository,
                                        onRecharge = { showYktRecharge = true },
                                        onBills = { showYktBill = true },
                                        modifier = Modifier.padding(padding),
                                    )
                                    2 -> li.lyc.lzuapp.ui.apps.AppsScreen(
                                        onOpenDorm = { showDorm = true },
                                        onOpenNet = { showNet = true },
                                        onOpenGrade = { showGrade = true },
                                        onOpenMail = { showMail = true },
                                        onOpenEval = { showEval = true },
                                        onOpenElec = {
                                            // 独立纯 View Activity（Compose 内嵌 WebView 在本机不渲染，见 ElecActivity 注释）
                                            startActivity(
                                                android.content.Intent(
                                                    this@MainActivity,
                                                    li.lyc.lzuapp.ui.ykt.ElecActivity::class.java,
                                                ),
                                            )
                                        },
                                        modifier = Modifier.padding(padding),
                                    )
                                    else -> ProfileScreen(
                                        authStore = container.authStore,
                                        repository = container.repository,
                                        fdmRepository = container.fdmRepository,
                                        modifier = Modifier.padding(padding),
                                    )
                                }
                            }
                            }
                        }
                    }
                    else -> {
                        val scope = rememberCoroutineScope()
                        LoginScreen(
                            repository = container.repository,
                            agreementUser = authState?.agreementUser.orEmpty(),
                            onAcceptAgreement = { u ->
                                // 存 "学号|协议版本"：协议修订后老用户登录需重新滑到底确认
                                scope.launch {
                                    container.authStore.saveAgreementUser(
                                        u + "|" + li.lyc.lzuapp.ui.login.AGREEMENT_VERSION,
                                    )
                                }
                            },
                            modifier = Modifier.safeDrawingPadding(),
                        )
                    }
                }
            }
        }
    }
}
