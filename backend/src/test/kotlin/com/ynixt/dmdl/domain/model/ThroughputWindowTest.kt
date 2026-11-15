package com.ynixt.dmdl.domain.model

import com.ynixt.dmdl.domain.model.ThroughputWindow.Companion.AVERAGE_WINDOW
import com.ynixt.dmdl.domain.model.ThroughputWindow.Companion.SPEED_WINDOW
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ThroughputWindowTest {
    private val mb = 1_000_000L

    private fun steady(
        window: ThroughputWindow,
        fromSecond: Int,
        toSecond: Int,
        bytesPerSecond: Long,
        startBytes: Long = 0,
    ): Long {
        var bytes = startBytes
        for (second in fromSecond..toSecond) {
            window.record(second * 1000L, bytes)
            bytes += bytesPerSecond
        }
        return bytes - bytesPerSecond
    }

    @Test
    fun `steady flow reports the flow rate`() {
        val window = ThroughputWindow()
        steady(window, 0, 30, 35 * mb)
        assertEquals(35 * mb, window.rate(30_000, SPEED_WINDOW))
        assertEquals(35 * mb, window.rate(30_000, AVERAGE_WINDOW))
    }

    @Test
    fun `a freeze decays the speed to zero without new samples`() {
        val window = ThroughputWindow()
        steady(window, 0, 30, 38 * mb)
        assertEquals(19 * mb, window.rate(35_000, SPEED_WINDOW))
        assertEquals(0, window.rate(41_000, SPEED_WINDOW))
    }

    @Test
    fun `a catch-up burst is averaged over the window instead of spiking`() {
        val window = ThroughputWindow()
        val before = steady(window, 0, 30, 30 * mb)
        // 30 s freeze, then aria2 dumps 100 MB at once.
        window.record(60_000, before + 100 * mb)
        assertEquals(100 * mb / 30, window.rate(60_000, SPEED_WINDOW))
    }

    @Test
    fun `warm-up returns null until two seconds of history exist`() {
        val window = ThroughputWindow()
        assertNull(window.rate(0, SPEED_WINDOW))
        window.record(0, 0)
        window.record(1_000, 10 * mb)
        assertNull(window.rate(1_500, SPEED_WINDOW))
        window.record(2_000, 20 * mb)
        assertEquals(10 * mb, window.rate(2_000, SPEED_WINDOW))
    }

    @Test
    fun `average uses the time since the run started when shorter than its window`() {
        val window = ThroughputWindow()
        steady(window, 0, 20, 5 * mb)
        assertEquals(5 * mb, window.rate(20_000, AVERAGE_WINDOW))
    }

    @Test
    fun `bytes going backwards restart the window and never report a negative speed`() {
        val window = ThroughputWindow()
        steady(window, 0, 30, 10 * mb, startBytes = 41_200 * mb)
        window.record(31_000, 40_900 * mb)
        assertNull(window.rate(31_000, SPEED_WINDOW))
        // The sample after a regression becomes the new baseline.
        window.record(32_000, 40_930 * mb)
        window.record(34_000, 40_990 * mb)
        assertEquals(30 * mb, window.rate(34_000, SPEED_WINDOW))
    }

    @Test
    fun `a restarted aria2 reporting zero before its control file does not create a spike`() {
        val window = ThroughputWindow()
        steady(window, 0, 30, 36 * mb, startBytes = 14_900 * mb)
        // aria2 reported 0 for 14 seconds while it reconnected through the addon redirect.
        (33..47).forEach { window.record(it * 1000L, 0) }
        assertNull(window.rate(47_000, SPEED_WINDOW))
        window.record(36_000 + 12_000, 14_910 * mb)
        assertNull(window.rate(48_000, SPEED_WINDOW))
        window.record(49_000, 14_940 * mb)
        window.record(50_000, 14_970 * mb)
        assertEquals(30 * mb, window.rate(50_000, SPEED_WINDOW))
    }

    @Test
    fun `retention keeps a baseline for the longest window`() {
        val window = ThroughputWindow()
        steady(window, 0, 300, 2 * mb)
        assertEquals(2 * mb, window.rate(300_000, AVERAGE_WINDOW))
    }
}
