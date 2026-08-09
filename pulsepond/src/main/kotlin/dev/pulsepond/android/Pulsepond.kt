package dev.pulsepond.android

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

public object Pulsepond {
    @JvmStatic
    public fun create(config: PulsepondConfig): PulsepondClient {
        val resolved = resolveConfig(config)
        if (resolved.persistence !is PulsepondIdentityPersistence.Memory) {
            throw PulsepondConfigurationException(
                "A Context is required for device identity persistence",
            )
        }
        return createClient(
            resolved = resolved,
            diagnostics = DiagnosticSink(resolved.onDiagnostic),
            initialIdentity = StoredIdentity(null, null, null),
            persistence = MemoryIdentityPersistenceWriter,
        )
    }

    @JvmStatic
    public suspend fun create(context: Context, config: PulsepondConfig): PulsepondClient {
        val resolved = resolveConfig(config)
        val diagnostics = DiagnosticSink(resolved.onDiagnostic)
        if (resolved.persistence is PulsepondIdentityPersistence.Memory) {
            return createClient(
                resolved = resolved,
                diagnostics = diagnostics,
                initialIdentity = StoredIdentity(null, null, null),
                persistence = MemoryIdentityPersistenceWriter,
            )
        }
        val namespace =
            (resolved.persistence as PulsepondIdentityPersistence.DeviceStorage).namespace
        val storage = withContext(Dispatchers.IO) {
            val primary = try {
                AndroidNoBackupIdentityStorage(context.applicationContext, namespace)
            } catch (_: RuntimeException) {
                diagnostics.emit(storageUnavailableDiagnostic())
                MemoryIdentityStorage()
            }
            ResilientIdentityStorage(
                primary = primary,
                fallback = MemoryIdentityStorage(),
                onUnavailable = {
                    diagnostics.emit(storageUnavailableDiagnostic())
                },
            )
        }
        val initial = withContext(Dispatchers.IO) { storage.read() }
        return createClient(
            resolved = resolved,
            diagnostics = diagnostics,
            initialIdentity = initial,
            persistence = AsyncIdentityPersistenceWriter(storage, Dispatchers.IO),
        )
    }

    private fun createClient(
        resolved: ResolvedConfig,
        diagnostics: DiagnosticSink,
        initialIdentity: StoredIdentity,
        persistence: IdentityPersistenceWriter,
    ): PulsepondClient {
        val runtime = PulsepondRuntime(
            clock = SystemClock,
            dispatcher = Dispatchers.IO,
            transport = HttpPulsepondTransport(resolved.endpoint),
            uuid = SecureUuidGenerator,
            jitter = SecureRetryJitter,
        )
        return PulsepondClientImpl(
            config = resolved,
            diagnostics = diagnostics,
            identity = IdentityManager(initialIdentity, SecureUuidGenerator, persistence),
            runtime = runtime,
        )
    }

    private fun storageUnavailableDiagnostic(): PulsepondDiagnostic =
        PulsepondDiagnostic(
            code = PulsepondDiagnosticCode.STORAGE_UNAVAILABLE,
            droppedEvents = 0,
            retryable = false,
        )
}
