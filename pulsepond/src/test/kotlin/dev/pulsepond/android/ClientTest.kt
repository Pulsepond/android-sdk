package dev.pulsepond.android

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

public class ClientTest {
    @Test
    public fun `sends explicit events with the publishable key`() = runBlocking {
        val transport = FakeTransport()
        val client = testClient(transport = transport)

        val eventId = client.track("app_open", mapOf("source" to "launcher"))
        client.flush()

        assertNotNull(eventId)
        assertEquals(1, transport.requests.size)
        assertEquals(TEST_WRITE_KEY, transport.requests.single().first)
        assertEquals(1, eventCount(transport.requests.single().second))
        assertFalse(transport.requests.single().second.contains("Authorization"))
        client.shutdown()
    }

    @Test
    public fun `retries a byte-identical frozen batch`() = runBlocking {
        val transport = FakeTransport(
            listOf(
                TransportResult.Response(503, null),
                TransportResult.Response(202, null),
            ),
        )
        val client = testClient(transport = transport)
        client.track("audio_play", mapOf("work_id" to "work_123"))

        client.flush()
        client.flush()

        assertEquals(2, transport.requests.size)
        assertEquals(transport.requests[0].second, transport.requests[1].second)
        client.shutdown()
    }

    @Test
    public fun `splits a rejected multi-event batch without changing event bytes`() = runBlocking {
        val transport = FakeTransport(
            listOf(
                TransportResult.Response(413, null),
                TransportResult.Response(202, null),
                TransportResult.Response(202, null),
            ),
        )
        val client = testClient(
            config = testConfig(batchSize = 4),
            transport = transport,
        )

        repeat(4) { client.track("view_work", mapOf("position" to it)) }
        client.flush()

        assertEquals(listOf(4, 2, 2), transport.requests.map { eventCount(it.second) })
        val originalIds = eventIds(transport.requests.first().second)
        val splitIds = transport.requests.drop(1).flatMap { eventIds(it.second) }
        assertEquals(originalIds, splitIds)
        client.shutdown()
    }

    @Test
    public fun `bounds the queue and reports only redacted diagnostics`() = runBlocking {
        val diagnostics = mutableListOf<PulsepondDiagnostic>()
        val pending = CompletableDeferred<TransportResult>()
        val transport = PulsepondTransport { _, _ -> pending.await() }
        val config = testConfig(
            batchSize = 1,
            maxQueueSize = 1,
            onDiagnostic = diagnostics::add,
        )
        val client = testClient(config = config, transport = transport)
        client.track("first", mapOf("private" to "secret"))
        val second = client.track("second")

        assertNull(second)
        assertEquals(PulsepondDiagnosticCode.QUEUE_FULL, diagnostics.single().code)
        assertFalse(diagnostics.toString().contains("secret"))
        assertFalse(diagnostics.toString().contains(TEST_WRITE_KEY))
        pending.complete(TransportResult.Response(202, null))
        client.shutdown()
    }

    @Test
    public fun `drops stale events before batching`() = runBlocking {
        val diagnostics = mutableListOf<PulsepondDiagnostic>()
        val clock = FakeClock()
        val transport = FakeTransport()
        val config = testConfig(eventTtlMs = 60_000, onDiagnostic = diagnostics::add)
        val client = testClient(config = config, clock = clock, transport = transport)
        client.track("app_open")
        clock.value += 60_001

        client.flush()

        assertEquals(0, transport.requests.size)
        assertEquals(PulsepondDiagnosticCode.STALE_EVENT, diagnostics.single().code)
        client.shutdown()
    }

    @Test
    public fun `reset rotates identity and shutdown rejects tracking`() = runBlocking {
        val transport = FakeTransport()
        val client = testClient(transport = transport)
        client.track("before")
        client.flush()
        val first = transport.requests.single().second
        client.reset()
        client.track("after")
        client.flush()
        val second = transport.requests.last().second

        assertFalse(extractField(first, "anonymous_installation_id") ==
            extractField(second, "anonymous_installation_id"))
        client.shutdown()
        assertThrows(PulsepondValidationException::class.java) {
            client.track("closed")
        }
        Unit
    }

    @Test
    public fun `exhausts bounded retries with a redacted permanent drop`() = runBlocking {
        val diagnostics = mutableListOf<PulsepondDiagnostic>()
        val transport = FakeTransport(
            List(6) { TransportResult.NetworkFailure },
        )
        val config = testConfig(onDiagnostic = diagnostics::add)
        val client = testClient(config = config, transport = transport)
        client.track("audio_play", mapOf("work_id" to "private_value"))

        repeat(6) { client.flush() }
        client.flush()

        assertEquals(6, transport.requests.size)
        assertEquals(
            1,
            diagnostics.count { it.code == PulsepondDiagnosticCode.RETRY_EXHAUSTED },
        )
        assertFalse(diagnostics.toString().contains("private_value"))
        assertFalse(diagnostics.toString().contains(TEST_WRITE_KEY))
        client.shutdown()
    }

    private fun extractField(body: String, field: String): String =
        Regex("\\\"$field\\\":\\\"([^\\\"]+)\\\"").find(body)?.groupValues?.get(1)
            ?: error("missing $field")

    private fun eventIds(body: String): List<String> =
        Regex("\\\"event_id\\\":\\\"([^\\\"]+)\\\"")
            .findAll(body)
            .map { it.groupValues[1] }
            .toList()
}
