package li.lyc.lzuapp.data.repo

import li.lyc.lzuapp.data.AuthStore
import li.lyc.lzuapp.data.api.LzuApi
import li.lyc.lzuapp.data.api.YktApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 校园卡钱包（余额查询结果的一行） */
data class YktWallet(
    val cardAccNum: String,
    val walletNum: String,
    val walletName: String,
    val money: String,
    val moneyMax: String,
)

/** 账单交易记录（一行） */
data class YktBillItem(
    val month: String,
    val day: String,
    val time: String,
    val typeName: String,
    val money: String,
    val feeCategory: String,
    val payRecNum: String,
    val uploadTime: String,
) {
    /** 金额数值（负=支出）；解析失败为 0 */
    val amount: Double get() = money.replace("元", "").trim().toDoubleOrNull() ?: 0.0
}

/** 充值渠道（PayChannelId 000001=支付宝 000002=微信） */
data class YktChannel(val payChannelId: String, val childChannelId: String) {
    val displayName: String
        get() = when (payChannelId) {
            "000001" -> "支付宝"
            "000002" -> "微信"
            else -> "渠道 $payChannelId"
        }
}

// ---------- 宿舍电费（校付宝 H5，见 elecPortalUrl） ----------

/**
 * 校园卡仓库：etToken 生命周期管理 + 业务调用。
 * 每个操作前都经 [withYktRetry] 静默校验登录态（etToken 失效 → ST 重换 →
 * ST 也失效（10004）→ 静默重新登录后重试），与课程侧的 withLoginRetry 同款模式。
 */
