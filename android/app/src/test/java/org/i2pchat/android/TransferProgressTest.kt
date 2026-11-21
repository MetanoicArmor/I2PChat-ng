package org.i2pchat.android

import org.junit.Assert.assertEquals
import org.junit.Test

class TransferProgressTest {
    @Test
    fun percent_halfway() {
        val t = TransferProgressUi(
            peer = "peer",
            name = "a.bin",
            size = 200,
            transferred = 100,
            incoming = true,
        )
        assertEquals(50, t.percent)
    }

    @Test
    fun percent_completedOutcomeIs100() {
        val t = TransferProgressUi(
            peer = "peer",
            name = "a.bin",
            size = 200,
            transferred = 50,
            incoming = false,
            outcome = "completed",
        )
        assertEquals(100, t.percent)
    }

    @Test
    fun percent_zeroSize() {
        val t = TransferProgressUi(
            peer = "peer",
            name = "a.bin",
            size = 0,
            transferred = 0,
            incoming = true,
        )
        assertEquals(0, t.percent)
    }
}
