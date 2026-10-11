package li.lyc.lzuapp.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import li.lyc.lzuapp.util.PhotoDisturb
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 一键签到照片库：用户预先放入若干张签到照片，一键签到时随机取一张，
 * 经 [PhotoDisturb] 扰动重编码后上传（每次上传的像素级内容都不同，防查重）。
 * 存储在应用私有目录 filesDir/sign_library/（退出登录清除数据时随应用数据一并清除）。
 */
class SignPhotoLibrary(context: Context) {

    private val dir = File(context.filesDir, "sign_library")

    /** 照片库文件列表（按文件名排序 = 按添加时间排序） */
    fun list(): List<File> =
        dir.listFiles()?.filter { it.isFile && it.length() > 0 }?.sortedBy { it.name } ?: emptyList()

    /** 添加一张照片（解码 + 长边 ≤1280 + JPEG 85 落库；重复内容按时间戳区分） */
    fun add(resolver: ContentResolver, uri: Uri): Boolean {
        dir.mkdirs()
        val bmp = decode(resolver, uri) ?: return false
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        val f = File(dir, "lib_${System.currentTimeMillis()}_${(0..999).random()}.jpg")
        return runCatching { f.writeBytes(out.toByteArray()) }.isSuccess
    }

    fun remove(file: File) {
        file.delete()
    }

    /** 随机取一张并扰动重编码；库为空或解码失败返回 null */
    fun randomDisturbed(): ByteArray? {
        val files = list()
        if (files.isEmpty()) return null
        val bmp = BitmapFactory.decodeFile(files.random().absolutePath) ?: return null
        return PhotoDisturb.process(bmp)
    }

    /** 解码 Uri（长边 ≤1280）；API 28+ 走 ImageDecoder（HEIC/EXIF 旋正），低版本 decodeStream */
    private fun decode(resolver: ContentResolver, uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return runCatching {
                android.graphics.ImageDecoder.decodeBitmap(
                    android.graphics.ImageDecoder.createSource(resolver, uri),
                ) { decoder, info, _ ->
                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                    val w = info.size.width
                    val h = info.size.height
                    if (w > 0 && h > 0 && max(w, h) > 1280) {
                        val scale = 1280f / max(w, h)
                        decoder.setTargetSize((w * scale).roundToInt(), (h * scale).roundToInt())
                    }
                }
            }.getOrNull()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = run {
            var s = 1
            while (max(bounds.outWidth / s, bounds.outHeight / s) > 1280) s *= 2
            s
        }
        return resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
}