class YktRepository(
    private val authStore: AuthStore,
    private val lzuApi: LzuApi,
    private val yktApi: YktApi,
    private val courseRepo: CourseRepository,
) {

    sealed interface YktResult<out T> {
        data class Ok<T>(val data: T) : YktResult<T>
        data class Err(val message: String, val reason: SyncFailReason = SyncFailReason.OTHER) : YktResult<Nothing>
    }

    /** ST 票据一次性：换 etToken 必须串行 */
    private val etMutex = Mutex()

    /**
     * 确保 etToken 有效。失效链：etToken 过期 → getSt 重换；
     * getSt 失败（登录态被挤）→ 静默重新登录 → 再 getSt → 重换。
     * 同时刷新账户缓存（EPID/主卡/姓名）。
     */
    private suspend fun ensureEtToken(): Result<String> = etMutex.withLock {
        val s = authStore.current()
        if (s.etValid) {
            // EPID 缓存为空（此前 GetAccInfo 失败被吞）时补拉，否则后续业务必败
            if (s.yktEpid.isEmpty()) refreshAccountLocked(s.etToken, s.yktAccNum)
            return@withLock Result.success(s.etToken)
        }
        val first = exchangeLocked()
        if (first.isSuccess) return@withLock first
        // 首次失败：静默重新登录后重试一次
        val relogin = courseRepo.login(s.username, s.password)
        if (relogin !is LoginResult.Success) {
            return@withLock Result.failure(
                if (relogin is LoginResult.Failed && relogin.reason == SyncFailReason.NETWORK)
                    java.io.IOException("无网络")
                else Exception("登录状态失效")
            )
        }
        exchangeLocked()
    }

    /** getSt → ExchangeEtToken → 保存 token → 拉 GetAccInfo 缓存 EPID/主卡 */
    private suspend fun exchangeLocked(): Result<String> {
        val cur = authStore.current()
        if (!cur.isLoggedIn) return Result.failure(Exception("未登录"))
        val st = lzuApi.getSt(cur.loginToken, cur.gatewayToken).getOrElse {
            return Result.failure(if (it is java.io.IOException) it else Exception("登录状态失效"))
        }
        val resp = try {
            yktApi.call("/ExchangeEtToken", mapOf("St" to st), etToken = null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(if (e is java.io.IOException) e else Exception("换取校园卡令牌失败"))
        }
        val token = resp.field("token").orEmpty()
        val accNum = resp.field("accNum").orEmpty()
        if (token.isEmpty() || accNum.isEmpty()) return Result.failure(Exception("换取校园卡令牌失败"))
        authStore.saveEtToken(token, accNum)
        refreshAccountLocked(token, accNum)
        return Result.success(token)
    }

    /** 拉 GetAccInfo 刷新账户缓存（EPID/主卡/姓名）；失败不阻塞，下次操作时重试 */
    private suspend fun refreshAccountLocked(token: String, accNum: String) {
        runCatching {
            val info = yktApi.call("/GetAccInfo", mapOf("AccNum" to accNum), etToken = token)
            authStore.saveYktAccount(
                epid = info.field("EPID").orEmpty(),
                cardAccNum = info.field("CardAccNum").orEmpty(),
                accName = info.field("AccName").orEmpty(),
            )
        }.onFailure { if (it is CancellationException) throw it }
    }

    /**
     * 操作前静默校验登录态 + 失效自动重试：
     * - 签名/登录态错误（Code=3）：强制重换 etToken 重试 1 次
     * - 网络错误（IOException）：读操作最多共试 3 次（重试 2 次，间隔 500ms），
     *   仍失败才报"无网络"；写操作（[retryOnNetwork]=false，如充值下单）不重试，防重复下单
     */
    private suspend fun <T> withYktRetry(
        retryOnNetwork: Boolean = true,
        block: suspend (token: String) -> T,
    ): YktResult<T> {
        var authRetried = false
        var networkAttempts = 0
        val maxNetworkAttempts = if (retryOnNetwork) 3 else 1
        while (true) {
            var usedToken: String? = null
            try {
                val token = ensureEtToken().getOrThrow()
                usedToken = token
                return YktResult.Ok(block(token))
            } catch (e: CancellationException) {
                // 协程取消（ViewModel 销毁等）必须向上传播，不能转成错误结果继续重试
                throw e
            } catch (e: YktApi.YktException) {
                if (e.isAuth && !authRetried) {
                    authRetried = true
                    etMutex.withLock {
                        // 只清自己这次用的 token：若在途旧请求返回时别协程已换新 token，
                        // 锁外无差别清会把新 token 顶掉，连环重换
                        if (usedToken == null || authStore.current().etToken == usedToken) {
                            authStore.clearEtToken()
                        }
                    }
                    continue
                }
                return YktResult.Err(e.message ?: "操作失败")
            } catch (e: java.io.IOException) {
                networkAttempts++
                if (networkAttempts < maxNetworkAttempts) {
                    kotlinx.coroutines.delay(500)
                    continue
                }
                return YktResult.Err("无网络", SyncFailReason.NETWORK)
            } catch (e: Exception) {
                return YktResult.Err(e.message ?: "操作失败")
            }
        }
    }

    private suspend fun accNum(): String = authStore.current().yktAccNum
    private suspend fun epid(): String = authStore.current().yktEpid

    /** 静默校验登录态（进入付款码页前调用）：成功 = 当前登录链可用 */
    suspend fun checkLoginState(): YktResult<Unit> = withYktRetry { }

    /** 钱包余额（只保留 M1CPU 主卡钱包，电子账户不在范围内） */
    suspend fun walletMoney(): YktResult<List<YktWallet>> = withYktRetry { token ->
        val s = authStore.current()
        val r = yktApi.call("/GetWalletMoney", mapOf("AccNum" to s.yktAccNum, "EPID" to s.yktEpid), token)
        r.table.mapNotNull { row ->
            val cardAccNum = row["CardAccNum"].orEmpty()
            // 只显示挂在主卡（M1CPU混合卡）下的钱包；无卡号信息时保留首个
            if (s.yktCardAccNum.isNotEmpty() && cardAccNum.isNotEmpty() && cardAccNum != s.yktCardAccNum) null
            else YktWallet(
                cardAccNum = cardAccNum,
                walletNum = row["WalletNum"].orEmpty(),
                walletName = row["WalletName"].orEmpty(),
                money = row["WalletMoney"].orEmpty(),
                moneyMax = row["MoneyMax"].orEmpty(),
            )
        }
    }

    /** 付款码：qRCode 内容 + 查单号 authNum + 卡号 */
    suspend fun paymentQrCode(): YktResult<Triple<String, String, String>> = withYktRetry { token ->
        val r = yktApi.call("/getH5QRCode", mapOf("AccNum" to accNum()), token)
        val qr = r.field("qRCode").orEmpty()
        if (qr.isEmpty()) throw YktApi.YktException(r.msg.ifBlank { "获取付款码失败" })
        Triple(qr, r.field("authNum").orEmpty(), r.field("cardAccNum").orEmpty())
    }

    /** 账单（按月分组）。typeNum: -1全部 -2消费 -3充值；返回 (记录, 是否还有更多) */
    suspend fun bills(typeNum: String = "-1", beginRecNum: Int = 0, count: Int = 20): YktResult<Pair<List<YktBillItem>, Boolean>> =
        withYktRetry { token ->
            val r = yktApi.call(
                "/GetDealRec", mapOf(
                    "AccNum" to accNum(), "BeginRecNum" to beginRecNum.toString(),
                    "CardAccNum" to "-1", "Count" to count.toString(),
                    "EPID" to epid(), "TypeNum" to typeNum, "WalletNum" to "0",
                ), token,
            )
            val total = r.field("AllRecSum")?.toIntOrNull() ?: 0
            val items = r.rows.flatMap { (month, rows) ->
                rows.map { row ->
                    YktBillItem(
                        month = month,
                        day = row["Day"].orEmpty(),
                        time = row["Time"].orEmpty(),
                        typeName = row["TypeName"].orEmpty(),
                        money = row["Money"].orEmpty(),
                        feeCategory = row["FeeCategory"].orEmpty(),
                        payRecNum = row["PayRecNum"].orEmpty(),
                        uploadTime = row["UploadTime"].orEmpty(),
                    )
                }
            }
            items to (beginRecNum + items.size < total)
        }

    /** 充值渠道（微信/支付宝） */
    suspend fun rechargeChannels(): YktResult<List<YktChannel>> = withYktRetry { token ->
        val r = yktApi.call("/getPayTypeFromAggregate", mapOf("EPID" to epid(), "PayOrigin" to "1"), token)
        r.table.map { YktChannel(it["PayChannelId"].orEmpty(), it["ChildChannelId"].orEmpty()) }
            .filter { it.payChannelId.isNotEmpty() }
    }

    /**
     * 充值下单：返回收银台 URL。
     * [callbackUrl] 支付平台回跳地址（WebView 拦截用，附加 out_trade_no）。
     */
    suspend fun rechargeOrder(money: String, wallet: YktWallet, channel: YktChannel, callbackUrl: String): YktResult<String> =
        withYktRetry(retryOnNetwork = false) { token ->
            val r = yktApi.call(
                "/hzsunPayByOtherType", mapOf(
                    "AccNum" to accNum(), "CardAccNum" to wallet.cardAccNum, "EPID" to epid(),
                    "InEWalletNum" to wallet.walletNum, "MonTrans" to money,
                    "ChildChannelId" to channel.childChannelId, "PayChannelId" to channel.payChannelId,
                    "Url" to callbackUrl,
                ), token,
            )
            r.field("Url") ?: throw YktApi.YktException(r.msg.ifBlank { "下单失败" })
        }

    /** 按商户订单号查充值结果：Status 2=成功 3=失败（其它=处理中） */
    suspend fun rechargeResult(orderNum: String): YktResult<YktApi.YktResponse> = withYktRetry { token ->
        yktApi.call("/GetOrderByNum", mapOf("AccNum" to accNum(), "OrderNum" to orderNum), token)
    }

    // ==================== 宿舍电费（校付宝 H5，服务目录 29615） ====================
    // 官方链取证（2026-10，抓包 + jadx H5Activity.h1 + PC 复放）：
    // 官方 App 点「宿舍电费」→ getSt(serviceId=29615) → H5Activity 打开
    // apps_all.shtml?authAppid=…&platform=QHIT_EDU&schoolCode=10730&PersonID=<学号>&st=<ST>&ticket=<ST>
    // （UrlType.APP：参数名 st 与 ticket 均为同一张 ST）。
    // 校付宝会话靠 WebView Cookie 维持；首次未登录时页面自行跳统一身份认证（CAS），
    // 登录一次后长期静默。两个缴费板块都在 apps_all 页内（本页即合并后的单页面）。
    // 旧 easytong payEle 链（ItemNum=2）已废弃：电控系统查询恒 21s 超时返空。

    /**
     * 构造宿舍电费入口 URL（含一次性 ST）。
     * 进入前静默校验登录态，与课表同步同一套自愈逻辑：
     * - 签 ST 遇网络抖动（IOException）：500ms 间隔静默重试，共 3 次仍失败才报"无网络"；
     * - 签 ST 发现登录态失效：静默重新登录后重签；重登本身遇网络错误同样按 500ms 间隔重试；
     * - 重登成功但重签仍失效（账号被挤/凭据变更等）才报"登录状态失效"。
     */
    suspend fun elecPortalUrl(): YktResult<String> {
        var reloginTried = false
        var networkAttempts = 0
        while (true) {
            val r = buildElecUrl()
            if (r.isSuccess) return YktResult.Ok(r.getOrThrow())
            val e = r.exceptionOrNull()
            // 协程取消（页面销毁等）必须向上传播，不能转成错误结果继续重试
            if (e is CancellationException) throw e
            if (e is java.io.IOException) {
                networkAttempts++
                if (networkAttempts < ELE_NETWORK_ATTEMPTS) {
                    kotlinx.coroutines.delay(500)
                    continue
                }
                return YktResult.Err("无网络", SyncFailReason.NETWORK)
            }
            // 登录态失效：静默重登一次后重签
            if (reloginTried) return YktResult.Err("登录状态失效", SyncFailReason.LOGIN)
            reloginTried = true
            val s = authStore.current()
            if (s.username.isEmpty()) return YktResult.Err("未登录", SyncFailReason.LOGIN)
            val relogin = courseRepo.login(s.username, s.password)
            if (relogin is LoginResult.Success) {
                // 重登成功：网络计数清零，重签 ST
                networkAttempts = 0
                continue
            }
            val isNet = relogin is LoginResult.Failed && relogin.reason == SyncFailReason.NETWORK
            if (isNet) {
                networkAttempts++
                if (networkAttempts < ELE_NETWORK_ATTEMPTS) {
                    // 网络错误不算消耗重登机会：下轮重新走重登
                    reloginTried = false
                    kotlinx.coroutines.delay(500)
                    continue
                }
                return YktResult.Err("无网络", SyncFailReason.NETWORK)
            }
            return YktResult.Err("登录状态失效", SyncFailReason.LOGIN)
        }
    }

    private suspend fun buildElecUrl(): Result<String> {
        val s = authStore.current()
        if (!s.isLoggedIn) return Result.failure(Exception("未登录"))
        val st = lzuApi.getStByServiceId(s.loginToken, s.gatewayToken, ELE_SERVICE_ID)
            .getOrElse { return Result.failure(it) }
        if (st.isEmpty()) return Result.failure(Exception("登录状态失效"))
        // PersonID = 学号（xykh）；个别账号 userInfo 缺 xykh 时回退登录用户名（即学号）
        val personId = s.xykh.ifEmpty { s.username }
        val enc = { v: String -> java.net.URLEncoder.encode(v, "UTF-8") }
        return Result.success(ELE_H5_URL + "&PersonID=" + enc(personId) + "&st=" + enc(st) + "&ticket=" + enc(st))
    }

    companion object {
        /** 服务目录中「宿舍电费」的 service_info_id（h5_url 指向校付宝 apps_all 页） */
        private const val ELE_SERVICE_ID = "29615"

        /** 入口签 ST 网络抖动静默重试次数（含首次），间隔 500ms */
        private const val ELE_NETWORK_ATTEMPTS = 3

        /** 校付宝应用列表页（两个缴费板块的合并入口；参数与服务目录登记的一致） */
        private const val ELE_H5_URL =
            "https://webapp.xiaofubao.com/card/apps_all.shtml" +
                "?authAppid=2606161276416311297&platform=QHIT_EDU&schoolCode=10730"
    }
}
