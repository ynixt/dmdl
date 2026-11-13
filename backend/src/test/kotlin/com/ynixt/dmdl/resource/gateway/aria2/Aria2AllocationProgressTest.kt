package com.ynixt.dmdl.resource.gateway.aria2

import com.ynixt.dmdl.application.config.Aria2Properties
import com.ynixt.dmdl.application.config.StorageProperties
import com.ynixt.dmdl.domain.model.AllocationProgress
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Clock
import kotlin.test.assertEquals
import kotlin.test.assertNull

class Aria2AllocationProgressTest {
    @TempDir lateinit var root: Path
    private val clock = mockk<Clock>()
    private val epoch = 1700000000000L
    private var now = epoch
    private val gid = "abcdef1234567890"
    private lateinit var source: Aria2AllocationProgress
    private lateinit var path: Path

    @BeforeEach
    fun setup() {
        every { clock.millis() } answers { now }
        source = Aria2AllocationProgress(StorageProperties(root.toString()), Aria2Properties(), ObjectMapper(), clock)
        path = Files.createDirectories(root.resolve(".dmdl")).resolve("aria2-allocation.json")
    }

    private fun sample(
        second: Int,
        bytes: Long,
        total: Long = 1000,
        targetGid: String = gid,
    ): AllocationProgress? {
        now = epoch + second * 1000L
        Files.writeString(path, """{"gidPrefix":"${targetGid.take(6)}","allocatedBytes":$bytes,"totalBytes":$total}""")
        Files.setLastModifiedTime(path, FileTime.fromMillis(now))
        return source.find(targetGid)
    }

    @Test
    fun `ETA waits for distinct samples and elapsed time instead of counting repeated polls`() {
        assertNull(sample(0, 0)!!.secondsRemaining)
        repeat(20) { assertNull(source.find(gid)!!.secondsRemaining) }
        assertNull(sample(5, 500)!!.secondsRemaining)
        assertNull(sample(6, 600)!!.secondsRemaining)
        assertEquals(3, sample(7, 700)!!.secondsRemaining)
        repeat(20) { assertEquals(3, source.find(gid)!!.secondsRemaining) }
        assertNull(source.find("1111111234567890"))
        assertEquals(3, source.find(gid)!!.secondsRemaining)
    }

    @Test
    fun `rolling throughput smooths bursts includes short pauses and suppresses stalled estimates`() {
        sample(0, 0, 10000)
        sample(1, 1000, 10000)
        sample(2, 1000, 10000)
        sample(3, 1000, 10000)
        sample(4, 1000, 10000)
        assertEquals(5, sample(5, 5000, 10000)!!.secondsRemaining)
        assertEquals(6, sample(6, 5000, 10000)!!.secondsRemaining)
        assertEquals(3, sample(7, 7000, 10000)!!.secondsRemaining)
        assertEquals(4, sample(8, 7000, 10000)!!.secondsRemaining)
        assertNull(sample(13, 7000, 10000)!!.secondsRemaining)
    }

    @Test
    fun `recent window adapts to sustained disk slowdown`() {
        for (second in 0..30) sample(second, second * 1000L, 100000)
        assertEquals(70, source.find(gid)!!.secondsRemaining)
        for (second in 31..60) sample(second, 30000 + (second - 30) * 100L, 100000)
        assertEquals(670, source.find(gid)!!.secondsRemaining)
    }

    @Test
    fun `counter reset size change and different GID start a fresh estimate`() {
        for (second in 0..5) sample(second, second * 100L)
        assertEquals(5, source.find(gid)!!.secondsRemaining)
        assertNull(sample(6, 10)!!.secondsRemaining)
        for (second in 7..11) sample(second, 10 + (second - 6) * 10L)
        assertEquals(94, source.find(gid)!!.secondsRemaining)
        assertNull(sample(12, 70, 2000)!!.secondsRemaining)
        assertNull(sample(13, 80, 2000, "123456abcdef1234")!!.secondsRemaining)
        assertEquals(0, sample(14, 2000, 2000, "123456abcdef1234")!!.secondsRemaining)
    }

    @Test
    fun `resuming after a sampling gap estimates only fresh observed allocation time`() {
        for (second in 0..5) sample(second, second * 100L)
        assertEquals(5, source.find(gid)!!.secondsRemaining)
        assertNull(sample(15, 600)!!.secondsRemaining)
        assertNull(sample(20, 650)!!.secondsRemaining)
        assertNull(sample(21, 660)!!.secondsRemaining)
        assertEquals(33, sample(22, 670)!!.secondsRemaining)
    }

    @Test
    fun `stale missing and malformed telemetry is rejected and small timestamp skew is tolerated`() {
        assertNull(source.find(gid))
        for (second in 0..5) sample(second, second * 100L)
        now -= 500
        assertEquals(5, source.find(gid)!!.secondsRemaining)
        now += 6500
        assertNull(source.find(gid))
        assertNull(sample(12, 600)!!.secondsRemaining)
        Files.writeString(path, "partial-json")
        Files.setLastModifiedTime(path, FileTime.fromMillis(now))
        assertNull(source.find(gid))
        Files.writeString(path, "{}")
        Files.setLastModifiedTime(path, FileTime.fromMillis(now))
        assertNull(source.find(gid))
    }
}
