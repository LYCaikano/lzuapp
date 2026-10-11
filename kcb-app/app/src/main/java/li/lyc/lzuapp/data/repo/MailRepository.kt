package li.lyc.lzuapp.data.repo

import li.lyc.lzuapp.data.api.ApiException
import li.lyc.lzuapp.data.api.CoremailApi
import li.lyc.lzuapp.data.api.LzuApi
import li.lyc.lzuapp.data.api.model.MailAttrs
import li.lyc.lzuapp.data.api.model.MailDetail
import li.lyc.lzuapp.data.api.model.MailFolder
import li.lyc.lzuapp.data.api.model.MailFolderStats
import li.lyc.lzuapp.data.api.model.MailMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * 邮箱仓库：getJumpMenu 换 Coremail sid（短时效），再走 Coremail JSON API。
 * sid 缓存 5 分钟；API 回 FA_ 前缀错误（会话失效）时静默换新 sid 重试一次；
 * 网络错误重试 2 次（间隔 500ms）。每次操作前经 validAccessToken 静默校验登录态。
 */
class MailRepository(
    private val lzuApi: LzuApi,
    private val courseRepo: CourseRepository,
    private val coremail: CoremailApi = CoremailApi(),
) {

    sealed interface MailResult<out T> {
        data class Ok<T>(val data: T) : MailResult<T>
        data class Err(val message: String) : MailResult<Nothing>
    }

    private var cachedSid: String? = null
    private var sidFetchedAt: Long = 0L

    /** 文件夹 + 统计（fid -> 邮件数/未读数） */
    suspend fun folders(): MailResult<Pair<List<MailFolder>, Map<Int, MailFolderStats>>> =
        run { api, sid ->
            val folders = api.getAllFolders(sid).getOrThrow()
            val stats = api.getFolderStats(sid, folders.map { it.id }).getOrElse { emptyMap() }
            val mapped = stats.mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to v } }.toMap()
            Result.success(folders to mapped)
        }

    suspend fun account(): MailResult<MailAttrs> = run { api, sid -> api.getAttrs(sid) }

    suspend fun messages(fid: Int, start: Int, limit: Int): MailResult<List<MailMessage>> =
        run { api, sid -> api.listMessages(sid, fid, start, limit) }

    suspend fun read(id: String): MailResult<MailDetail> =
        run { api, sid -> api.readMessage(sid, id, markRead = true) }

    /** 发送邮件（纯文本正文会转成简单 html 段落） */
    suspend fun send(to: List<String>, subject: String, content: String): MailResult<Unit> =
        run { api, sid -> api.sendMail(sid, to, subject, content) }

    /** 移入已删除（回收站） */
    suspend fun moveToTrash(id: String): MailResult<Unit> =
        run { api, sid -> api.moveToTrash(sid, id) }

    // ---------- 内部 ----------

    /** 取 sid：优先 5 分钟内的缓存，否则走完整 token 链换新 */
    private suspend fun freshSid(force: Boolean): String {
        val now = System.currentTimeMillis()
        val cached = cachedSid
        if (!force && cached != null && now - sidFetchedAt < 5 * 60_000) return cached
        val token = courseRepo.validAccessToken().getOrElse { throw it }
        val url = try {
            lzuApi.getMailJumpUrl(token).getOrThrow()
        } catch (e: ApiException) {
            // token 被拒（10004）：强制续约重试一次
            if (e.message?.startsWith("10004") == true) {
                lzuApi.getMailJumpUrl(courseRepo.forceAccessToken().getOrElse { throw it }).getOrThrow()
            } else throw e
        }
        val sid = Regex("[?&]sid=([A-Za-z0-9]+)").find(url)?.groupValues?.get(1)
            ?: throw ApiException("getJumpMenu: 无 sid")
        cachedSid = sid
        sidFetchedAt = now
        return sid
    }

    private suspend fun <T> run(block: suspend (CoremailApi, String) -> Result<T>): MailResult<T> {
        var sidForced = false
        var networkAttempts = 0
        while (true) {
            val sid = try {
                freshSid(force = sidForced)
            } catch (e: CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                networkAttempts++
                if (networkAttempts < 3) { delay(500); continue }
                return MailResult.Err("无网络")
            } catch (e: Exception) {
                return MailResult.Err("登录状态失效")
            }
            try {
                return MailResult.Ok(block(coremail, sid).getOrThrow())
            } catch (e: CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                networkAttempts++
                if (networkAttempts < 3) { delay(500); continue }
                return MailResult.Err("无网络")
            } catch (e: ApiException) {
                // Coremail 会话失效：静默换新 sid 重试一次
                if (e.message?.startsWith("FA_") == true && !sidForced) {
                    sidForced = true
                    continue
                }
                return MailResult.Err("邮箱服务异常，请重试")
            } catch (e: Exception) {
                return MailResult.Err(e.message ?: "加载失败")
            }
        }
    }
}
