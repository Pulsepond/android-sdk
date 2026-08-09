package dev.pulsepond.android

import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal const val TEST_WRITE_KEY: String =
    "ppw_v1_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

internal fun testConfig(
    batchSize: Int = 20,
    eventTtlMs: Long = 23 * 60 * 60 * 1_000,
    maxQueueSize: Int = 1_000,
    onDiagnostic: ((PulsepondDiagnostic) -> Unit)? = null,
): ResolvedConfig = resolveConfig(
    PulsepondConfig(
        endpoint = "http://localhost:8787/v1/batch",
        writeKey = TEST_WRITE_KEY,
        environment = "test",
        batchSize = batchSize,
        eventTtlMs = eventTtlMs,
        flushIntervalMs = 0,
        maxQueueSize = maxQueueSize,
        onDiagnostic = onDiagnostic,
    ),
)

internal class FakeClock(var value: Long = 1_700_000_000_000) : Clock {
    override fun nowMs(): Long = value
}

internal class FakeUuidGenerator : UuidGenerator {
    private var counter = 0L

    override fun v4(): String {
        counter += 1
        return "00000000-0000-4000-8000-${counter.toString(16).padStart(12, '0')}"
    }

    override fun v7(timestampMs: Long): String {
        counter += 1
        val timestamp = timestampMs.toString(16).padStart(12, '0')
        return "${timestamp.substring(0, 8)}-${timestamp.substring(8)}-7000-8000-${counter.toString(16).padStart(12, '0')}"
    }
}

internal class FakeTransport(
    results: List<TransportResult> = emptyList(),
) : PulsepondTransport {
    val requests = mutableListOf<Pair<String, String>>()
    private val results = ArrayDeque(results)

    override suspend fun post(writeKey: String, body: String): TransportResult {
        requests += writeKey to body
        return if (results.isEmpty()) TransportResult.Response(202, null)
        else results.removeFirst()
    }
}

internal fun testClient(
    config: ResolvedConfig = testConfig(),
    clock: FakeClock = FakeClock(),
    transport: PulsepondTransport = FakeTransport(),
    uuid: UuidGenerator = FakeUuidGenerator(),
    storage: IdentityStorage = MemoryIdentityStorage(),
    dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
): PulsepondClientImpl = PulsepondClientImpl(
    config = config,
    diagnostics = DiagnosticSink(config.onDiagnostic),
    identity = IdentityManager(
        initial = storage.read(),
        uuid = uuid,
        persistence = object : IdentityPersistenceWriter {
            override fun submit(value: StoredIdentity) = storage.write(value)
            override suspend fun close() = Unit
        },
    ),
    runtime = PulsepondRuntime(
        clock = clock,
        dispatcher = dispatcher,
        transport = transport,
        uuid = uuid,
        jitter = RetryJitter { 30_000 },
    ),
)

internal fun eventCount(body: String): Int = "\"event_id\"".toRegex().findAll(body).count()
