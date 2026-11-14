package com.ynixt.dmdl.resource.cache

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID

@Component
class DiscoveryCache(
    private val redis: StringRedisTemplate,
) {
    data class Lookup(
        val value: String? = null,
        val generation: String? = null,
    )

    fun lookup(
        key: String,
        refresh: Boolean = false,
    ): Lookup =
        runCatching {
            val result =
                redis.execute(
                    lookupScript,
                    listOf(key, "$key:generation"),
                    if (refresh) UUID.randomUUID().toString() else "",
                )
            Lookup(result?.get(0)?.toString()?.takeIf(String::isNotEmpty), result?.get(1)?.toString())
        }.getOrDefault(Lookup())

    fun put(
        key: String,
        value: String,
        ttl: Duration,
        generation: String?,
    ) {
        if (generation == null) return
        runCatching {
            redis.execute(storeScript, listOf(key, "$key:generation"), generation, value, ttl.seconds.toString())
        }
    }

    companion object {
        val RESULTS_TTL: Duration = Duration.ofHours(24)
        val METADATA_TTL: Duration = Duration.ofDays(3)

        /** A title does not stop being an anime; a missing Kitsu mapping may be added later. */
        val KITSU_ANIME_TTL: Duration = Duration.ofDays(30)
        val KITSU_SERIES_TTL: Duration = Duration.ofDays(7)

        private val lookupScript =
            DefaultRedisScript(
                """
                if ARGV[1] ~= '' then
                    redis.call('SET', KEYS[2], ARGV[1], 'EX', 259200)
                    redis.call('DEL', KEYS[1])
                end
                return {redis.call('GET', KEYS[1]) or '', redis.call('GET', KEYS[2]) or ''}
                """.trimIndent(),
                List::class.java,
            )

        // An invalidated request must not overwrite the result of a newer refresh.
        private val storeScript =
            DefaultRedisScript(
                """
                if (redis.call('GET', KEYS[2]) or '') == ARGV[1] then
                    redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3])
                    return 1
                end
                return 0
                """.trimIndent(),
                Long::class.java,
            )

        fun key(
            kind: String,
            source: String,
            revision: Long,
            query: String,
        ): String {
            val digest =
                MessageDigest.getInstance("SHA-256").digest(query.toByteArray()).joinToString("") {
                    "%02x".format(
                        it.toInt() and 255,
                    )
                }
            return "dmdl:$kind:$source:$revision:$digest"
        }
    }
}
