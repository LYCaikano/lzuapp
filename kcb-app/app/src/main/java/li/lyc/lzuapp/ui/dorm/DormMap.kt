package li.lyc.lzuapp.ui.dorm

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import li.lyc.lzuapp.data.repo.FdmRepository
import li.lyc.lzuapp.util.GeoTransform
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/** 高德公开栅格瓦片（GCJ-02，style=8 矢量底图），webrd01~04 轮询；UA 在 Application 初始化 */
private class AmapTileSource : OnlineTileSourceBase(
    "AmapVector", 0, 18, 256, ".png",
    arrayOf(
        "https://webrd01.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8",
        "https://webrd02.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8",
        "https://webrd03.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8",
        "https://webrd04.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8",
    ),
) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        getBaseUrl() + "&x=" + MapTileIndex.getX(pMapTileIndex) +
            "&y=" + MapTileIndex.getY(pMapTileIndex) + "&z=" + MapTileIndex.getZoom(pMapTileIndex)
}

/** 围栏数据（已转 GCJ-02，地图显示用） */
data class FenceGcj(val lng: Double, val lat: Double, val radiusM: Double, val address: String)

/** 围栏圆 + 地址标注 + 设备位置点覆盖层（坐标均为 GCJ-02） */
private class FenceOverlay(
    private val fences: List<FenceGcj>,
    private val device: GeoPoint?,
    accentColor: Int,
) : Overlay() {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = android.graphics.Color.argb(
            36,
            android.graphics.Color.red(accentColor),
            android.graphics.Color.green(accentColor),
            android.graphics.Color.blue(accentColor),
        )
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = android.graphics.Color.argb(
            200,
            android.graphics.Color.red(accentColor),
            android.graphics.Color.green(accentColor),
            android.graphics.Color.blue(accentColor),
        )
    }
    // 地址标注：白色描边 + 深灰填充，保证在瓦片上可读
    private val textStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 30f
        textAlign = Paint.Align.CENTER
        style = Paint.Style.STROKE
        strokeWidth = 7f
        color = android.graphics.Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
    }
    private val textFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 30f
        textAlign = Paint.Align.CENTER
        color = android.graphics.Color.DKGRAY
        typeface = Typeface.DEFAULT_BOLD
    }
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
    // 生效位置点：红色实心（醒目）
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(229, 57, 53) }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val pj = mapView.projection
        fences.forEach { f ->
            val pt = pj.toPixels(GeoPoint(f.lat, f.lng), null)
            val rPx = pj.metersToPixels(f.radiusM.toFloat(), f.lat, mapView.zoomLevelDouble)
            canvas.drawCircle(pt.x.toFloat(), pt.y.toFloat(), rPx, fillPaint)
            canvas.drawCircle(pt.x.toFloat(), pt.y.toFloat(), rPx, strokePaint)
            if (f.address.isNotBlank()) {
                val ty = pt.y.toFloat() - rPx - 14f
                canvas.drawText(f.address, pt.x.toFloat(), ty, textStroke)
                canvas.drawText(f.address, pt.x.toFloat(), ty, textFill)
            }
        }
        // 生效位置小圆点（白描边 + 红色实心）
        device?.let {
            val pt = pj.toPixels(it, null)
            canvas.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 18f, dotRing)
            canvas.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 12f, dotPaint)
        }
    }
}

