package com.nova.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class CacheCleanerTest {
    @Test fun formatSizes() {
        assertEquals("512 B", CacheCleaner.format(512))
        assertEquals("2 KB", CacheCleaner.format(2048))
        assertEquals("1.5 MB", CacheCleaner.format(1572864))
        assertEquals("2.00 GB", CacheCleaner.format(2L * 1024 * 1024 * 1024))
    }
}
