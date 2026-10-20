package io.shayne.fogvisitor

import io.shayne.fogvisitor.storage.ArchiveSyncEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveSyncStateTest {
    private val state = ArchiveSyncEntity(epoch = "database-a", revision = 12, resetRevision = 7)

    @Test fun acceptsCurrentAndCatchupRevisions() {
        assertFalse(state.requiresReset("database-a", 7))
        assertFalse(state.requiresReset("database-a", 10))
        assertFalse(state.requiresReset("database-a", 12))
    }

    @Test fun resetsAfterReplacementOrDatabaseChange() {
        assertTrue(state.requiresReset("database-a", 6))
        assertTrue(state.requiresReset("database-b", 12))
        assertTrue(state.requiresReset("", 0))
    }

    @Test fun rejectsFutureAndNegativeRevisions() {
        assertTrue(state.requiresReset("database-a", 13))
        assertTrue(state.requiresReset("database-a", -1))
        assertTrue(state.requiresReset("database-a", Long.MAX_VALUE))
    }
}
