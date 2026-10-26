package li.lyc.lzuapp.data.api

import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvalTransportTest {
    @Test fun sendHookRunsBeforeHeadersAndFailedHookSendsNoHeaders() = runTest {
        for (failPersistence in listOf(false, true)) {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 5000
                val marked = AtomicBoolean(false)
                val observed = CompletableFuture<Boolean>()
                val worker = Thread {
                    try {
                        server.accept().use { socket ->
                            socket.soTimeout = 5000
                            val input = socket.getInputStream().bufferedReader()
                            val first = input.readLine()
                            observed.complete(first != null)
                            if (first != null) {
                                check(marked.get())
                                var length = 0
                                while (true) {
                                    val line = input.readLine() ?: break
                                    if (line.isEmpty()) break
                                    if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                                }
                                repeat(length) { input.read() }
                                val body = "{\"code\":200}"
                                socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n" + body).toByteArray())
                            }
                        }
                    } catch (e: Exception) { observed.completeExceptionally(e) }
                }.apply { isDaemon = true; start() }
                val client = JwqeApi.defaultClient().newBuilder().proxy(Proxy.NO_PROXY).build()
                val api = JwqeApi(client, baseUrl = "http://127.0.0.1:${server.localPort}")
                val result = runCatching { api.submit("fake", emptyList(), "") {
                    check(!failPersistence) { "persistence failed" }
                    marked.set(true)
                } }
                assertEquals(!failPersistence, result.isSuccess)
                assertEquals(!failPersistence, observed.get(5, TimeUnit.SECONDS))
                worker.join(5000)
                assertTrue(!worker.isAlive)
            }
        }
    }
}
