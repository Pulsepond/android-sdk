package dev.pulsepond.android

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

public class TransportTest {
    @Test
    public fun `sends only explicit headers and no Origin`() = runBlocking {
        val authorization = AtomicReference<String?>()
        val contentType = AtomicReference<String?>()
        val origin = AtomicReference<String?>()
        val body = AtomicReference<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/batch") { exchange ->
            authorization.set(exchange.requestHeaders.getFirst("Authorization"))
            contentType.set(exchange.requestHeaders.getFirst("Content-Type"))
            origin.set(exchange.requestHeaders.getFirst("Origin"))
            body.set(exchange.requestBody.bufferedReader().use { it.readText() })
            exchange.sendResponseHeaders(202, -1)
            exchange.close()
        }
        server.start()

        try {
            val transport = HttpPulsepondTransport(
                URI("http://127.0.0.1:${server.address.port}/v1/batch"),
                Dispatchers.IO,
            )
            val result = transport.post(TEST_WRITE_KEY, "{\"events\":[]}")

            assertEquals(TransportResult.Response(202, null), result)
            assertEquals("Bearer $TEST_WRITE_KEY", authorization.get())
            assertEquals("application/json", contentType.get())
            assertNull(origin.get())
            assertEquals("{\"events\":[]}", body.get())
        } finally {
            server.stop(0)
        }
    }
}
