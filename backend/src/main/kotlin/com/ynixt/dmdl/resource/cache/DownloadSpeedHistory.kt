package com.ynixt.dmdl.resource.cache

import com.ynixt.dmdl.domain.model.SpeedSample
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

/**
 * Bounded per-download speed history. Keyed by request id (not generation) so retries continue the same chart.
 * Losing chart data must never fail a download, so Redis errors are swallowed.
 */
@Component
class DownloadSpeedHistory(
    private val redis: StringRedisTemplate,
    private val json: ObjectMapper,
) {
    fun append(
        id: UUID,
        sample: SpeedSample,
    ) {
        runCatching {
            val entry =
                json.writeValueAsString(
                    mapOf(
                        "at" to sample.at.toEpochMilli(),
                        "speed" to sample.speedBytesPerSecond,
                        "connections" to sample.connections,
                    ),
                )
            val list = redis.opsForList()
            list.rightPush(key(id), entry)
            list.trim(key(id), -MAX_SAMPLES, -1)
        }
    }

    /** Samples oldest first; empty when none were recorded or Redis is unavailable. */
    fun find(id: UUID): List<SpeedSample> =
        runCatching {
            redis.opsForList().range(key(id), 0, -1).orEmpty().mapNotNull { entry ->
                runCatching {
                    val node = json.readTree(entry)
                    SpeedSample(
                        Instant.ofEpochMilli(node.get("at").asLong()),
                        node.get("speed").asLong(),
                        node.get("connections").asInt(),
                    )
                }.getOrNull()
            }
        }.getOrDefault(emptyList())

    fun delete(id: UUID) {
        runCatching { redis.delete(key(id)) }
    }

    private fun key(id: UUID) = "dmdl:speed-history:$id"

    companion object {
        /** 24 hours at one sample every 30 seconds. */
        const val MAX_SAMPLES = 2880L
    }
}
