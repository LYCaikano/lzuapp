package li.lyc.lzuapp.data.api

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/** Shared by every jwqe read, login, retry and write, including manual evaluation. */
class EvalRequestGate(
    private val store: Store = MemoryStore(),
    private val now: () -> Long = System::currentTimeMillis,
    private val jitter: (Long) -> Long = { Random.nextLong(it + 1) },
) {
    data class State(val readAt: Long = 0, val writeAt: Long = 0, val blockedUntil: Long = 0)
    interface Store {
        fun read(): State
        fun save(state: State)
    }
    private class MemoryStore : Store {
        private var state = State()
        override fun read() = state
        override fun save(state: State) { this.state = state }
    }
    private val mutex = Mutex()

    suspend fun <T> request(write: Boolean, block: () -> T): T = mutex.withLock {
        var state = store.read()
        if (state.blockedUntil > now()) throw EvalRateLimitedException(state.blockedUntil)
        delay(((if (write) state.writeAt else state.readAt) - now()).coerceAtLeast(0))
        // Persist the reservation before sending, so a restart cannot bypass spacing.
        fun reserve() {
            val time = now()
            state = state.copy(readAt = time + 1000 + jitter(250), writeAt = time + 2000 + jitter(1000))
            store.save(state)
        }
        reserve()
        try {
            block()
        } catch (e: EvalRateLimitedException) {
            state = state.copy(blockedUntil = maxOf(state.blockedUntil, e.until))
            store.save(state)
            throw e
        } finally {
            // Failed attempts consume the same budget; no burst after a slow response.
            reserve()
        }
    }
}

class EvalRateLimitedException(val until: Long) : Exception(
    "评教接口正在冷却，请在 ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date(until))} 后手动重试",
)
