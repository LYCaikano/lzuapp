package li.lyc.lzuapp.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.dataStore by preferencesDataStore(name = "auth")

/**
 * 敏感字段 at-rest 加密：Android Keystore AES/GCM（密钥不可导出）。
 * 密文格式 "v1:" + Base64(iv(12B) + cipher)。无前缀的历史明文按原样读出（向后兼容），
 * 由 migrateEncryption() 或下次写入时升级为密文。
 */
private object CryptoBox {
    private const val KEY_ALIAS = "lzu_auth_key"
    private const val PREFIX = "v1:"

    // Keystore load/getEntry 是 IPC 调用，开销大；SecretKey 句柄可安全复用，只初始化一次
    private val secretKey: SecretKey by lazy {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: run {
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            gen.generateKey()
        }
    }

    /**
     * 解密结果记忆化：Cipher.init 走 Keystore IPC（数 ms 级），DataStore 每次发射要解 9 个字段，
     * 不解缓存会在主线程造成明显卡顿。密文→明文是纯函数，按密文缓存安全。
     */
    private val decCache = java.util.concurrent.ConcurrentHashMap<String, String>(32)

    fun enc(plain: String): String {
        if (plain.isEmpty() || plain.startsWith(PREFIX)) return plain
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
                .let { PREFIX + it }
        }.getOrElse {
            // Keystore 故障不应静默回落明文（落盘即明文泄露）；至少留日志
            android.util.Log.e("CryptoBox", "enc failed, storing plaintext", it)
            plain
        }
    }

    fun dec(stored: String): String {
        if (!stored.startsWith(PREFIX)) return stored
        decCache[stored]?.let { return it }
        val plain = runCatching {
            val raw = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, raw, 0, 12))
            String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        }.getOrElse {
            // 解密失败（暂时性 Keystore 故障）不缓存空值，避免把"未登录"状态固化
            android.util.Log.e("CryptoBox", "dec failed", it)
            return ""
        }
        if (decCache.size > 256) decCache.clear() // 简单防膨胀（密钥不变，清了只是重算）
        decCache[stored] = plain
        return plain
    }
}

/**
 * 登录态存储（DataStore）
 */
class AuthStore(private val context: Context) {

    companion object {
        private val KEY_USERNAME = stringPreferencesKey("username")
        private val KEY_PASSWORD = stringPreferencesKey("password")
        private val KEY_DEVICE_ID = stringPreferencesKey("device_id")
        private val KEY_LOGIN_TOKEN = stringPreferencesKey("login_token")
        private val KEY_GATEWAY_TOKEN = stringPreferencesKey("gateway_token")
        private val KEY_ACCESS_TOKEN = stringPreferencesKey("access_token")
        private val KEY_ACCESS_EXPIRY = longPreferencesKey("access_expiry")
        private val KEY_USER_NAME = stringPreferencesKey("user_name")
        private val KEY_COLLEGE = stringPreferencesKey("college")
        private val KEY_XYKH = stringPreferencesKey("xykh")
        private val KEY_LAST_SYNC = longPreferencesKey("last_sync")
        private val KEY_SKIP_CAPTCHA = stringPreferencesKey("session_id")
        private val KEY_SHOW_NOON_CLASSES = booleanPreferencesKey("show_noon_classes")
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_THEME_COLOR = stringPreferencesKey("theme_color")
        private val KEY_ET_TOKEN = stringPreferencesKey("et_token")
        private val KEY_ET_EXPIRY = longPreferencesKey("et_expiry")
        private val KEY_YKT_ACC_NUM = stringPreferencesKey("ykt_acc_num")
        private val KEY_YKT_EPID = stringPreferencesKey("ykt_epid")
        private val KEY_YKT_CARD_ACC_NUM = stringPreferencesKey("ykt_card_acc_num")
        private val KEY_YKT_ACC_NAME = stringPreferencesKey("ykt_acc_name")
        // 校园网（深澜 Srun Portal）凭据与自动登录开关
        private val KEY_NET_USERNAME = stringPreferencesKey("net_username")
        private val KEY_NET_PASSWORD = stringPreferencesKey("net_password")
        private val KEY_NET_ACCOUNT_TYPE = stringPreferencesKey("net_account_type")
        private val KEY_NET_AUTO_LOGIN = booleanPreferencesKey("net_auto_login")
        // 校园网多账户：JSON 数组（密码逐个加密）+ 当前账户 id（自动登录/设备管理用）
        private val KEY_NET_ACCOUNTS = stringPreferencesKey("net_accounts")
        private val KEY_NET_ACTIVE = stringPreferencesKey("net_active")
        // 用户协议：记录已同意协议的学号（永久免弹，换号重新确认）
        private val KEY_AGREEMENT_USER = stringPreferencesKey("agreement_user")
    }

