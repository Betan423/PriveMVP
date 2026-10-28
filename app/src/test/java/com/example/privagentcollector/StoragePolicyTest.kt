package com.example.privagentcollector

import org.junit.Assert.*
import org.junit.Test

class StoragePolicyTest {
    @Test fun retentionBoundaryIsSevenDays() {
        val now = 2 * StoragePolicy.RETAIN_MS
        assertFalse(StoragePolicy.expired(now - StoragePolicy.RETAIN_MS + 1, now))
        assertTrue(StoragePolicy.expired(now - StoragePolicy.RETAIN_MS, now))
        assertTrue(StoragePolicy.expired(0, now))
    }
    @Test fun onlyRecordsExceedingFileLimitRotate() {
        assertFalse(StoragePolicy.rotate(StoragePolicy.ROTATE_BYTES - 100, 100))
        assertTrue(StoragePolicy.rotate(StoragePolicy.ROTATE_BYTES - 100, 101))
        assertFalse(StoragePolicy.rotate(0, 100))
    }
    @Test fun totalBudgetHasRoomForMultipleRotatedFiles() {
        assertEquals(5, StoragePolicy.MAX_TOTAL_BYTES / StoragePolicy.ROTATE_BYTES)
    }
    @Test fun sensitiveSourcesAreInitiallyOptIn() {
        for (s in listOf(DataSource.CONTACTS, DataSource.LOCATION, DataSource.NOTIFICATIONS, DataSource.ACCESSIBILITY,
            DataSource.CALENDAR, DataSource.IMAGES, DataSource.VIDEOS, DataSource.AUDIO, DataSource.USAGE)) {
            assertFalse(s.name, s.basic)
        }
    }
}
