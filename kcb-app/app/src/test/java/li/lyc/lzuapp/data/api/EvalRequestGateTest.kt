package li.lyc.lzuapp.data.api

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EvalRequestGateTest {
    private class Store : EvalRequestGate.Store {
        var state = EvalRequestGate.State()
        override fun read() = state
        override fun save(state: EvalRequestGate.State) { this.state = state }
    }

    @Test fun concurrentReadsAndWritesShareOneBudgetWithJitter() = runTest {
        val gate = EvalRequestGate(now = { testScheduler.currentTime }, jitter = { it })
        val starts = mutableListOf<Long>()
        launch { gate.request(false) { starts.add(testScheduler.currentTime) } }
        launch { gate.request(false) { starts.add(testScheduler.currentTime) } }
        launch { gate.request(true) { starts.add(testScheduler.currentTime) } }
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(0L, 1250L, 4250L), starts)
    }

    @Test fun failuresAreThrottledAndCooldownSurvivesGateRecreation() = runTest {
        val store = Store()
        val clock = { testScheduler.currentTime }
        val gate = EvalRequestGate(store, clock, { 0 })
        try { gate.request(true) { error("network") } } catch (_: IllegalStateException) { }
        gate.request(true) { assertEquals(2000L, clock()) }
        try { gate.request(false) { throw EvalRateLimitedException(60_000) } } catch (_: EvalRateLimitedException) { }
        var sent = false
        try {
            EvalRequestGate(store, clock, { 0 }).request(false) { sent = true }
            fail("Cooldown must fail closed")
        } catch (_: EvalRateLimitedException) { }
        assertTrue(!sent)
        assertEquals(60_000L, store.state.blockedUntil)
    }

    @Test fun http429HonorsRetryAfterAndBlocksAllFurtherRequests() = runTest {
        val store = Store()
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(429).message("rate limited").header("Retry-After", "120").body("{}".toResponseBody()).build()
        }.build()
        val before = System.currentTimeMillis()
        repeat(2) {
            try {
                JwqeApi(client, EvalRequestGate(store)).getTasks("fake")
                fail("429 must stop requests")
            } catch (_: EvalRateLimitedException) { }
        }
        assertEquals(1, calls)
        assertTrue(store.state.blockedUntil >= before + 120_000)
    }

    @Test fun http403HasFiveMinuteMinimumAndIsNotRetried() = runTest {
        val store = Store()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(403).message("forbidden").header("Retry-After", "1").body("{}".toResponseBody()).build()
        }.build()
        val before = System.currentTimeMillis()
        try { JwqeApi(client, EvalRequestGate(store)).getTasks("fake") } catch (_: EvalRateLimitedException) { }
        assertTrue(store.state.blockedUntil >= before + 300_000)
    }
}
