package dev.pulsepond.android

import java.net.URI
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal const val MAX_BATCH_EVENTS: Int = 100
internal const val MAX_BATCH_BYTES: Int = 60_000
internal const val MAX_QUEUE_BYTES: Int = 1_000_000
private const val MAX_PROPERTIES: Int = 32
private const val MAX_PROPERTIES_BYTES: Int = 20_000
private const val MAX_SAFE_INTEGER: Long = 9_007_199_254_740_991
private val slug = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]*$")
private val writeKey = Regex("^ppw_v1_[0-9a-f]{32}_[0-9a-f]{64}$")

internal data class ResolvedConfig(
    val endpoint: URI,
    val writeKey: String,
    val environment: String,
    val appVersion: String?,
    val release: String?,
    val persistence: PulsepondIdentityPersistence,
    val batchSize: Int,
    val flushIntervalMs: Long,
    val maxQueueSize: Int,
    val eventTtlMs: Long,
    val onDiagnostic: ((PulsepondDiagnostic) -> Unit)?,
)

internal data class EventEnvelope(
    val eventId: String,
    val serialized: String,
)

internal fun resolveConfig(config: PulsepondConfig): ResolvedConfig {
    val endpoint = validateEndpoint(config.endpoint)
    if (!writeKey.matches(config.writeKey)) {
        throw PulsepondConfigurationException(
            "writeKey must be a canonical Pulsepond publishable key",
        )
    }
    validateConfigurationField {
        validateSlug("environment", config.environment, 32)
        validateOptionalText("appVersion", config.appVersion, 64)
        validateOptionalText("release", config.release, 128)
        val persistence = config.persistence
        if (persistence is PulsepondIdentityPersistence.DeviceStorage) {
            validateSlug("persistence namespace", persistence.namespace, 64)
        }
    }
    if (config.batchSize !in 1..MAX_BATCH_EVENTS) {
        throw PulsepondConfigurationException("batchSize must be from 1 through 100")
    }
    if (config.flushIntervalMs !in 0..3_600_000) {
        throw PulsepondConfigurationException(
            "flushIntervalMs must be from 0 through 3600000",
        )
    }
    if (config.maxQueueSize !in config.batchSize..10_000) {
        throw PulsepondConfigurationException(
            "maxQueueSize must be from batchSize through 10000",
        )
    }
    if (config.eventTtlMs !in 60_000..604_800_000) {
        throw PulsepondConfigurationException(
            "eventTtlMs must be from 60000 through 604800000",
        )
    }
    return ResolvedConfig(
        endpoint = endpoint,
        writeKey = config.writeKey,
        environment = config.environment,
        appVersion = config.appVersion,
        release = config.release,
        persistence = config.persistence,
        batchSize = config.batchSize,
        flushIntervalMs = config.flushIntervalMs,
        maxQueueSize = config.maxQueueSize,
        eventTtlMs = config.eventTtlMs,
        onDiagnostic = config.onDiagnostic,
    )
}

internal fun createEvent(
    config: ResolvedConfig,
    eventId: String,
    eventName: String,
    occurredAtMs: Long,
    identity: Identity,
    properties: PulsepondProperties,
): EventEnvelope {
    validateSlug("eventName", eventName, 64)
    val serializedProperties = serializeProperties(properties)
    val fields = mutableListOf<String>()
    fields += jsonField("anonymous_installation_id", identity.installationId)
    config.appVersion?.let { fields += jsonField("app_version", it) }
    fields += jsonField("environment", config.environment)
    fields += jsonField("event_id", eventId)
    fields += jsonField("event_name", eventName)
    fields += jsonField("occurred_at", formatTimestamp(occurredAtMs))
    fields += jsonField("platform", "android")
    fields += "\"properties\":$serializedProperties"
    config.release?.let { fields += jsonField("release", it) }
    fields += "\"schema_version\":1"
    fields += jsonField("session_id", identity.sessionId)
    return EventEnvelope(eventId, fields.joinToString(",", "{", "}"))
}

