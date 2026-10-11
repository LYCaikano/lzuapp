package li.lyc.lzuapp.ui.dorm

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import li.lyc.lzuapp.data.SignPhotoLibrary
import li.lyc.lzuapp.data.repo.FdmCategory
import li.lyc.lzuapp.data.repo.FdmDayTasks
import li.lyc.lzuapp.data.repo.FdmRepository
import li.lyc.lzuapp.data.repo.FdmStats
import li.lyc.lzuapp.data.repo.FdmTask
import li.lyc.lzuapp.data.repo.FdmTaskDetail
import li.lyc.lzuapp.util.GeoTransform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DormUiState(
    /** 进入页面时的登录态静默校验中 */
    val checking: Boolean = true,
    val loading: Boolean = false,
    /** 手动刷新进行中（期间 onEnterPage 等入口校验让路，避免误报/互相打断） */
    val refreshing: Boolean = false,
    /** 顶栏刷新状态文案（刷新中/刷新成功/失败原因），仿课表页 syncStatusText */
    val refreshStatusText: String = "",
    val refreshStatusError: Boolean = false,
    val error: String? = null,
    /** 今日进行中任务（getStuAttendacesInOneDay） */
    val runningTasks: List<FdmTask> = emptyList(),
    /** 今日任务（本月数据过滤当天，无进行中任务时展示） */
    val todayTasks: List<FdmTask> = emptyList(),
    val stats: FdmStats? = null,
    /** 本月记录（按天倒序） */
    val monthRecords: List<FdmDayTasks> = emptyList(),

    /** 打卡卡片绑定的当前任务（null → 卡片不显示） */
    val signTask: FdmTask? = null,
    /** 绑定任务的详情（围栏/拍照要求/签到模式），null=加载中或失败 */
    val signDetail: FdmTaskDetail? = null,
    val locating: Boolean = false,
    val locateFailed: Boolean = false,
    /** 设备定位（WGS-84；来源可能是系统 GPS 或高德网络定位） */
    val deviceLng: Double? = null,
    val deviceLat: Double? = null,
    /** 设备定位时间戳（毫秒）；用于 UI 提示位置新旧 */
    val deviceLocAt: Long? = null,
    /** 定位精度（米，0/null=未知）；>300 的粗精度结果（如纯基站）UI 提示核对 */
    val deviceAccuracyM: Int? = null,
    /** 地图选点（GCJ-02，高德瓦片坐标系）；null = 用设备位置 */
    val pickedLng: Double? = null,
    val pickedLat: Double? = null,
    /** 选点时间戳（毫秒） */
    val pickedAt: Long? = null,
    val photoBytes: ByteArray? = null,
    val photoThumb: Bitmap? = null,
    val signing: Boolean = false,
    /** 更新签到模式：已签到任务点「更新签到」后进入可编辑态（选点/拍照重新开放） */
    val updating: Boolean = false,
    /** 一键签到照片库（预先放入的照片文件，一键签到随机取用+扰动） */
    val signLibrary: List<File> = emptyList(),
) {
    /** 当前任务（进行中优先，否则今日任务） */
    val currentTasks: List<FdmTask> get() = runningTasks.ifEmpty { todayTasks }

    /** 有效签到坐标（BD-09，与围栏同坐标系）：选点优先，否则设备 GPS；都无为 null */
    val effectiveBd09: DoubleArray?
        get() = if (pickedLng != null && pickedLat != null) {
            GeoTransform.gcj02ToBd09(pickedLng, pickedLat)
        } else if (deviceLng != null && deviceLat != null) {
            GeoTransform.wgs84ToBd09(deviceLng, deviceLat)
        } else null
}

class DormViewModelFactory(
    private val repo: FdmRepository,
    private val app: Context,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return DormViewModel(repo, app) as T
    }
}

