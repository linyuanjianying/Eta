package io.github.mangi.eta.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatImageSizingTest {
    @Test fun largeOriginalsHaveBoundedPreviewDecodes() {
        for ((width, height) in listOf(12000 to 9000, 9000 to 12000, 50000 to 1, 1 to 50000)) {
            for (limit in listOf(400_000L, 2_000_000L, 8_000_000L)) {
                val sample = chatImageSampleSize(width, height, limit)
                assertEquals(0, sample and (sample - 1))
                assertTrue(((width.toLong() + sample - 1) / sample) * ((height.toLong() + sample - 1) / sample) <= limit)
            }
        }
        assertEquals(1, chatImageSampleSize(100, 100, 400_000))
        assertEquals(1, chatImageSampleSize(-1, -1, 400_000))
    }
}
