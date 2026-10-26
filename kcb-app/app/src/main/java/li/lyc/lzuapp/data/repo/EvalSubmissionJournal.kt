package li.lyc.lzuapp.data.repo

/** Claims are process-local; only possibly sent/confirmed requests survive a restart. */
internal class EvalSubmissionJournal(private val store: Store) {
    enum class Phase { SENT, CONFIRMED }
    interface Store {
        fun read(key: String): Phase?
        /** Must leave the prior value intact when persistence fails. */
        fun write(key: String, phase: Phase)
    }
    private val claims = mutableSetOf<String>()
    fun protected(key: String): Boolean = synchronized(claims) { key in claims || store.read(key) != null }

    suspend fun <T> submit(key: String, repeatConfirmed: Boolean = false, block: suspend (() -> Unit) -> T): T {
        synchronized(claims) {
            check(key !in claims && (repeatConfirmed || store.read(key) == null)) { "该对象正在提交或结果待核对，请刷新后检查" }
            claims.add(key)
        }
        try {
            var sent = false
            val result = block { store.write(key, Phase.SENT); sent = true }
            check(sent) { "请求未发送，未记录提交结果" }
            store.write(key, Phase.CONFIRMED)
            return result
        } finally {
            // Authentication, cooldown, cancellation and failed pre-send persistence release the claim.
            synchronized(claims) { claims.remove(key) }
        }
    }
}
