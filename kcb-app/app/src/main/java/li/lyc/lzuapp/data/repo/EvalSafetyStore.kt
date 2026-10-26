package li.lyc.lzuapp.data.repo

import android.content.Context
import li.lyc.lzuapp.data.api.EvalRequestGate
import li.lyc.lzuapp.data.api.model.EvalCourse
import li.lyc.lzuapp.data.api.model.EvalTeacherRecord

/** Identity excludes mutable result IDs and status flags. */
internal fun EvalCourse.targetKey(): List<String> = listOf(
    pjtype, coursecode, yearterm.toString(), pjcoursetype, classno.orEmpty(),
    if (pjtype == "hjjskc") "" else jobnumber?.takeIf { it.isNotBlank() } ?: teachername,
    // Some endpoints do not expose row IDs; semantic identity still deduplicates them.
    studentid,
)

internal val EvalCourse.hasDraft: Boolean
    get() = !isEvaluated && (hassubmit == 2 || (pjjgid ?: 0) != 0L)

/** No credentials or evaluation text are persisted. Unknown write outcomes stay protected. */
class EvalSafetyStore(context: Context) : EvalRequestGate.Store {
    private val prefs = context.getSharedPreferences("eval_safety", Context.MODE_PRIVATE)
    override fun read() = EvalRequestGate.State(
        prefs.getLong("read_at", 0), prefs.getLong("write_at", 0), prefs.getLong("blocked_until", 0),
    )
    override fun save(state: EvalRequestGate.State) {
        check(prefs.edit().putLong("read_at", state.readAt).putLong("write_at", state.writeAt)
            .putLong("blocked_until", state.blockedUntil).commit()) { "无法保存评教限速状态，已停止请求" }
    }
    fun attempted(account: String, taskId: Long, course: EvalCourse): Boolean = prefs.getBoolean(key(account, taskId, course), false)
    fun markAttempted(account: String, taskId: Long, course: EvalCourse): Boolean = synchronized(this) {
        if (attempted(account, taskId, course)) return@synchronized false
        check(prefs.edit().putBoolean(key(account, taskId, course), true).commit()) { "无法保存提交记录，已停止提交" }
        true
    }
    private fun key(account: String, taskId: Long, course: EvalCourse): String {
        val parts = listOf(account, taskId.toString()) + course.targetKey()
        val bytes = parts.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8)
        return "attempt_" + java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/** Conflicting duplicate rows must never erase an evaluated/draft status. */
internal fun List<EvalCourse>.uniqueTargets(): List<EvalCourse> = groupBy { it.targetKey() }.values.map { rows ->
    rows.firstOrNull { it.isEvaluated } ?: rows.firstOrNull { it.hasDraft } ?: rows.first()
}

internal fun mergeTeacherPage(
    records: MutableMap<Pair<String?, String>, EvalTeacherRecord>,
    page: List<EvalTeacherRecord>,
): Int {
    var added = 0
    for (teacher in page) {
        val key = teacher.jobnumber?.takeIf(String::isNotBlank)?.let { it to "" } ?: (null to teacher.teachername)
        val previous = records[key]
        if (previous == null) added++
        if (previous == null || ((teacher.pjjgid ?: 0) != 0L &&
                ((previous.pjjgid ?: 0) == 0L || (previous.speakstates != 2 && teacher.speakstates == 2)))) {
            records[key] = teacher
        }
    }
    return added
}
