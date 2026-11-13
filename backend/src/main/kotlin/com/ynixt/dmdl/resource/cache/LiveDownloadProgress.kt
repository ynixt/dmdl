package com.ynixt.dmdl.resource.cache

import com.ynixt.dmdl.domain.model.DownloadProgress
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.UUID

@Component
class LiveDownloadProgress(
    private val redis: StringRedisTemplate,
    private val json: ObjectMapper,
) {
    fun publish(
        id: UUID,
        generation: Long,
        progress: DownloadProgress,
    ) {
        runCatching {
            redis.opsForValue().set(
                "dmdl:progress:$id:$generation",
                json.writeValueAsString(progress),
                Duration.ofMinutes(5),
            )
        }
    }

    fun find(
        id: UUID,
        generation: Long,
    ): DownloadProgress? =
        runCatching {
            redis.opsForValue().get("dmdl:progress:$id:$generation")?.let { json.readValue(it, DownloadProgress::class.java) }
        }.getOrNull()
}
