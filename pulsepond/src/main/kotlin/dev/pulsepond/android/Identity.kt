package dev.pulsepond.android

import android.content.Context
import android.util.AtomicFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.SecureRandom

private const val SESSION_TIMEOUT_MS: Long = 30 * 60 * 1_000
private const val MAX_UUID_V7_TIMESTAMP: Long = 0xffffffffffff
private val canonicalAnonymousId =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[47][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

internal data class Identity(
    val installationId: String,
    val sessionId: String,
)

internal data class StoredIdentity(
    val installationId: String?,
    val sessionId: String?,
    val sessionActivityMs: Long?,
)

internal interface IdentityStorage {
    fun read(): StoredIdentity

    fun write(value: StoredIdentity)

    fun clear()
}

internal class MemoryIdentityStorage : IdentityStorage {
    private var value = StoredIdentity(null, null, null)

    override fun read(): StoredIdentity = value

    override fun write(value: StoredIdentity) {
        this.value = value
    }

    override fun clear() {
        value = StoredIdentity(null, null, null)
    }
}

internal class AndroidNoBackupIdentityStorage(
    context: Context,
    namespace: String,
) : IdentityStorage {
    private val file = AtomicFile(
        File(context.noBackupFilesDir, "pulsepond-$namespace.identity"),
    )

    override fun read(): StoredIdentity {
        if (!file.baseFile.exists()) return StoredIdentity(null, null, null)
        return DataInputStream(file.openRead().buffered()).use { input ->
            StoredIdentity(
                installationId = input.readNullableString(),
                sessionId = input.readNullableString(),
                sessionActivityMs = if (input.readBoolean()) input.readLong() else null,
            )
        }
    }

    override fun write(value: StoredIdentity) {
        val output = file.startWrite()
        try {
            val data = DataOutputStream(output.buffered())
            data.writeNullableString(value.installationId)
            data.writeNullableString(value.sessionId)
            data.writeBoolean(value.sessionActivityMs != null)
            value.sessionActivityMs?.let(data::writeLong)
            data.flush()
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    override fun clear() {
        file.delete()
    }

    private fun DataInputStream.readNullableString(): String? =
        if (readBoolean()) readUTF() else null

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        value?.let(::writeUTF)
    }
}

internal class ResilientIdentityStorage(
    primary: IdentityStorage,
    private val fallback: IdentityStorage,
    private val onUnavailable: () -> Unit,
) : IdentityStorage {
    private var active = primary
    private var reported = false

    @Synchronized
    override fun read(): StoredIdentity = execute { it.read() }

    @Synchronized
    override fun write(value: StoredIdentity) {
        execute {
            it.write(value)
            Unit
        }
    }

    @Synchronized
    override fun clear() {
        execute {
            it.clear()
            Unit
        }
    }

    private fun <T> execute(block: (IdentityStorage) -> T): T = try {
        block(active)
    } catch (_: Exception) {
        active = fallback
        if (!reported) {
            reported = true
            onUnavailable()
        }
        block(fallback)
    }
}

internal class IdentityManager(
    private val storage: IdentityStorage,
    private val uuid: UuidGenerator,
) {
    private var cached: StoredIdentity? = null

    @Synchronized
    fun current(nowMs: Long): Identity {
        val stored = cached ?: storage.read()
        val installationId = stored.installationId
            ?.takeIf(::isCanonicalAnonymousId)
            ?: uuid.v4()
        val reusableSession = stored.sessionId?.takeIf(::isCanonicalAnonymousId)
        val lastActivity = stored.sessionActivityMs
        val sessionId = if (
            reusableSession != null &&
            lastActivity != null &&
            nowMs >= lastActivity &&
            nowMs - lastActivity <= SESSION_TIMEOUT_MS
        ) {
            reusableSession
        } else {
            uuid.v4()
        }
        val updated = StoredIdentity(installationId, sessionId, nowMs)
        cached = updated
        storage.write(updated)
        return Identity(installationId, sessionId)
    }

    @Synchronized
    fun reset(nowMs: Long) {
        storage.clear()
        val updated = StoredIdentity(uuid.v4(), uuid.v4(), nowMs)
        cached = updated
        storage.write(updated)
    }
}

internal fun isCanonicalAnonymousId(value: String): Boolean =
    canonicalAnonymousId.matches(value)

internal interface UuidGenerator {
    fun v4(): String

    fun v7(timestampMs: Long): String
}

internal object SecureUuidGenerator : UuidGenerator {
    private val random = SecureRandom()

    override fun v4(): String {
        val bytes = ByteArray(16).also(random::nextBytes)
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        return formatUuid(bytes)
    }

    override fun v7(timestampMs: Long): String {
        if (timestampMs !in 0..MAX_UUID_V7_TIMESTAMP) {
            throw PulsepondConfigurationException(
                "Pulsepond received an invalid system clock value",
            )
        }
        val bytes = ByteArray(16).also(random::nextBytes)
        var remaining = timestampMs
        for (index in 5 downTo 0) {
            bytes[index] = (remaining and 0xff).toByte()
            remaining = remaining ushr 8
        }
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x70).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        return formatUuid(bytes)
    }
}

private fun formatUuid(bytes: ByteArray): String {
    val hex = bytes.joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
    return listOf(
        hex.substring(0, 8),
        hex.substring(8, 12),
        hex.substring(12, 16),
        hex.substring(16, 20),
        hex.substring(20),
    ).joinToString("-")
}
