package li.lyc.lzuapp.util

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 兰州大学 App 的 AES-CBC-NoPadding 加密（密钥硬编码，key = IV）
 */
object LzuCrypto {

    // 密钥构成: "QSfg2" + res/string(ak2="6hg4") + p(3,12)=33 + "BV42a"
    const val KEY: String = "QSfg26hg433BV42a"

    fun encryptToHex(plainText: String): String {
        val keyBytes = KEY.toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(keyBytes))
        val data = plainText.toByteArray(Charsets.UTF_8)
        val block = cipher.blockSize
        val padded = if (data.size % block == 0) data
            else data.copyOf(data.size + (block - data.size % block))
        return cipher.doFinal(padded).toHexString()
    }

    fun decryptHex(hex: String): String {
        val keyBytes = KEY.toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(keyBytes))
        val plain = cipher.doFinal(hex.fromHexString())
        // 去掉尾部 0 填充
        var end = plain.size
        while (end > 0 && plain[end - 1] == 0.toByte()) end--
        return String(plain, 0, end, Charsets.UTF_8)
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

    private fun String.fromHexString(): ByteArray {
        val result = ByteArray(length / 2)
        for (i in result.indices) {
            result[i] = substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return result
    }
}