    /** 校园网账户（type = campus / study，见 NetRepository.AccountType.storage） */
    data class NetAccount(
        val id: String = "",
        val username: String = "",
        val password: String = "",
        val type: String = "campus",
    )

    @kotlinx.serialization.Serializable
    private data class NetAccountJson(
        val id: String = "",
        val username: String = "",
        val password: String = "",
        val type: String = "campus",
    )

    private val netAccountJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    data class AuthState(
        val username: String = "",
        val password: String = "",
        val deviceId: String = "",
        val loginToken: String = "",
        val gatewayToken: String = "",
        val accessToken: String = "",
        val accessExpiry: Long = 0L,
        val userName: String = "",
        val college: String = "",
        val xykh: String = "",
        val lastSync: Long = 0L,
        val sessionId: String = "",
        /** 是否显示午1/午2（13、14 节） */
        val showNoonClasses: Boolean = false,
        /** 主题模式（system/light/dark，见 theme.ThemeMode） */
        val themeMode: String = "system",
        /** 主题配色（blue/pink/gray，见 theme.ThemeColor） */
        val themeColor: String = "blue",
        /** 校园卡 etToken（JWT，约 1 天有效）与到期时间 */
        val etToken: String = "",
        val etExpiry: Long = 0L,
        /** 校园卡账户缓存：accNum / EPID / 主卡卡号 / 姓名 */
        val yktAccNum: String = "",
        val yktEpid: String = "",
        val yktCardAccNum: String = "",
        val yktAccName: String = "",
        /** 校园网上网账号（不含域后缀）/ 密码 / 账号类型（campus / study）/ 自动登录开关 */
        val netUsername: String = "",
        val netPassword: String = "",
        val netAccountType: String = "campus",
        val netAutoLogin: Boolean = false,
        /** 校园网多账户列表与当前账户 id（自动登录/设备管理跟随当前账户） */
        val netAccounts: List<NetAccount> = emptyList(),
        val netActiveId: String = "",
        /** 已同意用户协议的学号（该学号登录不再弹协议窗） */
        val agreementUser: String = "",
    ) {
        val isLoggedIn: Boolean get() = loginToken.isNotEmpty() && gatewayToken.isNotEmpty()

        /** access_token 是否还有效（120 秒，留 10 秒余量） */
        val accessValid: Boolean get() = accessToken.isNotEmpty() && System.currentTimeMillis() < accessExpiry - 10_000

        /** etToken 是否还有效（留 10 分钟余量） */
        val etValid: Boolean get() = etToken.isNotEmpty() && System.currentTimeMillis() < etExpiry - 600_000

        /** 当前校园网账户：activeId 命中优先，否则列表第一个；列表为空回退旧单账户字段 */
        val activeNetAccount: NetAccount?
            get() = netAccounts.firstOrNull { it.id == netActiveId && it.username.isNotBlank() }
                ?: netAccounts.firstOrNull { it.username.isNotBlank() }
                ?: if (netUsername.isNotBlank()) NetAccount("legacy", netUsername, netPassword, netAccountType)
                else null
    }

