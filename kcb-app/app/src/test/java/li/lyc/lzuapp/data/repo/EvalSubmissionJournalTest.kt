package li.lyc.lzuapp.data.repo

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import li.lyc.lzuapp.data.api.JwqeApi
import li.lyc.lzuapp.data.api.EvalRequestGate
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.Proxy
import java.net.UnknownHostException

class EvalSubmissionJournalTest {
    private class Store : EvalSubmissionJournal.Store {
        val rows = mutableMapOf<String, EvalSubmissionJournal.Phase>()
        var fail = false
        override fun read(key: String) = rows[key]
        override fun write(key: String, phase: EvalSubmissionJournal.Phase) {
            check(!fail) { "disk failure" }
            rows[key] = phase
        }
    }

    @Test fun preflightFailureAndFailedPersistenceReleaseClaimsWithoutPermanentProtection() = runTest {
        val store = Store()
        val journal = EvalSubmissionJournal(store)
        for (failure in listOf("not logged in", "ensureToken failure", "cooldown")) {
            try { journal.submit("target") { throw IllegalStateException(failure) } } catch (_: IllegalStateException) { }
            assertFalse(journal.protected("target"))
        }
        store.fail = true
        try { journal.submit("target") { before -> before() } } catch (_: IllegalStateException) { }
        assertFalse(journal.protected("target"))
        store.fail = false
        journal.submit("target") { before -> before() }
        assertEquals(EvalSubmissionJournal.Phase.CONFIRMED, store.rows["target"])
    }

    @Test fun possiblySentFailureSurvivesRestartAndManualSuccessProtectsLaterBatch() = runTest {
        val store = Store()
        val journal = EvalSubmissionJournal(store)
        try { journal.submit("unknown") { before -> before(); error("response lost") } } catch (_: IllegalStateException) { }
        assertTrue(EvalSubmissionJournal(store).protected("unknown"))
        journal.submit("manual", repeatConfirmed = true) { before -> before() }
        var sentAgain = false
        try { journal.submit("manual") { before -> sentAgain = true; before() } } catch (_: IllegalStateException) { }
        assertFalse(sentAgain)
        assertTrue(journal.protected("manual"))
    }

    @Test fun activeClaimBlocksConcurrentWriterButCancelledUnsentClaimCanRetry() = runTest {
        val store = Store()
        val journal = EvalSubmissionJournal(store)
        val claimed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = launch { journal.submit("key") { claimed.complete(Unit); release.await() } }
        claimed.await()
        assertTrue(journal.protected("key"))
        try { journal.submit("key") { fail("Concurrent request must not run") } } catch (_: IllegalStateException) { }
        first.cancel()
        first.join()
        assertFalse(journal.protected("key"))
        journal.submit("key") { it() }
    }

    @Test fun dnsFailureAndGateReservationFailureNeverMarkRequestAsSent() = runTest {
        val store = Store()
        val journal = EvalSubmissionJournal(store)
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).retryOnConnectionFailure(false)
            .dns(object : okhttp3.Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> = throw UnknownHostException("test DNS failure")
            }).build()
        val gateStore = object : EvalRequestGate.Store {
            override fun read() = EvalRequestGate.State()
            override fun save(state: EvalRequestGate.State) { error("gate persistence failure") }
        }
        val cooldown = object : EvalRequestGate.Store {
            override fun read() = EvalRequestGate.State(blockedUntil = System.currentTimeMillis() + 60_000)
            override fun save(state: EvalRequestGate.State) { error("Must not reserve during cooldown") }
        }
        for (api in listOf(JwqeApi(client), JwqeApi(client, EvalRequestGate(gateStore)), JwqeApi(client, EvalRequestGate(cooldown)))) {
            try { journal.submit("target") { before -> api.submit("fake", emptyList(), "", before) } } catch (_: Exception) { }
            assertFalse(journal.protected("target"))
        }
    }
}
