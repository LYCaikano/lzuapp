package li.lyc.lzuapp.util

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 坐标系转换（公开标准算法，eviltransform）：
 * - 设备 GPS 按 WGS-84 处理，高德瓦片用 GCJ-02，辅导猫围栏/提交坐标用百度 BD-09
 * - 地图显示统一 GCJ-02；提交签到统一 BD-09
 * 返回 DoubleArray(经度, 纬度)。
 */
object GeoTransform {

    private const val A = 6378245.0
    private const val EE = 0.006693421622965943
    private const val X_PI = Math.PI * 3000.0 / 180.0

    /** WGS-84 -> GCJ-02（国外坐标不偏移，原样返回） */
    fun wgs84ToGcj02(lng: Double, lat: Double): DoubleArray {
        if (outOfChina(lng, lat)) return doubleArrayOf(lng, lat)
        var dLat = transformLat(lng - 105.0, lat - 35.0)
        var dLng = transformLng(lng - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * Math.PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * Math.PI)
        dLng = (dLng * 180.0) / (A / sqrtMagic * cos(radLat) * Math.PI)
        return doubleArrayOf(lng + dLng, lat + dLat)
    }

    /** GCJ-02 -> 百度 BD-09 */
    fun gcj02ToBd09(lng: Double, lat: Double): DoubleArray {
        val z = sqrt(lng * lng + lat * lat) + 0.00002 * sin(lat * X_PI)
        val theta = atan2(lat, lng) + 0.000003 * cos(lng * X_PI)
        return doubleArrayOf(z * cos(theta) + 0.0065, z * sin(theta) + 0.006)
    }

    /** 百度 BD-09 -> GCJ-02 */
    fun bd09ToGcj02(lng: Double, lat: Double): DoubleArray {
        val x = lng - 0.0065
        val y = lat - 0.006
        val z = sqrt(x * x + y * y) - 0.00002 * sin(y * X_PI)
        val theta = atan2(y, x) - 0.000003 * cos(x * X_PI)
        return doubleArrayOf(z * cos(theta), z * sin(theta))
    }

    /** GCJ-02 -> WGS-84（近似反解：正向偏差反向减，误差 1m 级；高德定位结果归一化用） */
    fun gcj02ToWgs84(lng: Double, lat: Double): DoubleArray {
        if (outOfChina(lng, lat)) return doubleArrayOf(lng, lat)
        val gcj = wgs84ToGcj02(lng, lat)
        return doubleArrayOf(lng * 2 - gcj[0], lat * 2 - gcj[1])
    }

    /** WGS-84 -> BD-09（提交设备位置用） */
    fun wgs84ToBd09(lng: Double, lat: Double): DoubleArray {
        val gcj = wgs84ToGcj02(lng, lat)
        return gcj02ToBd09(gcj[0], gcj[1])
    }

    private fun outOfChina(lng: Double, lat: Double): Boolean =
        lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * Math.PI) + 40.0 * sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * Math.PI) + 320.0 * sin(y * Math.PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * Math.PI) + 40.0 * sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * Math.PI) + 300.0 * sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
        return ret
    }
}
