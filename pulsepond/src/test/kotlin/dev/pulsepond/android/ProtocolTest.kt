package dev.pulsepond.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

public class ProtocolTest {
    @Test
    public fun `creates a deterministic closed Android envelope`() {
        val config = resolveConfig(
            PulsepondConfig(
                endpoint = "https://events.example.com/v1/batch",
                writeKey = TEST_WRITE_KEY,
                environment = "production",
                appVersion = "1.2.3",
                release = "android@1.2.3",
            ),
        )
        val event = createEvent(
            config = config,
            eventId = "01890f3e-e4b8-7cc3-98c8-7f0d7b4c9a11",
            eventName = "view_work",
            occurredAtMs = 1_688_169_600_000,
            identity = Identity(
                installationId = "01890f3e-e4b8-4cc3-98c8-7f0d7b4c9a10",
                sessionId = "01890f3e-e4b8-4cc3-98c8-7f0d7b4c9a12",
            ),
            properties = linkedMapOf(
                "work_id" to "work_123",
                "completed" to false,
                "position" to 3,
            ),
        )

        assertEquals(
            "{\"anonymous_installation_id\":\"01890f3e-e4b8-4cc3-98c8-7f0d7b4c9a10\"," +
                "\"app_version\":\"1.2.3\",\"environment\":\"production\"," +
                "\"event_id\":\"01890f3e-e4b8-7cc3-98c8-7f0d7b4c9a11\"," +
                "\"event_name\":\"view_work\",\"occurred_at\":\"2023-07-01T00:00:00.000Z\"," +
                "\"platform\":\"android\",\"properties\":{\"completed\":false," +
                "\"position\":3,\"work_id\":\"work_123\"}," +
                "\"release\":\"android@1.2.3\",\"schema_version\":1," +
                "\"session_id\":\"01890f3e-e4b8-4cc3-98c8-7f0d7b4c9a12\"}",
            event.serialized,
        )
        for (forbidden in listOf("cookie", "referrer", "url", "user_agent", "user_id")) {
            assertFalse(event.serialized.contains("\"$forbidden\""))
        }
    }

    @Test
    public fun `rejects invalid endpoint credentials and configuration bounds`() {
        for (config in listOf(
            PulsepondConfig("http://events.example.com/v1/batch", TEST_WRITE_KEY, "test"),
            PulsepondConfig("https://events.example.com/v1/batch?secret=1", TEST_WRITE_KEY, "test"),
            PulsepondConfig("https://events.example.com/v1/batch", "private", "test"),
            PulsepondConfig(
                "https://events.example.com/v1/batch",
                TEST_WRITE_KEY,
                "test",
                batchSize = 101,
            ),
        )) {
            assertThrows(PulsepondConfigurationException::class.java) {
                resolveConfig(config)
            }
        }
    }

    @Test
    public fun `requires a Context before enabling device identity persistence`() {
        assertThrows(PulsepondConfigurationException::class.java) {
            Pulsepond.create(
                PulsepondConfig(
                    endpoint = "https://events.example.com/v1/batch",
                    writeKey = TEST_WRITE_KEY,
                    environment = "test",
                    persistence = PulsepondIdentityPersistence.DeviceStorage("android_app"),
                ),
            )
        }
    }

    @Test
    public fun `rejects PII-shaped and lossy property values at the closed boundary`() {
        val config = testConfig()
        for (properties in listOf(
            mapOf("feedback" to " private "),
            mapOf("display_name" to "François"),
            mapOf("unsafe" to 1.5),
            mapOf("nested" to mapOf("value" to 1)),
            mapOf("too_large" to 9_007_199_254_740_992L),
        )) {
            assertThrows(PulsepondValidationException::class.java) {
                createEvent(
                    config,
                    "01890f3e-e4b8-7cc3-98c8-7f0d7b4c9a11",
                    "view_work",
                    1_700_000_000_000,
                    Identity(
                        "01890f3e-e4b8-4cc3-98c8-7f0d7b4c9a10",
                        "01890f3e-e4b8-4cc3-98c8-7f0d7b4c9a12",
                    ),
                    properties,
                )
            }
        }
    }
}
