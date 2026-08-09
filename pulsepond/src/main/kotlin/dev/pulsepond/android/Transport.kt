package dev.pulsepond.android

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal sealed interface TransportResult {
    data class Response(
        val status: Int,
        val retryAfter: String?,
    ) : TransportResult

    data object NetworkFailure : TransportResult
}

internal fun interface PulsepondTransport {
    suspend fun post(writeKey: String, body: String): TransportResult
}

internal class HttpPulsepondTransport(
    private val endpoint: URI,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PulsepondTransport {
    override suspend fun post(writeKey: String, body: String): TransportResult =
        withContext(dispatcher) {
            var connection: HttpURLConnection? = null
            try {
                val current = endpoint.toURL().openConnection() as HttpURLConnection
                connection = current
                val bytes = body.toByteArray(StandardCharsets.UTF_8)
                current.requestMethod = "POST"
                current.instanceFollowRedirects = false
                current.useCaches = false
                current.doOutput = true
                current.connectTimeout = 10_000
                current.readTimeout = 10_000
                current.setFixedLengthStreamingMode(bytes.size)
                current.setRequestProperty("Authorization", "Bearer $writeKey")
                current.setRequestProperty("Content-Type", "application/json")
                current.outputStream.use { it.write(bytes) }
                TransportResult.Response(
                    status = current.responseCode,
                    retryAfter = current.getHeaderField("Retry-After"),
                )
            } catch (_: IOException) {
                TransportResult.NetworkFailure
            } finally {
                connection?.disconnect()
            }
        }
}

internal interface Clock {
    fun nowMs(): Long
}

internal object SystemClock : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()
}

internal data class PulsepondRuntime(
    val clock: Clock,
    val dispatcher: CoroutineDispatcher,
    val transport: PulsepondTransport,
    val uuid: UuidGenerator,
    val jitter: RetryJitter,
)

internal fun interface RetryJitter {
    fun delayMs(attempt: Int): Long
}

internal object SecureRetryJitter : RetryJitter {
    private val random = SecureRandom()

    override fun delayMs(attempt: Int): Long {
        val ceiling = (1_000L shl (attempt - 1)).coerceAtMost(30_000)
        return if (ceiling == 30_000L) {
            random.nextInt(30_001).toLong()
        } else {
            random.nextInt(ceiling.toInt() + 1).toLong()
        }
    }
}

internal fun retryAfterMs(value: String?, nowMs: Long): Long? {
    val trimmed = value?.trim() ?: return null
    if (trimmed.matches(Regex("^[0-9]+$"))) {
        val seconds = trimmed.toLongOrNull() ?: return 30_000
        return if (seconds >= 30) 30_000 else seconds * 1_000
    }
    val date = try {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("GMT")
        }.parse(trimmed)?.time
    } catch (_: Exception) {
        null
    } ?: return null
    return (date - nowMs).coerceIn(0, 30_000)
}
