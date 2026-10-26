package cool.jacoblin.particeps.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageRecoveryProgressTest {
    @Test
    fun unfinishedLargeFilesNeverRoundUpToComplete() {
        assertEquals(0, StorageRecoveryProgress(0, Long.MAX_VALUE).percent)
        assertEquals(37, StorageRecoveryProgress(37, 100).percent)
        assertEquals(99, StorageRecoveryProgress(Long.MAX_VALUE - 1, Long.MAX_VALUE).percent)
        assertEquals(100, StorageRecoveryProgress(Long.MAX_VALUE, Long.MAX_VALUE).percent)
        assertEquals(100, StorageRecoveryProgress(0, 0).percent)
    }
}
