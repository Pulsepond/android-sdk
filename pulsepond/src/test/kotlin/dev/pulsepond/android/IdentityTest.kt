package dev.pulsepond.android

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

public class IdentityTest {
    @Test
    public fun `keeps installation identity and rotates an inactive session`() {
        val storage = MemoryIdentityStorage()
        val manager = IdentityManager(
            initial = storage.read(),
            uuid = FakeUuidGenerator(),
            persistence = object : IdentityPersistenceWriter {
                override fun submit(value: StoredIdentity) = storage.write(value)
                override suspend fun close() = Unit
            },
        )
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
            },
            fallback = MemoryIdentityStorage(),
            onUnavailable = { reports += 1 },
        )
        val manager = IdentityManager(
            initial = storage.read(),
            uuid = FakeUuidGenerator(),
            persistence = object : IdentityPersistenceWriter {
                override fun submit(value: StoredIdentity) = storage.write(value)
                override suspend fun close() = Unit
            },
        )

        val identity = manager.current(1_000)
        manager.current(2_000)

        assertTrue(isCanonicalAnonymousId(identity.installationId))
        assertEquals(1, reports)
    }

    @Test
    public fun `persists identity on the owned writer instead of the caller thread`() = runBlocking {
        val callerThread = Thread.currentThread().name
        val written = CountDownLatch(1)
        var writeThread: String? = null
        var stored = StoredIdentity(null, null, null)
        val storage = object : IdentityStorage {
            override fun read(): StoredIdentity = stored
            override fun write(value: StoredIdentity) {
                writeThread = Thread.currentThread().name
                stored = value
                written.countDown()
            }
        }
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "pulsepond-identity-writer")
        }
        val dispatcher = executor.asCoroutineDispatcher()
        val persistence = AsyncIdentityPersistenceWriter(storage, dispatcher)
        val manager = IdentityManager(storage.read(), FakeUuidGenerator(), persistence)

        try {
            val identity = manager.current(1_000)
            assertTrue(written.await(5, TimeUnit.SECONDS))
            manager.close()

            assertNotEquals(callerThread, writeThread)
            assertEquals(identity.installationId, stored.installationId)
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }
}
