package dev.pulsepond.android

import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val MAX_RETRIES: Int = 5

internal data class QueuedEvent(
    val eventId: String,
    val serialized: String,
    val serializedBytes: Int,
    val occurredAtMs: Long,
    val sequence: Long,
)

internal data class EventBatch(
    val events: List<QueuedEvent>,
    val body: String,
)

internal class PulsepondClientImpl(
    private val config: ResolvedConfig,
    private val diagnostics: DiagnosticSink,
    private val identity: IdentityManager,
    private val runtime: PulsepondRuntime,
) : PulsepondClient {
    private val stateLock = Any()
    private val deliveryMutex = Mutex()
    private val shutdownMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + runtime.dispatcher)
    private val queue = ArrayDeque<QueuedEvent>()
    private var queueBytes = 0
    private var sequence = 0L
    private var generation = 0L
    private var effectiveBatchSize = config.batchSize
    private var retryBatch: EventBatch? = null
    private var retryEventId: String? = null
    private var retryAttempts = 0
    private var flushJob: Job? = null
    private var retryJob: Job? = null
    private var closing = false
    private var closed = false

    override fun track(eventName: String, properties: PulsepondProperties): String? {
        val initiallyFull = synchronized(stateLock) {
            if (closing || closed) {
                throw PulsepondValidationException(
                    "Pulsepond cannot track after shutdown has started",
                )
            }
            queue.size >= config.maxQueueSize || queueBytes >= MAX_QUEUE_BYTES
        }
        if (initiallyFull) {
            reportQueueFull()
            return null
        }

        val nowMs = runtime.clock.nowMs()
        val event = createEvent(
            config = config,
            eventId = runtime.uuid.v7(nowMs),
            eventName = eventName,
            occurredAtMs = nowMs,
            identity = identity.current(nowMs),
            properties = properties,
        )
        val eventBytes = event.serialized.utf8Size()
        var queueFull = false
        var flushImmediately = false
        synchronized(stateLock) {
            if (closing || closed) {
                throw PulsepondValidationException(
                    "Pulsepond cannot track after shutdown has started",
                )
            }
            if (queue.size >= config.maxQueueSize ||
                queueBytes >= MAX_QUEUE_BYTES ||
                queueBytes + eventBytes > MAX_QUEUE_BYTES
            ) {
                queueFull = true
            } else {
                sequence += 1
                queue.addLast(
                    QueuedEvent(
                        eventId = event.eventId,
                        serialized = event.serialized,
                        serializedBytes = eventBytes,
                        occurredAtMs = nowMs,
                        sequence = sequence,
                    ),
                )
                queueBytes += eventBytes
                flushImmediately = queue.size >= effectiveBatchSize
            }
        }
        if (queueFull) {
            reportQueueFull()
            return null
        }
        if (flushImmediately) {
            launchAutomaticFlush()
        } else {
            scheduleFlush()
        }
        return event.eventId
    }

    override suspend fun flush() {
        val state = synchronized(stateLock) {
            when {
                closed -> FlushState.Closed
                closing -> FlushState.Closing
                else -> {
                    cancelScheduledLocked()
                    FlushState.Ready(queue.lastOrNull()?.sequence)
                }
            }
        }
        when (state) {
            FlushState.Closed -> return
            FlushState.Closing -> {
                shutdownMutex.withLock { }
                return
            }
            is FlushState.Ready -> state.target?.let {
                runFlush(targetSequence = it, oneBatchOnly = false, automatic = false)
            }
        }
    }

    override fun reset() {
        val nowMs = runtime.clock.nowMs()
        synchronized(stateLock) {
            if (closing || closed) return
            generation += 1
            cancelScheduledLocked()
            queue.clear()
            queueBytes = 0
            effectiveBatchSize = config.batchSize
            resetRetryLocked()
            identity.reset(nowMs)
        }
    }

    override suspend fun shutdown() {
        shutdownMutex.withLock {
            val target = synchronized(stateLock) {
                if (closed) return@withLock
                closing = true
                cancelScheduledLocked()
                queue.lastOrNull()?.sequence
            }
            target?.let {
                runFlush(targetSequence = it, oneBatchOnly = true, automatic = false)
            }
            val dropped = synchronized(stateLock) {
                val count = queue.size
                queue.clear()
                queueBytes = 0
                resetRetryLocked()
                closed = true
                closing = false
                count
            }
            if (dropped > 0) {
                diagnostics.emit(
                    PulsepondDiagnostic(
                        code = PulsepondDiagnosticCode.DELIVERY_FAILED,
                        droppedEvents = dropped,
                        retryable = false,
                    ),
                )
            }
            scope.cancel()
        }
    }

    private fun launchAutomaticFlush() {
        val target = synchronized(stateLock) {
            if (closed || closing || retryJob != null) null else queue.lastOrNull()?.sequence
        } ?: return
        scope.launch {
            runFlush(targetSequence = target, oneBatchOnly = false, automatic = true)
        }
    }

    private fun scheduleFlush() {
        if (config.flushIntervalMs == 0L) return
        lateinit var job: Job
        synchronized(stateLock) {
            if (queue.isEmpty() || flushJob != null || retryJob != null || closing || closed) {
                return
            }
            job = scope.launch(start = CoroutineStart.LAZY) {
                delay(config.flushIntervalMs)
                val target = synchronized(stateLock) {
                    if (flushJob === job) flushJob = null
                    if (closing || closed || retryJob != null) null
                    else queue.lastOrNull()?.sequence
                }
                target?.let {
                    runFlush(targetSequence = it, oneBatchOnly = false, automatic = true)
                }
            }
            flushJob = job
        }
        job.start()
    }

    private suspend fun runFlush(
        targetSequence: Long,
        oneBatchOnly: Boolean,
        automatic: Boolean,
    ) {
        if (automatic && synchronized(stateLock) { retryJob != null }) return
        deliveryMutex.withLock {
            var sentBatches = 0
            while (true) {
                val stale = dropStaleEvents()
                if (stale > 0) {
                    diagnostics.emit(
                        PulsepondDiagnostic(
                            code = PulsepondDiagnosticCode.STALE_EVENT,
                            droppedEvents = stale,
                            retryable = false,
                        ),
                    )
                }
                val requestGeneration: Long
                val batch = synchronized(stateLock) {
                    if (closed) return@withLock
                    requestGeneration = generation
                    nextBatchLocked(targetSequence)
                } ?: return@withLock

                val result = runtime.transport.post(config.writeKey, batch.body)
                if (synchronized(stateLock) { generation != requestGeneration }) {
                    return@withLock
                }
                when (result) {
                    is TransportResult.Response -> when {
                        result.status == 202 -> {
                            synchronized(stateLock) {
                                removeBatchLocked(batch)
                                resetRetryLocked()
                            }
                        }
                        result.status == 413 && batch.events.size > 1 -> {
                            synchronized(stateLock) {
                                resetRetryLocked()
                                effectiveBatchSize = (batch.events.size / 2).coerceAtLeast(1)
                            }
                            if (oneBatchOnly) return@withLock
                            continue
                        }
                        result.status == 413 -> {
                            synchronized(stateLock) {
                                removeBatchLocked(batch)
                                resetRetryLocked()
                            }
                            diagnostics.emit(
                                PulsepondDiagnostic(
                                    code = PulsepondDiagnosticCode.BATCH_REJECTED,
                                    droppedEvents = 1,
                                    retryable = false,
                                    status = 413,
                                ),
                            )
                        }
                        result.status == 408 || result.status == 429 || result.status >= 500 -> {
                            if (oneBatchOnly) return@withLock
                            if (!handleRetry(batch, retryAfterMs(result.retryAfter, runtime.clock.nowMs()))) {
                                return@withLock
                            }
                        }
                        else -> {
                            synchronized(stateLock) {
                                removeBatchLocked(batch)
                                resetRetryLocked()
                            }
                            diagnostics.emit(
                                PulsepondDiagnostic(
                                    code = PulsepondDiagnosticCode.BATCH_REJECTED,
                                    droppedEvents = batch.events.size,
                                    retryable = false,
                                    status = result.status,
                                ),
                            )
                        }
                    }
                    TransportResult.NetworkFailure -> {
                        if (oneBatchOnly) return@withLock
                        if (!handleRetry(batch, null)) return@withLock
                    }
                }

                sentBatches += 1
                if (oneBatchOnly || sentBatches >= 100) return@withLock
            }
        }
        scheduleFlush()
    }

    /** Returns true only when retry exhaustion dropped the batch and flushing may continue. */
    private fun handleRetry(batch: EventBatch, retryAfterMs: Long?): Boolean {
        val attempt = synchronized(stateLock) {
            val firstEventId = batch.events.firstOrNull()?.eventId ?: return true
            if (retryEventId != firstEventId) {
                retryEventId = firstEventId
                retryAttempts = 0
                retryBatch = batch
            } else if (retryBatch == null) {
                retryBatch = batch
            }
            retryAttempts += 1
            retryAttempts
        }
        if (attempt > MAX_RETRIES) {
            synchronized(stateLock) {
                removeBatchLocked(batch)
                resetRetryLocked()
            }
            diagnostics.emit(
                PulsepondDiagnostic(
                    code = PulsepondDiagnosticCode.RETRY_EXHAUSTED,
                    droppedEvents = batch.events.size,
                    retryable = false,
                ),
            )
            return true
        }
        diagnostics.emit(
            PulsepondDiagnostic(
                code = PulsepondDiagnosticCode.DELIVERY_FAILED,
                droppedEvents = 0,
                retryable = true,
            ),
        )
        scheduleRetry(retryAfterMs ?: runtime.jitter.delayMs(attempt))
        return false
    }

    private fun scheduleRetry(delayMs: Long) {
        lateinit var job: Job
        synchronized(stateLock) {
            flushJob?.cancel()
            flushJob = null
            retryJob?.cancel()
            job = scope.launch(start = CoroutineStart.LAZY) {
                delay(delayMs.coerceIn(0, 30_000))
                val target = synchronized(stateLock) {
                    if (retryJob === job) retryJob = null
                    if (closing || closed) null else queue.lastOrNull()?.sequence
                }
                target?.let {
                    runFlush(targetSequence = it, oneBatchOnly = false, automatic = true)
                }
            }
            retryJob = job
        }
        job.start()
    }

    private fun dropStaleEvents(): Int = synchronized(stateLock) {
        val nowMs = runtime.clock.nowMs()
        val retained = queue.filter { nowMs - it.occurredAtMs <= config.eventTtlMs }
        val dropped = queue.size - retained.size
        if (dropped > 0) {
            queue.clear()
            queue.addAll(retained)
            queueBytes = retained.sumOf { it.serializedBytes }
            resetRetryLocked()
        }
        dropped
    }

    private fun nextBatchLocked(targetSequence: Long): EventBatch? {
        retryBatch?.let { return it }
        val selected = mutableListOf<QueuedEvent>()
        var bodyBytes = "{\"events\":[]}".utf8Size()
        for (event in queue) {
            if (event.sequence > targetSequence || selected.size >= effectiveBatchSize) break
            val candidateBytes = bodyBytes + event.serializedBytes + if (selected.isEmpty()) 0 else 1
            if (candidateBytes > MAX_BATCH_BYTES && selected.isNotEmpty()) break
            selected += event
            bodyBytes = candidateBytes
        }
        if (selected.isEmpty()) return null
        return EventBatch(
            events = selected,
            body = selected.joinToString(",", "{\"events\":[", "]}") { it.serialized },
        )
    }

    private fun removeBatchLocked(batch: EventBatch) {
        val actualIds = queue.take(batch.events.size).map { it.eventId }
        val expectedIds = batch.events.map { it.eventId }
        if (actualIds != expectedIds) return
        repeat(batch.events.size) {
            val removed = queue.removeFirst()
            queueBytes -= removed.serializedBytes
        }
    }

    private fun resetRetryLocked() {
        retryBatch = null
        retryEventId = null
        retryAttempts = 0
        retryJob?.cancel()
        retryJob = null
    }

    private fun cancelScheduledLocked() {
        flushJob?.cancel()
        flushJob = null
        retryJob?.cancel()
        retryJob = null
    }

    private fun reportQueueFull() {
        diagnostics.emit(
            PulsepondDiagnostic(
                code = PulsepondDiagnosticCode.QUEUE_FULL,
                droppedEvents = 1,
                retryable = false,
            ),
        )
    }

    private sealed interface FlushState {
        data object Closed : FlushState
        data object Closing : FlushState
        data class Ready(val target: Long?) : FlushState
    }
}