    val state: Flow<AuthState> = context.dataStore.data.map { p ->
        AuthState(
            username = p[KEY_USERNAME].orEmpty(),
            password = CryptoBox.dec(p[KEY_PASSWORD].orEmpty()),
            deviceId = p[KEY_DEVICE_ID].orEmpty(),
            loginToken = CryptoBox.dec(p[KEY_LOGIN_TOKEN].orEmpty()),
            gatewayToken = CryptoBox.dec(p[KEY_GATEWAY_TOKEN].orEmpty()),
            accessToken = CryptoBox.dec(p[KEY_ACCESS_TOKEN].orEmpty()),
            accessExpiry = p[KEY_ACCESS_EXPIRY] ?: 0L,
            userName = CryptoBox.dec(p[KEY_USER_NAME].orEmpty()),
            college = CryptoBox.dec(p[KEY_COLLEGE].orEmpty()),
            xykh = CryptoBox.dec(p[KEY_XYKH].orEmpty()),
            lastSync = p[KEY_LAST_SYNC] ?: 0L,
            sessionId = p[KEY_SKIP_CAPTCHA].orEmpty(),
            showNoonClasses = p[KEY_SHOW_NOON_CLASSES] ?: false,
            themeMode = p[KEY_THEME_MODE] ?: "system",
            themeColor = p[KEY_THEME_COLOR] ?: "blue",
            etToken = CryptoBox.dec(p[KEY_ET_TOKEN].orEmpty()),
            etExpiry = p[KEY_ET_EXPIRY] ?: 0L,
            yktAccNum = p[KEY_YKT_ACC_NUM].orEmpty(),
            yktEpid = p[KEY_YKT_EPID].orEmpty(),
            yktCardAccNum = p[KEY_YKT_CARD_ACC_NUM].orEmpty(),
            yktAccName = CryptoBox.dec(p[KEY_YKT_ACC_NAME].orEmpty()),
            netUsername = p[KEY_NET_USERNAME].orEmpty(),
            netPassword = CryptoBox.dec(p[KEY_NET_PASSWORD].orEmpty()),
            netAccountType = p[KEY_NET_ACCOUNT_TYPE] ?: "campus",
            netAutoLogin = p[KEY_NET_AUTO_LOGIN] ?: false,
            netAccounts = runCatching {
                netAccountJson.decodeFromString<List<NetAccountJson>>(p[KEY_NET_ACCOUNTS].orEmpty())
                    .map { NetAccount(it.id, it.username, CryptoBox.dec(it.password), it.type) }
            }.getOrElse { emptyList() },
            netActiveId = p[KEY_NET_ACTIVE].orEmpty(),
            agreementUser = p[KEY_AGREEMENT_USER].orEmpty(),
        )
    }.flowOn(kotlinx.coroutines.Dispatchers.Default) // 解密/映射不占用主线程

    /** 保存校园卡 etToken（默认 23 小时有效，实际 JWT 约 1 天） */
    suspend fun saveEtToken(token: String, accNum: String) {
        context.dataStore.edit { p ->
            p[KEY_ET_TOKEN] = CryptoBox.enc(token)
            p[KEY_ET_EXPIRY] = System.currentTimeMillis() + 23 * 3600_000L
            p[KEY_YKT_ACC_NUM] = accNum
        }
    }

    /** 保存校园卡账户缓存（EPID/主卡卡号/姓名） */
    suspend fun saveYktAccount(epid: String, cardAccNum: String, accName: String) {
        context.dataStore.edit { p ->
            p[KEY_YKT_EPID] = epid
            p[KEY_YKT_CARD_ACC_NUM] = cardAccNum
            p[KEY_YKT_ACC_NAME] = CryptoBox.enc(accName)
        }
    }

    /** 清除校园卡 token（失效时强制重换） */
    suspend fun clearEtToken() {
        context.dataStore.edit { p ->
            p.remove(KEY_ET_TOKEN)
            p.remove(KEY_ET_EXPIRY)
        }
    }

    /** 记录某学号已同意用户协议（该学号后续登录免弹协议窗） */
    suspend fun saveAgreementUser(username: String) {
        context.dataStore.edit { p -> p[KEY_AGREEMENT_USER] = username }
    }

    /** 保存校园网上网账号凭据（密码加密落盘） */
    suspend fun saveNetCredentials(username: String, password: String, accountType: String) {
        context.dataStore.edit { p ->
            p[KEY_NET_USERNAME] = username
            p[KEY_NET_PASSWORD] = CryptoBox.enc(password)
            p[KEY_NET_ACCOUNT_TYPE] = accountType
        }
    }

    /** 保存校园网多账户列表与当前账户（密码逐个加密进 JSON） */
    suspend fun saveNetAccounts(accounts: List<NetAccount>, activeId: String) {
        val json = netAccountJson.encodeToString(
            accounts.map { NetAccountJson(it.id, it.username, CryptoBox.enc(it.password), it.type) },
        )
        context.dataStore.edit { p ->
            p[KEY_NET_ACCOUNTS] = json
            p[KEY_NET_ACTIVE] = activeId
        }
    }

    /**
     * 旧单账户字段一次性迁移为多账户列表（幂等）。
     * 迁移成功后清空旧字段：防止列表里删掉该账户后被 activeNetAccount 的旧字段兜底"复活"。
     */
    suspend fun migrateNetAccounts() {
        val s = current()
        if (s.netAccounts.isNotEmpty() || s.netUsername.isBlank()) return
        saveNetAccounts(
            listOf(NetAccount(id = "acc1", username = s.netUsername, password = s.netPassword, type = s.netAccountType)),
            activeId = "acc1",
        )
        context.dataStore.edit { p ->
            p.remove(KEY_NET_USERNAME)
            p.remove(KEY_NET_PASSWORD)
            p.remove(KEY_NET_ACCOUNT_TYPE)
        }
    }