class DormViewModel(
    private val repo: FdmRepository,
    app: Context,
) : ViewModel() {

    /** 一键签到照片库（应用私有目录，落盘持久化） */
    private val photoLibrary = SignPhotoLibrary(app)

    private val _uiState = MutableStateFlow(DormUiState())
    val uiState: StateFlow<DormUiState> = _uiState.asStateFlow()

    /** 一次性提示（签到成功/失败等，Activity 层 snackbar 统一播报） */
    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** UI 层提示（权限被拒等），走统一 snackbar */
    fun notify(message: String) {
        _events.trySend(message)
    }

    private fun announce(text: String) {
        _events.trySend(text)
    }

    /** 顶栏刷新 1s 节流（冷却内点击直接无视）；签到相关按钮 500ms 冷却 */
    private var lastRefreshAt = 0L
    private var lastActionAt = 0L

    /** loadAllInternal 串行化：refresh 绕过 loading 守卫直接调用，与在途 loadAll 交错时防旧结果覆盖新结果 */
    private val loadMutex = Mutex()

    init {
        // 进入页面先静默校验登录态（自动跑辅导猫认证链），通过后加载数据
        repo.log("VM init: checkLoginState")
        refreshLibrary()
        viewModelScope.launch {
            when (val r = repo.checkLoginState()) {
                is FdmRepository.FdmResult.Ok -> {
                    _uiState.value = _uiState.value.copy(checking = false)
                    loadAll()
                }
                is FdmRepository.FdmResult.Err ->
                    _uiState.value = _uiState.value.copy(checking = false, error = r.message)
            }
        }
    }

    private fun currentMonth(): String = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** 手动刷新（1s 冷却）：无条件主动重跑认证链（参照课表 forceRefresh），再加载数据；toast + 顶栏文案：刷新中/刷新成功 */
    fun refresh() {
        val now = System.currentTimeMillis()
        if (now - lastRefreshAt < 1000) return
        lastRefreshAt = now
        repo.log("refresh: forceRelogin 开始")
        announce("刷新中")
        _uiState.value = _uiState.value.copy(
            refreshing = true, error = null,
            refreshStatusText = "刷新中", refreshStatusError = false,
        )
        viewModelScope.launch {
            try {
                when (val r = repo.forceRelogin()) {
                    is FdmRepository.FdmResult.Ok -> {
                        val err = loadAllInternal()
                        _uiState.value = _uiState.value.copy(
                            refreshStatusText = err ?: "刷新成功",
                            refreshStatusError = err != null,
                        )
                        announce(err ?: "刷新成功")
                    }
                    is FdmRepository.FdmResult.Err -> {
                        _uiState.value = _uiState.value.copy(
                            error = r.message,
                            refreshStatusText = r.message, refreshStatusError = true,
                        )
                        announce(r.message)
                    }
                }
            } finally {
                _uiState.value = _uiState.value.copy(refreshing = false)
            }
        }
    }

    /** 每次进入查寝页都真实校验一次登录态（VM 是 Activity 作用域，init 只在进程启动时跑一次） */
    private var lastEnterAt = 0L
    fun onEnterPage() {
        val now = System.currentTimeMillis()
        if (now - lastEnterAt < 5_000) return // 与 init/retry 的连发去重
        lastEnterAt = now
        val s = _uiState.value
        repo.log("onEnterPage: checking=${s.checking} loading=${s.loading} refreshing=${s.refreshing} error=${s.error} running=${s.runningTasks.size} records=${s.monthRecords.size} stats=${s.stats != null}")
        // 刷新/校验/加载进行中时让路：刷新流程在后台继续，不在此插入第二次校验（防竞态误报）
        if (s.checking || s.loading || s.refreshing) return
        viewModelScope.launch {
            when (val r = repo.checkLoginState()) {
                is FdmRepository.FdmResult.Ok -> loadAll()
                is FdmRepository.FdmResult.Err ->
                    _uiState.value = _uiState.value.copy(error = r.message)
            }
        }
    }

    /** 校验失败/加载失败后的整页重试 */
    fun retry() {
        _uiState.value = DormUiState()
        viewModelScope.launch {
            when (val r = repo.checkLoginState()) {
                is FdmRepository.FdmResult.Ok -> {
                    _uiState.value = _uiState.value.copy(checking = false)
                    loadAll()
                }
                is FdmRepository.FdmResult.Err ->
                    _uiState.value = _uiState.value.copy(checking = false, error = r.message)
            }
        }
    }

    /** 加载进行中任务 + 本月记录/统计（fire-and-forget 包装）；返回错误文案（null=成功） */
    private fun loadAll(manual: Boolean = false) {
        if (_uiState.value.loading) return
        viewModelScope.launch {
            val err = loadAllInternal()
            if (manual && err != null) announce(err)
        }
    }

    /**
     * 实际加载（suspend）。finally 兜底复位 loading：协程被取消/异常逃逸都不能让它
     * 卡死（loading 卡 true 会导致之后所有加载直接 return = 刷新永久失效、页面全空白）。
     * 全程持 [loadMutex]：refresh 与 loadAll 共用同一把锁串行，防并发写状态互相覆盖。
     */
    private suspend fun loadAllInternal(): String? = loadMutex.withLock {
        _uiState.value = _uiState.value.copy(loading = true, error = null)
        return@withLock try {
            val running = repo.runningTasks()
            val month = repo.monthData(currentMonth())
            android.util.Log.d(
                "DormVM",
                "loadAll running=${(running as? FdmRepository.FdmResult.Ok)?.data?.size}/${(running as? FdmRepository.FdmResult.Err)?.message} monthErr=${(month as? FdmRepository.FdmResult.Err)?.message}",
            )
            val err = (running as? FdmRepository.FdmResult.Err)?.message
                ?: (month as? FdmRepository.FdmResult.Err)?.message
            val days = (month as? FdmRepository.FdmResult.Ok)?.data?.days
            _uiState.value = _uiState.value.copy(
                runningTasks = (running as? FdmRepository.FdmResult.Ok)?.data
                    ?: _uiState.value.runningTasks,
                monthRecords = days ?: _uiState.value.monthRecords,
                todayTasks = days?.firstOrNull { it.dayInMonth == today() }?.tasks
                    ?: _uiState.value.todayTasks,
                stats = (month as? FdmRepository.FdmResult.Ok)?.data?.stats ?: _uiState.value.stats,
                error = err,
            )
            bindSignTask()
            err
        } finally {
            _uiState.value = _uiState.value.copy(loading = false)
        }
    }

    /** 打卡卡片绑定任务：未签到在时段内 > 未签到 > 首个当前任务；切换时重置卡片状态并加载详情 */
    private fun bindSignTask() {
        val s = _uiState.value
        val cur = s.currentTasks
        // 待签优先（含时段内的登记类任务——服务端数组归类可能滞后，登记≠免签）
        val task = cur.firstOrNull { it.signableNow }
            ?: cur.firstOrNull { it.category == FdmCategory.UNSIGNED }
            ?: cur.firstOrNull()
        if (sameTask(task, s.signTask)) {
            // 同一任务但内容变了（如刚签到/更新签到成功：未签→已签、照片更新）：就地更新任务对象，
            // 保留选点/照片；分类变化时详情（照片 URL/签到位置）必须重取
            if (task != s.signTask) {
                val categoryChanged = task?.category != s.signTask?.category
                _uiState.value = _uiState.value.copy(
                    signTask = task,
                    // 任务变为已签后退出更新模式
                    updating = if (task?.category == FdmCategory.SIGNED) false else _uiState.value.updating,
                )
                if (categoryChanged && task != null) {
                    reloadDetail(task)
                }
            }
            return
        }
        // 设备位置是设备状态，跨任务保留；选点/照片随任务重置
        _uiState.value = _uiState.value.copy(
            signTask = task, signDetail = null, updating = false,
            pickedLng = null, pickedLat = null, pickedAt = null,
            photoBytes = null, photoThumb = null,
        )
        if (task != null) reloadDetail(task)
    }

    /** 照片签名 URL 过期/加载失败时重取详情换新 URL（500ms 冷却） */
    private var lastDetailRefreshAt = 0L
    fun refreshDetail() {
        val now = System.currentTimeMillis()
        if (now - lastDetailRefreshAt < 500) return
        lastDetailRefreshAt = now
        _uiState.value.signTask?.let { reloadDetail(it) }
    }

    /** 加载任务详情（围栏/拍照要求/已签到照片位置）；落地前校验仍是当前任务，防竞态签错 */
    private fun reloadDetail(task: FdmTask) {
        viewModelScope.launch {
            when (val r = repo.taskDetail(task)) {
                is FdmRepository.FdmResult.Ok ->
                    if (sameTask(task, _uiState.value.signTask)) {
                        _uiState.value = _uiState.value.copy(signDetail = r.data)
                    }
                is FdmRepository.FdmResult.Err -> announce(r.message)
            }
        }
    }

    /**
     * 任务同一性判定：running 与 month 两个接口返回的 WID 字段可能一边为空，
     * 直接双等比较会在刷新后误判"任务切换"而清空用户已选点/照片。
     * 规则：双方都有 signInstanceWid 时比它（stuSignWid 只在双方都非空时参与）；
     * 否则退比任务名+时段；双 null 视为同一个。
     */
    private fun sameTask(a: FdmTask?, b: FdmTask?): Boolean {
        if (a == null || b == null) return a == b
        val aw = a.signInstanceWid
        val bw = b.signInstanceWid
        if (!aw.isNullOrEmpty() && !bw.isNullOrEmpty()) {
            if (aw != bw) return false
            val asw = a.stuSignWid
            val bsw = b.stuSignWid
            return asw.isEmpty() || bsw.isEmpty() || asw == bsw
        }
        return a.taskName == b.taskName && a.timeRange == b.timeRange
    }

    // ---------- 定位 / 选点 / 照片 ----------

    fun setLocating() {
        _uiState.value = _uiState.value.copy(locating = true, locateFailed = false)
    }

    /**
     * 定位成功（WGS-84 原始值；显示与提交时各自转坐标系）。
     * 覆盖语义：用户主动点「获取当前位置」= 明确要用真实位置，清掉之前的手动选点；
     * 反之手动选点后 effectiveBd09 优先用选点，后来的定位结果不会顶掉选点。
     */
    fun onLocation(lng: Double, lat: Double, accuracyM: Int? = null) {
        _uiState.value = _uiState.value.copy(
            locating = false, locateFailed = false,
            deviceLng = lng, deviceLat = lat, deviceAccuracyM = accuracyM,
            deviceLocAt = System.currentTimeMillis(),
            pickedLng = null, pickedLat = null, pickedAt = null,
        )
    }

    fun onLocateFailed() {
        _uiState.value = _uiState.value.copy(locating = false, locateFailed = true)
        announce("定位失败：室内可能无 GPS 信号，可到窗边重试，或点地图手动选点")
    }

    /** 地图选点（GCJ-02）；选点优先于设备定位（见 effectiveBd09） */
    fun setPickedPoint(lng: Double, lat: Double) {
        _uiState.value = _uiState.value.copy(
            pickedLng = lng, pickedLat = lat, pickedAt = System.currentTimeMillis(),
        )
    }

    fun onPhoto(bytes: ByteArray, thumb: Bitmap) {
        _uiState.value = _uiState.value.copy(photoBytes = bytes, photoThumb = thumb)
    }

    fun removePhoto() {
        _uiState.value = _uiState.value.copy(photoBytes = null, photoThumb = null)
    }

    // ---------- 一键签到照片库 ----------

    fun refreshLibrary() {
        viewModelScope.launch(Dispatchers.IO) {
            val files = photoLibrary.list()
            _uiState.value = _uiState.value.copy(signLibrary = files)
        }
    }

    /** 批量添加照片到照片库（系统照片选择器多选结果） */
    fun addLibraryPhotos(uris: List<Uri>, resolver: android.content.ContentResolver) {
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            var added = 0
            uris.forEach { if (photoLibrary.add(resolver, it)) added++ }
            val files = photoLibrary.list()
            _uiState.value = _uiState.value.copy(signLibrary = files)
            announce(if (added > 0) "已添加 $added 张到照片库" else "图片读取失败，换一张试试")
        }
    }

    fun removeLibraryPhoto(file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            photoLibrary.remove(file)
            _uiState.value = _uiState.value.copy(signLibrary = photoLibrary.list())
        }
    }

    // ---------- 一键签到 ----------

    /**
     * 一键签到（500ms 冷却）：围栏圆内 80% 半径随机取点；拍照任务从照片库随机取一张
     * 扰动后上传；纯定位任务不传照片。参考 fudaomao-auto-check-in run.py。
     */
    fun oneClickSign() {
        val now = System.currentTimeMillis()
        if (now - lastActionAt < 500) return
        lastActionAt = now
        val s = _uiState.value
        val task = s.signTask ?: return
        // 一键签到与手动同一准入：未签在时段内可签；更新模式按 canUpdate
        if (!s.updating && !task.signableNow) return
        if (s.updating && s.signDetail?.canUpdate != true) return
        if (s.signing) return
        val d = s.signDetail ?: run {
            announce("任务详情加载中，请稍后再试")
            return
        }
        d.unsupportedHint?.let {
            announce(it)
            return
        }
        if (d.fences.isEmpty()) {
            announce("该任务无签到范围信息，请用手动签到")
            return
        }
        if (d.isPhoto && s.signLibrary.isEmpty()) {
            announce("请先在下方照片库添加签到照片")
            return
        }
        _uiState.value = _uiState.value.copy(signing = true)
        viewModelScope.launch {
            // 围栏随机取点（BD-09，与围栏同坐标系），必在范围内 → isMalposition=0
            val fence = d.fences.random()
            val (lng, lat) = FdmRepository.randomPointInFence(fence)
            val photo = if (d.isPhoto) {
                withContext(Dispatchers.IO) { photoLibrary.randomDisturbed() } ?: run {
                    _uiState.value = _uiState.value.copy(signing = false)
                    announce("照片库读取失败，请重试")
                    return@launch
                }
            } else null
            when (val r = repo.sign(d, lng, lat, isMalposition = false, reason = "", photo = photo)) {
                is FdmRepository.FdmResult.Ok -> {
                    _uiState.value = _uiState.value.copy(
                        signing = false, updating = false,
                        pickedLng = null, pickedLat = null, pickedAt = null,
                        photoBytes = null, photoThumb = null,
                        stats = s.stats?.let { it.copy(signNum = it.signNum + 1) },
                    )
                    announce(if (s.updating) "更新成功" else "签到成功")
                    loadAll()
                    _uiState.value.signTask?.let { reloadDetail(it) }
                }
                is FdmRepository.FdmResult.Err -> {
                    _uiState.value = _uiState.value.copy(signing = false)
                    announce(r.message)
                }
            }
        }
    }

    // ---------- 签到 ----------

    /** 进入更新签到模式（已签到任务）：回到可编辑态，提交时覆盖原签到 */
    fun startUpdate() {
        val s = _uiState.value
        if (s.signTask?.category != FdmCategory.SIGNED) return
        if (s.signDetail?.canUpdate != true) {
            announce("该任务不允许更新签到")
            return
        }
        _uiState.value = _uiState.value.copy(updating = true)
    }

    /** 退出更新签到模式（不提交） */
    fun cancelUpdate() {
        _uiState.value = _uiState.value.copy(updating = false)
    }

    /**
     * 确认签到（500ms 冷却）。提交坐标 = 选点(GCJ→BD) 或设备 GPS(WGS→BD)，
     * isMalposition 由提交坐标对围栏（BD-09）判定；范围外且任务不允许外签时拦截。
     * 更新模式（已签到任务重新提交）走同一接口。
     */
    fun confirmSign() {
        val now = System.currentTimeMillis()
        if (now - lastActionAt < 500) return
        lastActionAt = now
        val s = _uiState.value
        val task = s.signTask ?: return
        // 更新模式下允许已签到任务重新提交；否则必须可签
        if (!s.updating && !task.signableNow) return
        if (s.updating && s.signDetail?.canUpdate != true) return
        if (s.signing) return
        val d = s.signDetail ?: run {
            announce("任务详情加载中，请稍后再试")
            return
        }
        d.unsupportedHint?.let {
            announce(it)
            return
        }
        val bd = s.effectiveBd09 ?: run {
            announce("请先获取当前位置或在地图上选点")
            return
        }
        if (d.isPhoto && s.photoBytes == null) {
            announce("请先拍照或从相册选择照片")
            return
        }
        val malposition = d.fences.isNotEmpty() && !FdmRepository.inAnyFence(d.fences, bd[0], bd[1])
        if (malposition && !d.allowOutOfFence) {
            announce("不在签到范围内，该任务不允许范围外签到")
            return
        }
        _uiState.value = _uiState.value.copy(signing = true)
        viewModelScope.launch {
            // 新版卡片无原因输入框，范围外（任务允许时）按空原因提交
            when (val r = repo.sign(d, bd[0], bd[1], malposition, reason = "", photo = s.photoBytes)) {
                is FdmRepository.FdmResult.Ok -> {
                    _uiState.value = _uiState.value.copy(
                        signing = false, updating = false,
                        pickedLng = null, pickedLat = null, pickedAt = null,
                        photoBytes = null, photoThumb = null,
                        // 乐观更新：统计已签 +1（服务端统计可能滞后，刷新后以其为准）
                        stats = s.stats?.let { it.copy(signNum = it.signNum + 1) },
                    )
                    announce(if (s.updating) "更新成功" else "签到成功")
                    loadAll()
                    // 更新签到后云端照片/签到位置已变：强制重取详情（任务分类不变时 bindSignTask 不会重取）
                    _uiState.value.signTask?.let { reloadDetail(it) }
                }
                is FdmRepository.FdmResult.Err -> {
                    _uiState.value = _uiState.value.copy(signing = false)
                    announce(r.message)
                }
            }
        }
    }
}