/** MapView 生命周期跟随 Compose：ON_RESUME/ON_PAUSE 转发，离开组合时 onDetach */
@Composable
private fun rememberMapView(vararg keys: Any?, configure: MapView.() -> Unit): MapView {
    val context = LocalContext.current
    val mapView = remember(*keys) {
        MapView(context).apply {
            setTileSource(AmapTileSource())
            minZoomLevel = 3.0
            maxZoomLevel = 18.0
            // 高 dpi 下放大瓦片，保证地图文字可读
            isTilesScaledToDpi = true
            configure()
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }
    return mapView
}

/**
 * 打卡卡片内嵌的静态地图预览：禁交互仅展示（点击由上层 Box 拦截进全屏选点）。
 * 覆盖层与中心随数据更新：生效点（选点/定位）出现后即画出红点并把中心移过去，
 * 否则居中首个围栏。注意 AndroidView 的 factory 只跑一次，不能用 remember(keys)
 * 重建 MapView 来响应数据变化（会显示旧实例），必须在 LaunchedEffect 里原地更新。
 */
@Composable
fun FenceMapPreview(
    fences: List<FenceGcj>,
    device: GeoPoint?,
    accentColor: Int,
    modifier: Modifier = Modifier,
) {
    val mapView = rememberMapView(Unit) {
        setMultiTouchControls(false)
        @Suppress("ClickableViewAccessibility")
        setOnTouchListener { _, _ -> true }
        controller.setZoom(15.0)
    }
    // 数据变化时原地更新覆盖层与中心（初始无点时先居中围栏）
    LaunchedEffect(fences, device, accentColor) {
        mapView.overlays.removeAll { it is FenceOverlay }
        mapView.overlays.add(FenceOverlay(fences, device, accentColor))
        val center = device ?: fences.firstOrNull()?.let { GeoPoint(it.lat, it.lng) }
        if (center != null) {
            mapView.controller.setZoom(if (device != null) 16.0 else 15.0)
            mapView.controller.setCenter(center)
        }
        mapView.invalidate()
    }
    AndroidView(factory = { mapView }, modifier = modifier)
}

/**
 * 全屏地图选点页：中心固定图钉（拖动地图取中心点为选点），叠加围栏圆与地址标注；
 * 初始中心 = 已选点 ?? 围栏 ?? 设备位置；右上角定位 FAB 回到设备位置。
 * 确认回调 GCJ-02 坐标（地图坐标系）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapPickerPage(
    state: DormUiState,
    accentColor: Int,
    onConfirm: (lng: Double, lat: Double) -> Unit,
    onLocateMe: () -> Unit,
    onClose: () -> Unit,
) {
    val detail = state.signDetail
    // 围栏 BD-09 -> GCJ-02（显示坐标系）
    val fences = remember(detail) {
        detail?.fences?.map {
            val gcj = GeoTransform.bd09ToGcj02(it.longitude, it.latitude)
            FenceGcj(gcj[0], gcj[1], it.radius, it.address)
        }.orEmpty()
    }
    // 设备 GPS WGS-84 -> GCJ-02
    val deviceGcj = remember(state.deviceLng, state.deviceLat) {
        val lng = state.deviceLng
        val lat = state.deviceLat
        if (lng != null && lat != null) {
            val g = GeoTransform.wgs84ToGcj02(lng, lat)
            GeoPoint(g[1], g[0])
        } else null
    }
    // 生效点（选点优先，否则设备位置）：覆盖层红点画的是它，与打卡卡片预览一致
    val effectiveGcj = remember(state.pickedLng, state.pickedLat, deviceGcj) {
        val pl = state.pickedLng
        val pt = state.pickedLat
        if (pl != null && pt != null) GeoPoint(pt, pl) else deviceGcj
    }
    // 初始中心：已选点 > 设备位置 > 围栏 > 兰州（兜底）。
    // 设备位置优先于围栏：用户定位过就认为那是 TA 当前真实位置，选点从它开始微调
    val initialCenter = remember {
        effectiveGcj
            ?: fences.firstOrNull()?.let { GeoPoint(it.lat, it.lng) }
            ?: GeoPoint(36.0611, 103.8343)
    }
    // 仅用于底部地址文字联动；确认时读取地图实时中心，不依赖本状态（防止动画/监听器时序导致取到旧值）
    var centerPoint by remember { mutableStateOf<GeoPoint?>(initialCenter) }

    val mapView = rememberMapView(Unit) {
        setMultiTouchControls(true)
        controller.setZoom(if (initialCenter != null) 16.0 else 10.0)
        controller.setCenter(initialCenter)
        addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent): Boolean {
                centerPoint = GeoPoint(mapCenter.latitude, mapCenter.longitude)
                return false
            }

            override fun onZoom(event: ZoomEvent): Boolean {
                centerPoint = GeoPoint(mapCenter.latitude, mapCenter.longitude)
                return false
            }
        })
    }

    // 围栏/生效点覆盖层随数据更新（红点 = 选点 ?? 设备位置）
    LaunchedEffect(fences, effectiveGcj, accentColor) {
        mapView.overlays.removeAll { it is FenceOverlay }
        mapView.overlays.add(FenceOverlay(fences, effectiveGcj, accentColor))
        mapView.invalidate()
    }
    // 无选点且无围栏时，设备位置就绪后移到设备位置（进页面时刚授予权限的场景）
    LaunchedEffect(deviceGcj) {
        if (deviceGcj != null && state.pickedLng == null && fences.isEmpty()) {
            mapView.controller.animateTo(deviceGcj)
        }
    }
    // 无选点且无设备位置时，围栏晚到（signDetail 异步加载）也要把视角移到首个围栏，
    // 否则只画了覆盖层而视角停在兰州城关兜底点
    LaunchedEffect(fences) {
        if (fences.isNotEmpty() && state.pickedLng == null && deviceGcj == null) {
            mapView.controller.animateTo(GeoPoint(fences[0].lat, fences[0].lng))
        }
    }

    // 底部选点地址：在哪个围栏内显示围栏地址，否则显示坐标（GCJ 转 BD 与围栏比较）
    val centerText = centerPoint?.let { p ->
        val bd = GeoTransform.gcj02ToBd09(p.longitude, p.latitude)
        detail?.fences?.firstOrNull {
            FdmRepository.distanceMeters(bd[0], bd[1], it.longitude, it.latitude) <= it.radius
        }?.address ?: "%.5f, %.5f".format(p.latitude, p.longitude)
    } ?: "拖动地图选择地点"

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    title = { Text("选择签到地点") },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
                // 中心固定图钉（钉尖对准地图中心，红色系与生效点一致）
                Icon(
                    Icons.Default.Place,
                    contentDescription = null,
                    tint = androidx.compose.ui.graphics.Color(0xFFE53935),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(44.dp)
                        .offset(y = (-22).dp),
                )
                // 定位 FAB：回到设备位置；无设备位置时走权限/定位流程
                SmallFloatingActionButton(
                    onClick = {
                        if (deviceGcj != null) mapView.controller.animateTo(deviceGcj)
                        else onLocateMe()
                    },
                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
                ) {
                    Icon(Icons.Default.MyLocation, contentDescription = "定位")
                }
                // 底部确认栏
                Surface(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                    tonalElevation = 3.dp,
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            centerText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                        )
                        Spacer(Modifier.width(12.dp))
                        Button(onClick = {
                            // 读地图实时中心：FAB 归位/惯性滑动等路径未必触发监听器，
                            // 用 centerPoint 状态可能拿到旧中心导致"选点不生效"
                            val c = mapView.mapCenter
                            onConfirm(c.longitude, c.latitude)
                        }) {
                            Text("使用该地点")
                        }
                    }
                }
            }
        }
    }
}
