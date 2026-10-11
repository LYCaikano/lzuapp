package li.lyc.lzuapp.util

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.util.Random
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 签到照片自然化扰动（参考 fudaomao-auto-check-in run.py 的 disturb_photo 一比一移植）：
 * 几何微缩放 + 光度抖动（gamma/亮度 LUT）+ 亮度自适应高斯噪声，
 * 让每次上传的照片像一次独立的重拍/转存（打散 pHash 与 JPEG 网格对齐，
 * 噪声暗部小亮部大、跨通道相关，接近真实传感器），防照片级查重比对。
 * 最后 JPEG 重编码（质量随机），EXIF 随重编码自然去除。
 */
object PhotoDisturb {

    private val random = Random()

    /** 扰动 + JPEG 重编码；输入任意尺寸 Bitmap（内部先压到长边 ≤1600） */
    fun process(src: Bitmap): ByteArray {
        // 0. 长边 ≤1600（对齐参考实现 thumbnail(1600)）
        var bmp = src
        val maxSide = maxOf(bmp.width, bmp.height)
        if (maxSide > 1600) {
            val s = 1600f / maxSide
            bmp = Bitmap.createScaledBitmap(
                bmp, (bmp.width * s).roundToInt(), (bmp.height * s).roundToInt(), true,
            )
        }

        // 1. 几何微变换：0.96~1.0 随机缩放
        val scale = 0.96f + random.nextFloat() * 0.04f
        val w = (bmp.width * scale).toInt().coerceAtLeast(32)
        val h = (bmp.height * scale).toInt().coerceAtLeast(32)
        if (w != bmp.width || h != bmp.height) {
            bmp = Bitmap.createScaledBitmap(bmp, w, h, true)
        }

        // 2. 光度抖动：gamma 0.95~1.05，亮度 ±3（LUT 查表）
        val gamma = 0.95 + random.nextDouble() * 0.10
        val bright = random.nextInt(7) - 3
        val lut = IntArray(256) { i ->
            (255.0 * (i / 255.0).pow(gamma) + bright).toInt().coerceIn(0, 255)
        }
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val p = pixels[i]
            pixels[i] = (0xFF shl 24) or
                (lut[(p shr 16) and 0xFF] shl 16) or
                (lut[(p shr 8) and 0xFF] shl 8) or
                lut[p and 0xFF]
        }

        // 3. 亮度自适应高斯噪声：σ = σ0 + k·√(L/255)；跨通道相关分量 + 每通道 0.3σ 独立分量
        val sigma0 = 0.6 + random.nextDouble() * 0.8
        val k = 0.6 + random.nextDouble() * 0.9
        for (i in pixels.indices) {
            val p = pixels[i]
            val r0 = (p shr 16) and 0xFF
            val g0 = (p shr 8) and 0xFF
            val b0 = p and 0xFF
            val lum = 0.299 * r0 + 0.587 * g0 + 0.114 * b0
            val sigma = sigma0 + k * sqrt(lum / 255.0 + 0.01)
            val common = random.nextGaussian() * sigma
            val inner = sigma * 0.3
            val r = (r0 + common + random.nextGaussian() * inner).toInt().coerceIn(0, 255)
            val g = (g0 + common + random.nextGaussian() * inner).toInt().coerceIn(0, 255)
            val b = (b0 + common + random.nextGaussian() * inner).toInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)

        // 4. JPEG 重编码：质量 72~92 随机（EXIF 随重编码自然去除）
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 72 + random.nextInt(21), out)
        if (bmp !== src) bmp.recycle()
        return out.toByteArray()
    }
}
