package mlabeler.app.state

import kotlinx.coroutines.*
import kotlin.test.*

class PendingSavesTest {
    @Test fun failedTakeIsRetainedAndRetriedBeforeLaterTakes() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var writable = false
            val saved = mutableListOf<Int>()
            var errors = 0
            val writes = PendingSaves(scope) { errors++ }
            writes.submit { check(writable) { "Disk full" }; saved += 1 }
            writes.submit { saved += 2 }
            assertFalse(writes.flush())
            assertTrue(saved.isEmpty())
            assertTrue(errors > 0)
            writable = true
            assertTrue(writes.flush())
            assertEquals(listOf(1, 2), saved)
            assertTrue(writes.flush())
            assertEquals(listOf(1, 2), saved)
        } finally { scope.cancel() }
    }

    @Test fun flushWaitsForTheActualWrite() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val release = CompletableDeferred<Unit>()
            var written = false
            val writes = PendingSaves(scope) { throw it }
            writes.submit { release.await(); written = true }
            val closing = async { writes.flush() }
            yield()
            assertFalse(closing.isCompleted)
            release.complete(Unit)
            assertTrue(closing.await())
            assertTrue(written)
        } finally { scope.cancel() }
    }
}
