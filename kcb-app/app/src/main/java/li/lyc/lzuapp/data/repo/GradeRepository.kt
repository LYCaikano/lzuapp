package li.lyc.lzuapp.data.repo

import li.lyc.lzuapp.data.api.ApiException
import li.lyc.lzuapp.data.api.LzuApi
import li.lyc.lzuapp.data.api.model.GradeRecord
import li.lyc.lzuapp.data.api.model.StuXqItem
import kotlinx.coroutines.CancellationException

/**
 * 成绩查询仓库（wdcj 接口，认证复用课表 access_token 链）。
 * token 被服务端拒绝（10004）时强制续约重试一次；网络错误重试 2 次（间隔 500ms）。
 */
class GradeRepository(
    private val lzuApi: LzuApi,
    private val courseRepo: CourseRepository,
) {

    sealed interface GradeResult<out T> {
        data class Ok<T>(val data: T) : GradeResult<T>
        data class Err(val message: String) : GradeResult<Nothing>
    }

    private suspend fun <T> withToken(block: suspend (String) -> T): GradeResult<T> {
        var forceRefreshed = false
        var networkAttempts = 0
        while (true) {
            val token = courseRepo.validAccessToken().getOrElse { e ->
                return GradeResult.Err(if (e is java.io.IOException) "无网络" else "登录状态失效")
            }
            try {
                return GradeResult.Ok(block(token))
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.message?.startsWith("10004") == true && !forceRefreshed) {
                    forceRefreshed = true
                    val t2 = courseRepo.forceAccessToken().getOrElse {
                        return GradeResult.Err("登录状态失效")
                    }
                    try {
                        return GradeResult.Ok(block(t2))
                    } catch (e2: CancellationException) {
                        throw e2
                    } catch (e2: Exception) {
                        return GradeResult.Err(friendlyMessage(e2.message) ?: "加载失败，请重试")
                    }
                }
                return GradeResult.Err(friendlyMessage(e.message) ?: "加载失败")
            } catch (e: java.io.IOException) {
                networkAttempts++
                if (networkAttempts < 3) {
                    kotlinx.coroutines.delay(500)
                    continue
                }
                return GradeResult.Err("无网络")
            } catch (e: Exception) {
                return GradeResult.Err(e.message ?: "加载失败")
            }
        }
    }

    /** 网关 5xx（后端挂起/维护，如 wdcj 504 Gateway Timeout）转为可读提示 */
    private fun friendlyMessage(raw: String?): String? = when {
        raw == null -> null
        raw.startsWith("HTTP 5") -> "学校成绩服务暂不可用（服务器维护中），请稍后再试"
        else -> raw
    }

    /** 有成绩的学期列表 */
    suspend fun semesters(): GradeResult<List<StuXqItem>> = withToken { lzuApi.getStuXqList(it).getOrThrow() }

    /** 成绩列表（xn/xqm 传 0 = 全部学期） */
    suspend fun grades(xn: Int = 0, xqm: Int = 0): GradeResult<List<GradeRecord>> =
        withToken { lzuApi.getGrades(it, xn, xqm).getOrThrow() }
}
