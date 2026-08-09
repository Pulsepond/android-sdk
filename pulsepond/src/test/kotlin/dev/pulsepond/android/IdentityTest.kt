package dev.pulsepond.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

public class IdentityTest {
    @Test
    public fun `keeps installation identity and rotates an inactive session`() {
        val storage = MemoryIdentityStorage()
        val manager = IdentityManager(storage, FakeUuidGenerator())
        val first = manager.current(1_000)
        val active = manager.current(1_000 + 30 * 60 * 1_000)
        val expired = manager.current(1_001 + 60 * 60 * 1_000)

        assertEquals(first.installationId, active.installationId)
        assertEquals(first.sessionId, active.sessionId)
        assertEquals(first.installationId, expired.installationId)
        assertNotEquals(first.sessionId, expired.sessionId)
    }

    @Test
    public fun `falls back once when persistent storage becomes unavailable`() {
        var reports = 0
        val storage = ResilientIdentityStorage(
            primary = object : IdentityStorage {
                override fun read(): StoredIdentity = error("unavailable")
                override fun write(value: StoredIdentity) = error("unavailable")
                override fun clear() = error("unavailable")
            },
            fallback = MemoryIdentityStorage(),
            onUnavailable = { reports += 1 },
        )
        val manager = IdentityManager(storage, FakeUuidGenerator())

        val identity = manager.current(1_000)
        manager.current(2_000)

        assertTrue(isCanonicalAnonymousId(identity.installationId))
        assertEquals(1, reports)
    }
}

