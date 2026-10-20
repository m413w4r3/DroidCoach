package dev.atlas.coach.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistenceSafetyTest {
    @Test
    fun writeFailuresAreReturnedAsStableStorageAndSerializationKinds() {
        val ioBackend = TestBackend().apply { failWrites = true }
        val ioFailure = runCatching { LocalStateStore(ioBackend).write(mapOf("value" to "private")) }.exceptionOrNull()
        assertEquals(PersistFailureKind.STORAGE_IO, (ioFailure as PersistWriteException).reason)

        val invalidFailure = runCatching {
            LocalStateStore(TestBackend()).write(mapOf("schemaVersion" to 99))
        }.exceptionOrNull()
        assertEquals(PersistFailureKind.INVALID_STATE, (invalidFailure as PersistWriteException).reason)

        val serializationFailure = runCatching {
            LocalStateStore(TestBackend()).write(mapOf("value" to object {
                override fun toString(): String = error("private serialization detail")
            }))
        }.exceptionOrNull()
        assertEquals(PersistFailureKind.SERIALIZATION, (serializationFailure as PersistWriteException).reason)
    }

    @Test
    fun diagnosticsAreBoundedAndContainNoPayloadOrExceptionText() {
        val backing = DiagnosticsMemoryStore()
        val diagnostics = PersistenceDiagnostics(backing)
        repeat(25) { index ->
            diagnostics.record("goals.write", PersistFailureKind.STORAGE_IO, index.toLong())
        }
        diagnostics.record("https://private.example/path token=secret objective: J'étudie", PersistFailureKind.UNKNOWN, 26L)

        assertEquals(PersistenceDiagnostics.MAX_EVENTS, diagnostics.recent().size)
        assertEquals(6L, diagnostics.recent().first().occurredAtUtcMillis)
        assertEquals("unknown", diagnostics.recent().last().operation)
        assertFalse(backing.value.orEmpty().contains("private.example"))
        assertFalse(backing.value.orEmpty().contains("secret"))
        assertFalse(backing.value.orEmpty().contains("J'étudie"))
        assertFalse(backing.value.orEmpty().contains("private serialization detail"))
        assertFalse(backing.value.orEmpty().contains("stackTrace"))

        val restored = PersistenceDiagnostics(backing)
        assertEquals(diagnostics.recent(), restored.recent())
    }

    private class TestBackend : StateBackend {
        var value: String? = null
        var failWrites = false
        override fun read(): String? = value
        override fun write(value: String) {
            if (failWrites) throw IllegalStateException("private storage detail")
            this.value = value
        }
    }

    private class DiagnosticsMemoryStore : PersistenceDiagnosticsStore {
        var value: String? = null
        override fun read(): String? = value
        override fun write(value: String): Boolean {
            this.value = value
            return true
        }
    }
}