private fun serializeProperties(properties: PulsepondProperties): String {
    if (properties.size > MAX_PROPERTIES) {
        throw PulsepondValidationException("properties must contain at most 32 values")
    }
    val entries = properties.entries.sortedBy { it.key }.map { (name, value) ->
        validateSlug("property name", name, 64)
        val serializedValue = when (value) {
            null -> "null"
            is Boolean -> value.toString()
            is Byte -> value.toString()
            is Short -> value.toString()
            is Int -> value.toString()
            is Long -> {
                if (value !in -MAX_SAFE_INTEGER..MAX_SAFE_INTEGER) {
                    throw PulsepondValidationException(
                        "numeric properties must be JavaScript-safe integers",
                    )
                }
                value.toString()
            }
            is String -> {
                if (!isPrintableText(value, 256)) {
                    throw PulsepondValidationException(
                        "string properties must be trimmed printable ASCII within 256 characters",
                    )
                }
                jsonString(value)
            }
            else -> throw PulsepondValidationException(
                "properties may contain only null, booleans, safe integers, or strings",
            )
        }
        "${jsonString(name)}:$serializedValue"
    }
    val serialized = entries.joinToString(",", "{", "}")
    if (serialized.utf8Size() > MAX_PROPERTIES_BYTES) {
        throw PulsepondValidationException("properties must serialize within 20000 bytes")
    }
    return serialized
}

internal fun String.utf8Size(): Int = toByteArray(StandardCharsets.UTF_8).size

private fun validateEndpoint(value: String): URI {
    val endpoint = try {
        URI(value)
    } catch (_: Exception) {
        throw PulsepondConfigurationException("endpoint must be an absolute URL")
    }
    val local = endpoint.host == "localhost" ||
        endpoint.host == "127.0.0.1" ||
        endpoint.host == "[::1]" ||
        endpoint.host == "::1"
    if (endpoint.isOpaque || endpoint.host == null ||
        (endpoint.scheme != "https" && !(endpoint.scheme == "http" && local))
    ) {
        throw PulsepondConfigurationException(
            "endpoint must use HTTPS, or HTTP on localhost",
        )
    }
    if (endpoint.userInfo != null || endpoint.query != null || endpoint.fragment != null ||
        endpoint.path != "/v1/batch"
    ) {
        throw PulsepondConfigurationException(
            "endpoint must be an origin followed by the exact /v1/batch path",
        )
    }
    return endpoint
}

private fun validateConfigurationField(block: () -> Unit) {
    try {
        block()
    } catch (error: PulsepondValidationException) {
        throw PulsepondConfigurationException(error.message ?: "invalid configuration")
    }
}

internal fun validateSlug(field: String, value: String, maximum: Int) {
    if (value.isEmpty() || value.length > maximum || !slug.matches(value)) {
        throw PulsepondValidationException(
            "$field must be an ASCII slug within $maximum characters",
        )
    }
}

private fun validateOptionalText(field: String, value: String?, maximum: Int) {
    if (value != null && !isPrintableText(value, maximum)) {
        throw PulsepondValidationException(
            "$field must be trimmed printable ASCII within $maximum characters",
        )
    }
}

private fun isPrintableText(value: String, maximum: Int): Boolean =
    value.isNotEmpty() && value.length <= maximum &&
        value.first().code in 0x21..0x7e && value.last().code in 0x21..0x7e &&
        value.all { it.code in 0x20..0x7e }

private fun jsonField(name: String, value: String): String =
    "${jsonString(name)}:${jsonString(value)}"

private fun jsonString(value: String): String = buildString(value.length + 2) {
    append('"')
    value.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000c' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(character)
        }
    }
    append('"')
}

private fun formatTimestamp(timestampMs: Long): String {
    if (timestampMs < 0) {
        throw PulsepondConfigurationException("Pulsepond received an invalid system clock value")
    }
    return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(timestampMs))
}
