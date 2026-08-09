package dev.pulsepond.android

import android.content.Context
import kotlinx.coroutines.Dispatchers

public object Pulsepond {
    @JvmStatic
    public fun create(config: PulsepondConfig): PulsepondClient {
        if (config.persistence !is PulsepondIdentityPersistence.Memory) {
            throw PulsepondConfigurationException(
                "A Context is required for device identity persistence",
            )
        }
        return createClient(config, null)
    }

    @JvmStatic
    public fun create(context: Context, config: PulsepondConfig): PulsepondClient =
        createClient(config, context.applicationContext)

    private fun createClient(config: PulsepondConfig, context: Context?): PulsepondClient {
        val resolved = resolveConfig(config)
        val diagnostics = DiagnosticSink(resolved.onDiagnostic)
        val primaryStorage = when (val persistence = resolved.persistence) {
            PulsepondIdentityPersistence.Memory -> MemoryIdentityStorage()
            is PulsepondIdentityPersistence.DeviceStorage -> {
                if (context == null) {
                    throw PulsepondConfigurationException(
                        "A Context is required for device identity persistence",
                    )
                }
                try {
                    AndroidNoBackupIdentityStorage(context, persistence.namespace)
                } catch (_: RuntimeException) {
                    diagnostics.emit(
                        PulsepondDiagnostic(
                            code = PulsepondDiagnosticCode.STORAGE_UNAVAILABLE,
                            droppedEvents = 0,
                            retryable = false,
                        ),
                    )
                    MemoryIdentityStorage()
                }
            }
        }
        val storage = ResilientIdentityStorage(
            primary = primaryStorage,
            fallback = MemoryIdentityStorage(),
            onUnavailable = {
                diagnostics.emit(
                    PulsepondDiagnostic(
                        code = PulsepondDiagnosticCode.STORAGE_UNAVAILABLE,
                        droppedEvents = 0,
                        retryable = false,
                    ),
                )
            },
        )
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
            identity = IdentityManager(storage, SecureUuidGenerator),
            runtime = runtime,
        )
    }
}
