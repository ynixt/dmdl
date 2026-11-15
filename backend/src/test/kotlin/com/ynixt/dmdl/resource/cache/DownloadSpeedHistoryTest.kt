package com.ynixt.dmdl.resource.cache

import com.ynixt.dmdl.domain.model.SpeedSample
import io.mockk.every
import io.mockk.mockk
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.core.ListOperations
import org.springframework.data.redis.core.StringRedisTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownloadSpeedHistoryTest {
    /** In-memory stand-in for the Redis list commands the history uses. */
    private val lists = mutableMapOf<String, MutableList<String>>()
    private val redis = mockk<StringRedisTemplate>()
    private val history = DownloadSpeedHistory(redis, JsonMapper.builder().build())

    init {
        val ops = mockk<ListOperations<String, String>>()
        every { redis.opsForList() } returns ops
        every { ops.rightPush(any(), any()) } answers {
            val list = lists.getOrPut(firstArg()) { mutableListOf() }
            list += secondArg<String>()
            list.size.toLong()
        }
        every { ops.trim(any(), any(), any()) } answers {
            val list = lists[firstArg()] ?: return@answers Unit
            val start = secondArg<Long>()
            val end = thirdArg<Long>()
            val from = (if (start < 0) list.size + start else start).coerceAtLeast(0).toInt()
            val to = (if (end < 0) list.size + end else end).coerceAtMost(list.size - 1L).toInt()
            val kept = if (from > to) emptyList() else list.subList(from, to + 1).toList()
            list.clear()
            list += kept
            Unit
        }
        every { ops.range(any(), any(), any()) } answers { lists[firstArg()]?.toList() ?: emptyList() }
        every { redis.delete(any<String>()) } answers { lists.remove(firstArg()) != null }
    }

    @Test
    fun `returns samples oldest first under the request key`() {
        val id = UUID.randomUUID()
        val start = Instant.parse("2026-11-14T14:41:00Z")
        (0 until 3).forEach { history.append(id, SpeedSample(start.plusSeconds(30L * it), 1000L * (it + 1), 8 - it)) }
        assertEquals(setOf("dmdl:speed-history:$id"), lists.keys)
        assertEquals(
            listOf(
                SpeedSample(start, 1000, 8),
                SpeedSample(start.plusSeconds(30), 2000, 7),
                SpeedSample(start.plusSeconds(60), 3000, 6),
            ),
            history.find(id),
        )
        assertEquals(emptyList(), history.find(UUID.randomUUID()))
        history.delete(id)
        assertEquals(emptyList(), history.find(id))
    }

    @Test
    fun `cumulative counters round-trip and legacy entries without them stay readable`() {
        val id = UUID.randomUUID()
        val at = Instant.parse("2026-11-14T22:48:16Z")
        // Written by the previous version: no cumulative counters.
        lists["dmdl:speed-history:$id"] = mutableListOf("""{"at":${at.toEpochMilli()},"speed":37907126,"connections":8}""")
        history.append(id, SpeedSample(at.plusSeconds(30), 0, 8, 49_966_448_640, 1_927_000_000))
        history.append(id, SpeedSample(at.plusSeconds(60), 1000, 8, 49_966_478_640))
        assertEquals(
            listOf(
                SpeedSample(at, 37907126, 8),
                SpeedSample(at.plusSeconds(30), 0, 8, 49_966_448_640, 1_927_000_000),
                SpeedSample(at.plusSeconds(60), 1000, 8, 49_966_478_640, null),
            ),
            history.find(id),
        )
    }

    @Test
    fun `keeps only the newest 2880 samples`() {
        val id = UUID.randomUUID()
        val start = Instant.parse("2026-11-14T00:00:00Z")
        repeat(2885) { history.append(id, SpeedSample(start.plusSeconds(30L * it), it.toLong(), 1)) }
        val samples = history.find(id)
        assertEquals(2880, samples.size)
        assertEquals(5L, samples.first().speedBytesPerSecond)
        assertEquals(2884L, samples.last().speedBytesPerSecond)
    }

    @Test
    fun `redis failures are swallowed`() {
        val failing = mockk<StringRedisTemplate>()
        every { failing.opsForList() } throws RedisConnectionFailureException("fixture outage")
        every { failing.delete(any<String>()) } throws RedisConnectionFailureException("fixture outage")
        val unavailable = DownloadSpeedHistory(failing, JsonMapper.builder().build())
        val id = UUID.randomUUID()
        unavailable.append(id, SpeedSample(Instant.now(), 1, 1))
        assertTrue(unavailable.find(id).isEmpty())
        unavailable.delete(id)
    }
}