    /** 校园网自动登录开关（前台服务常驻与否） */
    suspend fun setNetAutoLogin(enabled: Boolean) {
        context.dataStore.edit { p -> p[KEY_NET_AUTO_LOGIN] = enabled }
    }

    /** 切换是否显示午1/午2 */
    suspend fun setShowNoonClasses(show: Boolean) {
        context.dataStore.edit { p -> p[KEY_SHOW_NOON_CLASSES] = show }
    }

    /** 保存主题模式 */
    suspend fun setThemeMode(mode: String) {
        context.dataStore.edit { p -> p[KEY_THEME_MODE] = mode }
    }

    /** 保存主题配色 */
    suspend fun setThemeColor(color: String) {
        context.dataStore.edit { p -> p[KEY_THEME_COLOR] = color }
    }

    suspend fun current(): AuthState = state.first()

    suspend fun saveCredentials(username: String, password: String, deviceId: String) {
        context.dataStore.edit { p ->
            p[KEY_USERNAME] = username
            p[KEY_PASSWORD] = CryptoBox.enc(password)
            p[KEY_DEVICE_ID] = deviceId
        }
    }

    suspend fun saveLoginResult(loginToken: String, gatewayToken: String) {
        context.dataStore.edit { p ->
            p[KEY_LOGIN_TOKEN] = CryptoBox.enc(loginToken)
            p[KEY_GATEWAY_TOKEN] = CryptoBox.enc(gatewayToken)
            p.remove(KEY_ACCESS_TOKEN)
            p.remove(KEY_ACCESS_EXPIRY)
        }
    }

    suspend fun saveAccessToken(accessToken: String) {
        context.dataStore.edit { p ->
            p[KEY_ACCESS_TOKEN] = CryptoBox.enc(accessToken)
            p[KEY_ACCESS_EXPIRY] = System.currentTimeMillis() + 120_000
        }
    }

    suspend fun saveUserInfo(userName: String, college: String, xykh: String) {
        context.dataStore.edit { p ->
            p[KEY_USER_NAME] = CryptoBox.enc(userName)
            p[KEY_COLLEGE] = CryptoBox.enc(college)
            p[KEY_XYKH] = CryptoBox.enc(xykh)
        }
    }

    suspend fun saveSessionId(sessionId: String) {
        context.dataStore.edit { p -> p[KEY_SKIP_CAPTCHA] = sessionId }
    }

    suspend fun touchSync() {
        context.dataStore.edit { p -> p[KEY_LAST_SYNC] = System.currentTimeMillis() }
    }

    /**
     * 历史明文敏感字段一次性升级为密文（安装后首次启动调用）。
     * 读 -> 解密兼容读 -> 重新加密写回；已是密文的字段 enc() 原样返回，幂等。
     */
    suspend fun migrateEncryption() {
        context.dataStore.edit { p ->
            listOf(
                KEY_PASSWORD, KEY_LOGIN_TOKEN, KEY_GATEWAY_TOKEN, KEY_ACCESS_TOKEN,
                KEY_USER_NAME, KEY_COLLEGE, KEY_XYKH, KEY_ET_TOKEN, KEY_YKT_ACC_NAME,
                KEY_NET_PASSWORD,
            ).forEach { k ->
                val v = p[k]
                if (!v.isNullOrEmpty() && !v.startsWith("v1:")) p[k] = CryptoBox.enc(v)
            }
        }
    }

    /**
     * 退出登录：清除全部凭据与个人数据（含密码/姓名/学院/一卡通号），
     * 仅保留 username 供下次登录预填。
     */
    suspend fun logout() {
        context.dataStore.edit { p ->
            p.remove(KEY_PASSWORD)
            p.remove(KEY_LOGIN_TOKEN)
            p.remove(KEY_GATEWAY_TOKEN)
            p.remove(KEY_ACCESS_TOKEN)
            p.remove(KEY_ACCESS_EXPIRY)
            p.remove(KEY_SKIP_CAPTCHA)
            p.remove(KEY_USER_NAME)
            p.remove(KEY_COLLEGE)
            p.remove(KEY_XYKH)
            p.remove(KEY_ET_TOKEN)
            p.remove(KEY_ET_EXPIRY)
            p.remove(KEY_YKT_ACC_NUM)
            p.remove(KEY_YKT_EPID)
            p.remove(KEY_YKT_CARD_ACC_NUM)
            p.remove(KEY_YKT_ACC_NAME)
            // 校园网凭据同属敏感数据，退出登录时一并清除（自动登录开关保留无意义）
            p.remove(KEY_NET_USERNAME)
            p.remove(KEY_NET_PASSWORD)
            p.remove(KEY_NET_AUTO_LOGIN)
        }
    }
}
