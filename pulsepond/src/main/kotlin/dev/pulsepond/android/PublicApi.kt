package dev.pulsepond.android

public typealias PulsepondProperties = Map<String, Any?>

public sealed interface PulsepondIdentityPersistence {
    public data object Memory : PulsepondIdentityPersistence

    public data class DeviceStorage(
        public val namespace: String,
    ) : PulsepondIdentityPersistence
}

public data class PulsepondConfig(
    public val endpoint: String,
    public val writeKey: String,
    public val environment: String,
    public val appVersion: String? = null,
    public val release: String? = null,
    public val persistence: PulsepondIdentityPersistence = PulsepondIdentityPersistence.Memory,
    public val batchSize: Int = 20,
    public val flushIntervalMs: Long = 5_000,
    public val maxQueueSize: Int = 1_000,
    public val eventTtlMs: Long = 23 * 60 * 60 * 1_000,
    public val onDiagnostic: ((PulsepondDiagnostic) -> Unit)? = null,
)

public interface PulsepondClient {
    /** Returns the event UUIDv7, or null when the bounded in-memory queue is full. */
    public fun track(eventName: String, properties: PulsepondProperties = emptyMap()): String?

    /** Makes an immediate delivery attempt for events already enqueued. */
    public suspend fun flush()

    /** Discards unsent events and rotates random installation and session identifiers. */
    public fun reset()

    /** Makes one final bounded delivery attempt and permanently closes the client. */
    public suspend fun shutdown()
}

public enum class PulsepondDiagnosticCode {
    BATCH_REJECTED,
    DELIVERY_FAILED,
    QUEUE_FULL,
    RETRY_EXHAUSTED,
    STALE_EVENT,
    STORAGE_UNAVAILABLE,
}

public data class PulsepondDiagnostic(
    public val code: PulsepondDiagnosticCode,
    public val droppedEvents: Int,
    public val retryable: Boolean,
    public val status: Int? = null,
)

public open class PulsepondException(message: String) : IllegalArgumentException(message)

public class PulsepondConfigurationException(message: String) : PulsepondException(message)

public class PulsepondValidationException(message: String) : PulsepondException(message)

internal class DiagnosticSink(
    private val callback: ((PulsepondDiagnostic) -> Unit)?,
) {
    fun emit(diagnostic: PulsepondDiagnostic) {
        try {
            callback?.invoke(diagnostic)
        } catch (_: RuntimeException) {
            // Consumer diagnostics cannot break collection or expose event data.
        }
    }
}
