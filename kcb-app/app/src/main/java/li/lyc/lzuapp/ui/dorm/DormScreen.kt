package li.lyc.lzuapp.ui.dorm

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.core.location.LocationManagerCompat
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import li.lyc.lzuapp.KcbApplication
import li.lyc.lzuapp.data.repo.FdmCategory
import li.lyc.lzuapp.data.repo.FdmDayTasks
import li.lyc.lzuapp.data.repo.FdmRepository
import li.lyc.lzuapp.data.repo.FdmTask
import li.lyc.lzuapp.util.GeoTransform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.osmdroid.util.GeoPoint
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

/** 已签到绿（深/浅色各一） */
private val SignedGreen: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF81C784) else Color(0xFF2E7D32)

/**
 * 查寝主页：当前任务 + 打卡卡片（地图/拍照/签到） + 本月统计 + 本月记录。
 * 进入页面先静默校验查寝会话（认证链在仓库层自动跑），失败原因由顶栏刷新状态文案展示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DormScreen(
    vm: DormViewModel,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showMap by remember { mutableStateOf(false) }

    // 每次进入查寝页都真实校验一次登录态（tab 切入时本组合重新执行）
    LaunchedEffect(Unit) { vm.onEnterPage() }

    // ---------- 定位（权限用时申请） ----------

    fun locate() {
        vm.setLocating()
        scope.launch {
            val loc = fetchLocation(context)
            if (loc != null) {
                vm.onLocation(
                    loc.longitude, loc.latitude,
                    loc.accuracy.takeIf { it > 0f }?.roundToInt(),
                )
            } else {
                vm.onLocateFailed()
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) locate() else vm.notify("需要定位权限来获取位置")
    }

    fun startLocate() {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) locate()
        else permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        )
    }

    /** 进全屏选点页；无定位权限时顺带申请（拒绝只提示，不影响选点） */
    fun openMap() {
        showMap = true
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            )
        }
    }

    // ---------- 拍照 / 相册 ----------

    // 拍照：TakePicture + FileProvider 缓存文件（系统相机契约，不需 CAMERA 权限）
    var photoFile by remember { mutableStateOf<File?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = photoFile
        if (ok && f != null) {
            scope.launch {
                val r = withContext(Dispatchers.IO) { compressPhoto(f) }
                if (r != null) vm.onPhoto(r.first, r.second)
                f.delete()
            }
        } else f?.delete()
    }

    fun launchCamera() {
        // 拍照缓存固定在 cacheDir/sign/ 子目录（与 file_paths.xml 授权范围一致）
        val dir = File(context.cacheDir, "sign").apply { mkdirs() }
        val f = File(dir, "fdm_sign_${System.currentTimeMillis()}.jpg")
        photoFile = f
        val uri = FileProvider.getUriForFile(context, "li.lyc.lzuapp.fileprovider", f)
        takePicture.launch(uri)
    }

    // 相册：系统照片选择器（不需 READ_MEDIA 权限）
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val r = withContext(Dispatchers.IO) { compressPhoto(context, uri) }
                if (r != null) vm.onPhoto(r.first, r.second)
                else vm.notify("图片读取失败，换一张试试")
            }
        }
    }

    fun launchGallery() {
        pickImage.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    // 照片库：系统照片选择器多选（不需 READ_MEDIA 权限）
    val pickLibraryImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (!uris.isNullOrEmpty()) vm.addLibraryPhotos(uris, context.contentResolver)
    }

    fun launchLibraryPicker() {
        pickLibraryImages.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    Box(modifier.fillMaxSize()) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    title = { Text("查寝") },
                    navigationIcon = {
                        if (onBack != null) {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                            }
                        }
                    },
                    actions = {
                        // 刷新状态文案（刷新中/刷新成功/失败原因）在按钮左侧，仿课表页
                        if (state.refreshing) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(4.dp))
                        }
                        val statusText = state.refreshStatusText
                        if (statusText.isNotEmpty()) {
                            Text(
                                statusText,
                                color = if (state.refreshStatusError) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 140.dp),
                            )
                            Spacer(Modifier.width(2.dp))
                        }
                        IconButton(onClick = vm::refresh) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                    },
                )
            },
        ) { padding ->
            if (state.checking) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "正在校验登录状态…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    // 当前任务：进行中优先；无进行中时展示今日任务。
                    // 已并入打卡卡片的任务不再重复显示；全部并入（单任务常见情形）则整个区块不显示
                    val otherTasks = state.currentTasks.filter {
                        it.signInstanceWid != state.signTask?.signInstanceWid ||
                            it.stuSignWid != state.signTask?.stuSignWid
                    }
                    if (otherTasks.isNotEmpty() ||
                        (state.currentTasks.isEmpty() && state.signTask == null)
                    ) {
                        Text("当前任务", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        if (state.currentTasks.isEmpty()) {
                            Text(
                                "今日暂无查寝任务",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            otherTasks.forEach { task ->
                                TaskCard(task)
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }

                    // 打卡卡片（绑定当前任务；无任务也显示骨架：标题「查寝签到」+ 内容区蒙板）
                    Spacer(Modifier.height(8.dp))
                    SignCard(
                        state = state,
                        onOpenMap = { openMap() },
                        onLocate = { startLocate() },
                        onCamera = { launchCamera() },
                        onGallery = { launchGallery() },
                        onRemovePhoto = vm::removePhoto,
                        onSign = vm::confirmSign,
                        onOneClickSign = vm::oneClickSign,
                        onAddLibraryPhotos = { launchLibraryPicker() },
                        onRemoveLibraryPhoto = vm::removeLibraryPhoto,
                        onStartUpdate = vm::startUpdate,
                        onCancelUpdate = vm::cancelUpdate,
                        onRefreshDetail = vm::refreshDetail,
                    )

                    // 本月记录：日历视图（7 列状态标记，含统计行）
                    Spacer(Modifier.height(16.dp))
                    Text("本月记录", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    MonthCalendar(days = state.monthRecords, stats = state.stats, todayTasks = state.currentTasks)
                    Spacer(Modifier.height(24.dp))
                }
            }
        }

        // 全屏地图选点页（覆盖本 tab 内容区；返回键/返回按钮关闭）
        if (showMap) {
            BackHandler { showMap = false }
            MapPickerPage(
                state = state,
                accentColor = MaterialTheme.colorScheme.primary.toArgb(),
                onConfirm = { lng, lat ->
                    vm.setPickedPoint(lng, lat)
                    showMap = false
                },
                onLocateMe = { startLocate() },
                onClose = { showMap = false },
            )
        }
    }
}

/** 任务卡片：任务名 + 发布人 + 时段 + 状态（签到入口在打卡卡片上） */
@Composable
private fun TaskCard(task: FdmTask) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    task.taskName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                StatusChip(task)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "发布人：${task.senderUserName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "签到时段：${task.timeRange}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 打卡卡片（两列 + 底部签到条，比例布局）：
 * 左列 = 地图预览（点击进全屏选点）；右列 = 选图框（无照片=虚位框，有照片=缩略图）；
 * 底部 = 全宽签到按钮（未签到且在时段内可点，否则置灰）。
 * 已签到展示态（已签到且未进更新模式）= 位置/时间 + 云端照片 + 「更新签到」入口；
 * 更新模式（state.updating）= 与未签到一致的可编辑 UI，按钮「更新签到」+「取消」；
 * 不可签（不在时段内/无任务）= 内容区盖灰色蒙板，定位/拍照按钮禁用；无任务也渲染骨架。
 */
@Composable
private fun SignCard(
    state: DormUiState,
    onOpenMap: () -> Unit,
    onLocate: () -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onRemovePhoto: () -> Unit,
    onSign: () -> Unit,
    onOneClickSign: () -> Unit,
    onAddLibraryPhotos: () -> Unit,
    onRemoveLibraryPhoto: (java.io.File) -> Unit,
    onStartUpdate: () -> Unit,
    onCancelUpdate: () -> Unit,
    onRefreshDetail: () -> Unit,
) {
    val task = state.signTask
    val detail = state.signDetail
    val accent = MaterialTheme.colorScheme.primary.toArgb()
    // 已签到展示态：只读（云端照片 + 更新入口），不再显示围栏判定/编辑区
    val signedView = task?.category == FdmCategory.SIGNED && !state.updating
    // 可编辑 UI 但当前不可签：盖灰色蒙板（更新模式不受时段限制——服务端允许更新）
    val locked = !state.updating && task?.signableNow != true
    val lockText = if (task != null) "未在签到时间" else "今日暂无签到任务"

    // 位置状态文字（提交坐标系 BD-09 判定，与提交逻辑一致）
    val bd = state.effectiveBd09
    val fences = detail?.fences.orEmpty()
    // 来源与新旧：选点优先；设备定位超过 10 分钟提示偏旧（精度提示只对真实定位有意义）
    val fromPicked = state.pickedLng != null
    val staleHint = if (!fromPicked && state.deviceLocAt != null &&
        System.currentTimeMillis() - state.deviceLocAt!! > 10 * 60_000L
    ) "（位置较旧，建议重新获取）" else ""
    val statusText: String
    val statusColor: Color
    when {
        state.locating -> {
            statusText = "正在获取位置…"
            statusColor = MaterialTheme.colorScheme.onSurfaceVariant
        }
        bd == null -> {
            statusText = "未获取位置"
            statusColor = MaterialTheme.colorScheme.onSurfaceVariant
        }
        fences.isEmpty() -> {
            statusText = (if (fromPicked) "已选点" else "已获取位置") + staleHint
            statusColor = MaterialTheme.colorScheme.onSurfaceVariant
        }
        FdmRepository.inAnyFence(fences, bd[0], bd[1]) -> {
            val addr = FdmRepository.nearestFence(fences, bd[0], bd[1])?.address.orEmpty()
            statusText = (if (addr.isNotBlank()) "范围内 · $addr" else "范围内") + staleHint
            statusColor = SignedGreen
        }
        else -> {
            val nearest = FdmRepository.nearestFence(fences, bd[0], bd[1])
            val dist = nearest?.let {
                (FdmRepository.distanceMeters(bd[0], bd[1], it.longitude, it.latitude) - it.radius)
                    .roundToInt().coerceAtLeast(0)
            } ?: 0
            statusText = "范围外 · 距围栏约 ${dist}m" + staleHint
            statusColor = MaterialTheme.colorScheme.error
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            // 标题行：任务名（无任务骨架=「查寝签到」）+ 状态
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    task?.taskName ?: "查寝签到",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (task != null) StatusChip(task)
            }
            if (task != null) {
                Spacer(Modifier.height(4.dp))
                // 发布人 + 签到时段（并入打卡卡片，「当前任务」区不再重复显示本任务）
                Text(
                    "发布人：${task.senderUserName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "签到时段：${task.timeRange}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(2.dp))
            // 位置状态行：已签到展示态 = 云端签到位置/时间（已签到绿）；否则 = 实时围栏判定
            if (signedView) {
                val addr = detail?.signAddress
                if (!addr.isNullOrBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = SignedGreen,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            buildString {
                                append("签到位置 · ").append(addr)
                                detail.signTime?.takeIf { it.isNotBlank() }
                                    ?.let { append('\n').append(it) }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = SignedGreen,
                        )
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // 不支持的签到模式提示（扫码/IoT/NFC）
            detail?.unsupportedHint?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            // 拍照要求
            if (detail?.isPhoto == true && detail.photoTip.isNotBlank()) {
                Text(
                    "拍照要求：${detail.photoTip}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))

            if (signedView) {
                // 已签到展示态：云端签到照片（替换地图/选图/定位/拍照编辑区）；
                // URL 带签名参数约 5 分钟过期，失败时经 onRefreshDetail 换新 URL 重试
                detail?.signPhotoUrl?.takeIf { it.isNotBlank() }?.let {
                    SignPhotoImage(it, onRefreshUrl = onRefreshDetail)
                }
            } else {
                // 照片状态（右列选图框与下方按钮行共用）
                val thumb = state.photoThumb
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    // 左列：地图预览（正方形），点击进全屏选点页
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(10.dp)),
                    ) {
                        // 围栏 BD-09 -> GCJ-02；生效点 = 选点(GCJ-02) ?? 设备 GPS(WGS-84 -> GCJ-02)
                        val previewFences = remember(detail) {
                            fences.map {
                                val g = GeoTransform.bd09ToGcj02(it.longitude, it.latitude)
                                FenceGcj(g[0], g[1], it.radius, it.address)
                            }
                        }
                        val previewPoint = remember(
                            state.pickedLng, state.pickedLat, state.deviceLng, state.deviceLat,
                        ) {
                            val pl = state.pickedLng
                            val pt = state.pickedLat
                            val dl = state.deviceLng
                            val dt = state.deviceLat
                            when {
                                pl != null && pt != null -> GeoPoint(pt, pl)
                                dl != null && dt != null -> {
                                    val g = GeoTransform.wgs84ToGcj02(dl, dt)
                                    GeoPoint(g[1], g[0])
                                }
                                else -> null
                            }
                        }
                        FenceMapPreview(
                            fences = previewFences,
                            device = previewPoint,
                            accentColor = accent,
                            modifier = Modifier.fillMaxSize(),
                        )
                        // 透明点击层：进全屏选点页
                        Box(Modifier.fillMaxSize().clickable(onClick = onOpenMap))
                        // 蒙板：不可签（不在时段内/无任务）优先；可签但未获取位置时引导选点，
                        // 拿到位置（定位/选点）后自动撤掉；定位失败则保持
                        if (locked) {
                            LockedMask(lockText)
                        } else if (previewPoint == null) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.38f))
                                    .clickable(onClick = onOpenMap),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "点击选择位置",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))

                    // 右列：与地图同宽同高（1:1）的选图框——无照片：点击进相册的虚位框（图标+「选择图片」）；
                    // 有照片：缩略图（点击 = 相册重选）
                    Surface(
                        onClick = onGallery,
                        shape = RoundedCornerShape(10.dp),
                        color = if (thumb == null) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        else Color.Transparent,
                        border = if (thumb == null)
                            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(10.dp)),
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            if (thumb == null) {
                                Column(
                                    Modifier.fillMaxSize(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Icon(
                                        Icons.Default.Image,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(26.dp),
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        "选择图片",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            } else {
                                Image(
                                    bitmap = thumb.asImageBitmap(),
                                    contentDescription = "签到照片",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            if (locked) LockedMask(lockText)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))

                // 按钮行：左「获取当前位置」（地图下方）、右「拍照」（无照片）/「删除」（有照片）
                // 不可签时全部禁用
                Row(Modifier.fillMaxWidth()) {
                    LocateButton(
                        locating = state.locating,
                        enabled = !locked,
                        onClick = onLocate,
                        modifier = Modifier.weight(1f).height(40.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    if (thumb == null) {
                        OutlinedButton(
                            onClick = onCamera,
                            enabled = !locked,
                            modifier = Modifier.weight(1f).height(40.dp),
                        ) {
                            Icon(
                                Icons.Default.PhotoCamera,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("拍照", style = MaterialTheme.typography.labelMedium)
                        }
                    } else {
                        OutlinedButton(
                            onClick = onRemovePhoto,
                            enabled = !locked,
                            modifier = Modifier.weight(1f).height(40.dp),
                        ) {
                            Text(
                                "删除",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                // 一键签到照片库（仅拍照任务显示；不可签时也允许管理，方便提前准备）
                if (detail?.isPhoto == true) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "签到照片库",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "一键签到随机取用",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = onAddLibraryPhotos,
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(2.dp))
                            Text("添加", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    if (state.signLibrary.isEmpty()) {
                        Text(
                            "还未添加照片。拍照类任务使用一键签到前，请先添加一张或若干张宿舍照片",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    } else {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            state.signLibrary.forEach { f ->
                                LibraryThumb(f, onRemove = { onRemoveLibraryPhoto(f) })
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            // 底部全宽按钮条：禁用态也保留可见描边（用户要求按钮有边框），用 outlineVariant 弱描边
            when {
                // 已签到展示态：允许更新 = 全宽「更新签到」（primary 描边）；否则置灰禁用「已签到」
                signedView -> {
                    val updatable = detail?.canUpdate == true
                    OutlinedButton(
                        onClick = onStartUpdate,
                        enabled = updatable,
                        border = BorderStroke(
                            1.dp,
                            if (updatable) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                        ),
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                    ) {
                        Text(if (updatable) "更新签到" else "已签到")
                    }
                }
                // 无任务骨架：底部按钮不渲染
                task == null -> Unit
                // 可编辑态：可签（时段内或更新模式）= primary 文字+描边；签署中 = spinner；不可签 = 置灰禁用
                else -> {
                    val signable = (task.signableNow || state.updating) && !state.signing
                    // 一键签到：围栏内随机取点 + 照片库随机取图扰动上传（拍照任务需先入库）
                    OutlinedButton(
                        onClick = onOneClickSign,
                        enabled = signable,
                        border = BorderStroke(
                            1.dp,
                            if (signable) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                        ),
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                    ) {
                        if (state.signing) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                Icons.Default.Bolt,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(if (state.updating) "一键更新签到" else "一键签到")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onSign,
                        enabled = signable,
                        border = BorderStroke(
                            1.dp,
                            if (signable) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                        ),
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                    ) {
                        if (state.signing) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text(if (state.updating) "更新签到" else "手动签到")
                        }
                    }
                    // 更新模式的退出入口
                    if (state.updating) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            TextButton(onClick = onCancelUpdate) {
                                Text("取消", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 照片库缩略图：圆角小图 + 右上角删除角标（缩略图 IO 线程解码） */
@Composable
private fun LibraryThumb(file: java.io.File, onRemove: () -> Unit) {
    val bmp by produceState<Bitmap?>(null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                var sample = 1
                while (max(bounds.outWidth / sample, bounds.outHeight / sample) > 192) sample *= 2
                BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                )
            }.getOrNull()
        }
    }
    Box(Modifier.size(52.dp)) {
        bmp?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "照片库照片",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp)),
            )
        } ?: Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        // 删除角标
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .size(18.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(MaterialTheme.colorScheme.error)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "删除",
                tint = Color.White,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

/** 「获取当前位置」按钮：定位中显示小 spinner；不可签时禁用 */
@Composable
private fun LocateButton(
    locating: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 8.dp),
        modifier = modifier,
    ) {
        if (locating) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                Icons.Default.MyLocation,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            "获取当前位置",
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 不可签灰色蒙板：半透明遮盖 + 居中白色文案，空 clickable 拦截下层点击（圆角跟随外层 clip） */
@Composable
private fun LockedMask(text: String) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * 云端签到照片：URL 带签名参数约 5 分钟过期，进入即用 OkHttp 拉取（IO 线程）+ decodeStream。
 * 加载中 = 占位框 + 居中小 spinner；失败 = 自动重取详情换新 URL 重试（最多 2 次，
 * 防死循环），仍失败显示可点击的「照片加载失败，点击重试」。
 */
@Composable
private fun SignPhotoImage(url: String, onRefreshUrl: () -> Unit) {
    var bmp by remember(url) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }
    // 跨 URL 变化累计自动重试次数（同一 composable 实例内），防「换新 URL 仍失败→再换」死循环
    var autoRetries by remember { mutableStateOf(0) }
    // 手动重试触发器（URL 不变也能强制重新下载）
    var retryNonce by remember { mutableStateOf(0) }
    LaunchedEffect(url, retryNonce) {
        failed = false
        val r = withContext(Dispatchers.IO) {
            runCatching {
                OkHttpClient().newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) null
                    else resp.body?.byteStream()?.use { BitmapFactory.decodeStream(it) }
                }
            }.getOrNull()
        }
        if (r != null) bmp = r else failed = true
    }
    // 失败（多为签名 URL 过期）：自动重取详情换新 URL 再试
    LaunchedEffect(failed, url) {
        if (failed && autoRetries < 2) {
            autoRetries++
            onRefreshUrl()
        }
    }
    when {
        bmp != null ->
            // 方形小图，水平居中（Crop 裁成方形）
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Image(
                    bitmap = bmp!!.asImageBitmap(),
                    contentDescription = "签到照片",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(160.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
            }
        failed -> Text(
            "照片加载失败，点击重试",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable {
                // 手动重试：重置失败态并换新 URL（URL 不变则 nonce 强制重拉）
                autoRetries = 0
                retryNonce++
                onRefreshUrl()
            },
        )
        else -> Box(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

/**
 * 本月记录日历：7 列（周一~周日），行数随月份自适应。
 * 每天聚合状态：今日未签到=蓝圈；已过时间未签到=红✗；请假=黄「假」；已签到=绿✓；免签/无任务=灰。
 */
@Composable
private fun MonthCalendar(days: List<FdmDayTasks>, stats: li.lyc.lzuapp.data.repo.FdmStats?, todayTasks: List<FdmTask>) {
    // 每天的任务聚合（dayInMonth 形如 "2026-08-28"，容错非零填充）
    val byDay = remember(days) {
        days.mapNotNull { d ->
            val m = Regex("""(\d{4})\D+(\d{1,2})\D+(\d{1,2})""").find(d.dayInMonth)
            m?.let { it.groupValues[3].toInt() to d.tasks }
        }.toMap()
    }
    val now = java.util.Calendar.getInstance()
    val year = now.get(java.util.Calendar.YEAR)
    val month = now.get(java.util.Calendar.MONTH) // 0-based
    val today = now.get(java.util.Calendar.DAY_OF_MONTH)
    val daysInMonth = now.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
    // 本月 1 号是周几（周一=1 ... 周日=7）
    val firstDow = (java.util.Calendar.getInstance().apply {
        set(year, month,  1)
    }.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7 + 1
    val cells = (2 - firstDow)..daysInMonth // 1 号之前的占位用 <=0 表示

    val green = SignedGreen
    val red = MaterialTheme.colorScheme.error
    val blue = Color(0xFF1E88E5) // 鲜艳蓝（主题 primary 偏淡，日历里不醒目）
    val yellow = Color(0xFFF9A825)
    val gray = MaterialTheme.colorScheme.onSurfaceVariant

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            // 星期表头
            Row(Modifier.fillMaxWidth()) {
                listOf("一", "二", "三", "四", "五", "六", "日").forEach { d ->
                    Text(
                        d,
                        style = MaterialTheme.typography.labelSmall,
                        color = gray,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            // 周行（末行不足 7 天补空格占位，保持列对齐——否则 31 号会居中到错误星期）
            cells.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    week.forEach { day ->
                        Box(
                            Modifier.weight(1f).height(40.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (day >= 1) {
                                // 今日任务可能尚未进月报：用当前任务兜底
                                val tasks = if (day == today && byDay[day].isNullOrEmpty())
                                    todayTasks else byDay[day]
                                val status = dayStatus(tasks, day, today)
                                DayCell(day, status, green, red, blue, yellow, gray)
                            }
                        }
                    }
                    repeat(7 - week.size) {
                        Box(Modifier.weight(1f).height(40.dp))
                    }
                }
            }
            // 本月统计（并入日历卡底部）
            if (stats != null) {
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        "已签 ${stats.signNum} · 未签 ${stats.unSignNum} · 请假 ${stats.leaveNum}",
                        style = MaterialTheme.typography.labelMedium,
                        color = gray,
                    )
                }
            }
        }
    }
}

private enum class DayStatus { NONE, SIGNED, MISSED, PENDING, LEAVE }

/**
 * 一天的聚合状态：多个任务取「最需要关注」的那个。
 * 注意 fdm 的 registerLeaveTasks 是登记类任务（数组归类可能滞后于签到时段切换）：
 * 今天/未来出现的登记任务=待签（蓝圈），过去的一律视为已免签（灰）。
 * 待签（无论是否到时间）→ 蓝圈；过去未签 → 红叉；请假 → 黄「假」；已签 → 绿勾；免签/无任务 → 灰。
 */
private fun dayStatus(tasks: List<FdmTask>?, day: Int, today: Int): DayStatus {
    if (tasks.isNullOrEmpty()) return DayStatus.NONE
    // 需要签到但还没签：未签任务或登记类任务
    val pending = { t: FdmTask ->
        t.category == FdmCategory.UNSIGNED || t.category == FdmCategory.REGISTER_LEAVE
    }
    return when {
        day >= today && tasks.any(pending) -> DayStatus.PENDING
        day < today && tasks.any { it.category == FdmCategory.UNSIGNED } -> DayStatus.MISSED
        tasks.any { it.category == FdmCategory.LEAVE } -> DayStatus.LEAVE
        tasks.any { it.category == FdmCategory.SIGNED } -> DayStatus.SIGNED
        else -> DayStatus.NONE // 免签等
    }
}

@Composable
private fun DayCell(
    day: Int,
    status: DayStatus,
    green: Color,
    red: Color,
    blue: Color,
    yellow: Color,
    gray: Color,
) {
    when (status) {
        DayStatus.SIGNED -> Box(
            Modifier.size(30.dp).background(green, RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Check, contentDescription = "已签到", tint = Color.White, modifier = Modifier.size(16.dp))
        }
        DayStatus.MISSED -> Box(
            Modifier.size(30.dp).background(red, RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Close, contentDescription = "未签到", tint = Color.White, modifier = Modifier.size(16.dp))
        }
        DayStatus.PENDING -> Box(
            Modifier.size(30.dp).border(2.5.dp, blue, RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) {
            Text("$day", style = MaterialTheme.typography.labelMedium, color = blue, fontWeight = FontWeight.Bold)
        }
        DayStatus.LEAVE -> Box(
            Modifier.size(30.dp).background(yellow, RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) {
            Text("假", style = MaterialTheme.typography.labelMedium, color = Color.White, fontWeight = FontWeight.Bold)
        }
        DayStatus.NONE -> Text("$day", style = MaterialTheme.typography.labelMedium, color = gray)
    }
}

/** 状态小标签：已签到=绿；未签到在时段内=警示色、时段外=「未在签到时间」；免签(时段内)/请假=中性色 */
@Composable
private fun StatusChip(task: FdmTask) {
    val neutral = MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    val (label, bg, fg) = when (task.category) {
        FdmCategory.SIGNED -> Triple("已签到", SignedGreen.copy(alpha = 0.15f), SignedGreen)
        FdmCategory.UNSIGNED ->
            if (task.inSignWindow) {
                Triple("未签到", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.error)
            } else {
                Triple("未在签到时间", neutral.first, neutral.second)
            }
        FdmCategory.REGISTER_LEAVE ->
            // 登记类任务：时段内需要完成签到（服务端数组归类可能滞后于签到时段切换，
            // 标「免签」会误导用户漏签），时段外按未开始处理
            if (task.inSignWindow) {
                Triple("未签到", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.error)
            } else {
                Triple("未在签到时间", neutral.first, neutral.second)
            }
        FdmCategory.LEAVE -> Triple("已请假", neutral.first, neutral.second)
    }
    Text(
        label,
        color = fg,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .background(bg, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** DormLoc 日志：logcat + fdm_debug.log 双写（该机 logcat 缓冲旋转极快，落盘才查得到）；仅 debug 包启用 */
private fun locLog(context: Context, msg: String) {
    if (!li.lyc.lzuapp.BuildConfig.DEBUG) return
    Log.d("DormLoc", msg)
    runCatching {
        (context.applicationContext as KcbApplication).container.fdmApi.logRepo("LOC $msg")
    }
}

/**
 * 取一次定位（规范做法：持续监听所有已启用 provider，谁先来用谁）：
 * 1) 30 分钟内的系统缓存定位直接用（秒回）
 * 2) 系统定位总开关关闭直接失败（让 UI 提示去开）
 * 3) 对所有已启用 provider 注册 requestLocationUpdates（fused/network/gps/…），
 *    25s 窗口内首个 fix 返回——one-shot getCurrentLocation 在 GMS 网络定位残废、
 *    室内 GPS 冷启动的环境下不可靠；持续监听能吃 AGPS 辅助与各 provider 缓存推送
 * 4) 超时退回任意时效缓存；仍无则 null
 * 调用前需已授予定位权限。
 */
@SuppressLint("MissingPermission")
private suspend fun fetchLocation(context: Context): Location? {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    fun lastKnown(maxAgeMs: Long): Location? {
        val now = System.currentTimeMillis()
        return runCatching { lm.getProviders(true) }.getOrNull().orEmpty()
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .filter { now - it.time < maxAgeMs }
            .maxByOrNull { it.time }
    }
    lastKnown(1_800_000)?.let {
        locLog(context, "hit lastKnown(30min): ${it.provider} ${it.longitude},${it.latitude}")
        return it
    }
    val masterOn = runCatching { LocationManagerCompat.isLocationEnabled(lm) }.getOrDefault(false)
    if (!masterOn) locLog(context, "location master switch OFF（仍尝试高德网络定位）")
    // 高德网络定位优先（室内可用；HightAccuracy 模式本身融合 GPS，室外不吃亏）：
    // 返回 GCJ-02，这里归一化为 WGS-84 再走统一管线
    amapLocate(context)?.let { return it }
    val providers = runCatching { lm.getProviders(true) }.getOrNull().orEmpty()
    locLog(context, "enabled providers: $providers")
    if (!masterOn || providers.isEmpty()) return lastKnown(Long.MAX_VALUE)

    val live = withTimeoutOrNull(25_000) {
        suspendCancellableCoroutine<Location?> { cont ->
            val listeners = mutableListOf<LocationListener>()
            providers.forEach { p ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(loc: Location) {
                        if (cont.isActive) {
                            // 正常 resume 不走 invokeOnCancellation：先摘除全部监听，防 GPS 常驻泄漏
                            listeners.forEach { runCatching { lm.removeUpdates(it) } }
                            locLog(context, "fix from $p: ${loc.longitude},${loc.latitude} acc=${loc.accuracy}")
                            cont.resume(loc)
                        }
                    }
                }
                runCatching {
                    // 统一用传统 4 参签名（全 API 兼容）；LocationRequest.Builder(0L) 的 0 间隔
                    // 在部分实现里直接抛 IllegalArgumentException，导致监听一个都注册不上
                    lm.requestLocationUpdates(p, 1_000L, 0f, listener, Looper.getMainLooper())
                }.onFailure { e ->
                    locLog(context, "requestLocationUpdates $p failed: ${e.javaClass.simpleName} ${e.message}")
                }
                listeners += listener
            }
            cont.invokeOnCancellation {
                listeners.forEach { runCatching { lm.removeUpdates(it) } }
            }
        }
    }
    if (live != null) return live
    val stale = lastKnown(Long.MAX_VALUE)
    locLog(context, "live fix timeout(25s), stale=$stale")
    return stale
}

/**
 * 高德单次定位（网络定位，室内兜底主通道）：
 * 返回 WGS-84 的 Location（高德原生 GCJ-02，已归一化）；失败/超时返回 null。
 */
@SuppressLint("MissingPermission")
private suspend fun amapLocate(context: Context): Location? = withTimeoutOrNull(12_000) {
    val customKey = runCatching {
        (context.applicationContext as KcbApplication).container.authStore.current().amapApiKey
    }.getOrDefault("")
    val apiKey = customKey.ifEmpty {
        context.applicationInfo.metaData?.getString("com.amap.api.v2.apikey").orEmpty()
    }
    suspendCancellableCoroutine { cont ->
        var client: com.amap.api.location.AMapLocationClient? = null
        fun cleanup() {
            runCatching { client?.stopLocation() }
            runCatching { client?.onDestroy() }
        }
        try {
            com.amap.api.location.AMapLocationClient.setApiKey(apiKey)
            val c = com.amap.api.location.AMapLocationClient(context.applicationContext)
            client = c
            c.setLocationOption(com.amap.api.location.AMapLocationClientOption().apply {
                // 官方「签到」场景（专为打卡类设计），参数由 SDK 按场景定制
                locationPurpose = com.amap.api.location.AMapLocationClientOption.AMapLocationPurpose.SignIn
                locationMode = com.amap.api.location.AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                isOnceLocation = true        // 单次定位（拿一次就停）
                isOnceLocationLatest = true  // 取最近 3s 内精度最高的一次
                isNeedAddress = false
                httpTimeOut = 10_000
            })
            c.setLocationListener { loc ->
                if (!cont.isActive) return@setLocationListener
                if (loc != null && loc.errorCode == 0) {
                    locLog(
                        context,
                        "amap fix: ${loc.longitude},${loc.latitude} type=${loc.locationType} acc=${loc.accuracy}",
                    )
                    val wgs = GeoTransform.gcj02ToWgs84(loc.longitude, loc.latitude)
                    cont.resume(Location("amap").apply {
                        longitude = wgs[0]
                        latitude = wgs[1]
                        accuracy = loc.accuracy
                    })
                } else {
                    // errorCode 23=权限/开关，27=key 与包名/SHA1 不匹配，32=鉴权失败等
                    locLog(context, "amap err: code=${loc?.errorCode} info=${loc?.errorInfo}")
                    cont.resume(null)
                }
                cleanup()
            }
            c.startLocation()
            cont.invokeOnCancellation { cleanup() }
        } catch (e: Exception) {
            locLog(context, "amap exception: ${e.javaClass.simpleName} ${e.message}")
            cleanup()
            if (cont.isActive) cont.resume(null)
        }
    }
}

/** 照片压缩入口（相机文件）：EXIF 旋正 + 长边 ≤1280，JPEG 85；返回 (字节, 缩略图) */
private fun compressPhoto(file: File): Pair<ByteArray, Bitmap>? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    val bmp = BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds) },
    ) ?: return null
    val rotation = runCatching {
        ExifInterface(file.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    return scaleAndCompress(bmp, rotation)
}

/**
 * 照片压缩入口（相册 Uri）：
 * API 28+ 用 ImageDecoder（自动处理 HEIC/EXIF 旋正；必须 SOFTWARE allocator 否则无法压缩）；
 * 更低版本走 openFileDescriptor + decodeFileDescriptor（部分 ROM 对 decodeStream 兼容性差）。
 */
private fun compressPhoto(context: Context, uri: Uri): Pair<ByteArray, Bitmap>? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val bmp = runCatching {
            ImageDecoder.decodeBitmap(
                ImageDecoder.createSource(context.contentResolver, uri),
            ) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                if (w > 0 && h > 0 && max(w, h) > 1280) {
                    val scale = 1280f / max(w, h)
                    decoder.setTargetSize((w * scale).roundToInt(), (h * scale).roundToInt())
                }
            }
        }.getOrNull() ?: return null
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return out.toByteArray() to bmp
    }
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openFileDescriptor(uri, "r")?.use { pfd ->
        BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, bounds)
    } ?: return null
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val rotation = resolver.openFileDescriptor(uri, "r")?.use { pfd ->
        runCatching {
            ExifInterface(pfd.fileDescriptor).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    } ?: ExifInterface.ORIENTATION_NORMAL
    val bmp = resolver.openFileDescriptor(uri, "r")?.use { pfd ->
        BitmapFactory.decodeFileDescriptor(
            pfd.fileDescriptor, null,
            BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds) },
        )
    } ?: return null
    return scaleAndCompress(bmp, rotation)
}

/** 按长边 ≤1280 计算 inSampleSize（2 的幂） */
private fun sampleSize(bounds: BitmapFactory.Options): Int {
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return 0
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / sample > 1280) sample *= 2
    return sample
}

/** inSampleSize 取 2 的幂，可能仍略超 1280，再精确缩放一次后压 JPEG 85；按 EXIF 方向旋正 */
private fun scaleAndCompress(bmp: Bitmap, orientation: Int = ExifInterface.ORIENTATION_NORMAL): Pair<ByteArray, Bitmap> {
    val rotated = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90,
        ExifInterface.ORIENTATION_ROTATE_180,
        ExifInterface.ORIENTATION_ROTATE_270,
        -> {
            val deg = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                else -> 270f
            }
            val m = android.graphics.Matrix().apply { postRotate(deg) }
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        else -> bmp
    }
    val scaled = if (max(rotated.width, rotated.height) > 1280) {
        val scale = 1280f / max(rotated.width, rotated.height)
        Bitmap.createScaledBitmap(
            rotated,
            (rotated.width * scale).roundToInt(),
            (rotated.height * scale).roundToInt(),
            true,
        )
    } else rotated
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
    return out.toByteArray() to scaled
}
