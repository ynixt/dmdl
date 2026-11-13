package com.ynixt.dmdl.resource.gateway.aria2

import com.ynixt.dmdl.application.config.Aria2Properties
import com.ynixt.dmdl.application.config.StorageProperties
import com.ynixt.dmdl.domain.gateway.AllocationProgressSource
import com.ynixt.dmdl.domain.model.AllocationProgress
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import kotlin.math.ceil

/** aria2 reports allocation counters in its console readout, outside JSON-RPC. */
@Component
class Aria2AllocationProgress(
    storage: StorageProperties,
    properties: Aria2Properties,
    private val json: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
) : AllocationProgressSource {
    private val statusFile =
        Path
            .of(
                properties.applicationStagingRoot.takeIf(String::isNotBlank)
                    ?: Path.of(storage.root).resolve(".dmdl").toString(),
            ).resolve("aria2-allocation.json")

    private data class Sample(
        val at: Long,
        val bytes: Long,
    )

    private class AllocationHistory(
        val gid: String,
        val total: Long,
    ) {
        val samples = ArrayDeque<Sample>()
        var lastProgressAt: Long? = null
    }

    private var history: AllocationHistory? = null

    @Synchronized
    override fun find(gid: String): AllocationProgress? =
        runCatching {
            if (!Files.isRegularFile(statusFile) || Files.size(statusFile) > 4096) {
                history = null
                return@runCatching null
            }
            val now = clock.millis()
            val at = Files.getLastModifiedTime(statusFile).toMillis()
            if (now - at !in -MAX_FUTURE_SKEW_MILLIS..MAX_SAMPLE_AGE_MILLIS) {
                history = null
                return@runCatching null
            }
            val node = json.readTree(Files.readAllBytes(statusFile))
            val prefix = node["gidPrefix"]?.asString().orEmpty()
            if (!prefix.matches(Regex("[0-9a-f]{6,16}"))) {
                history = null
                return@runCatching null
            }
            if (!gid.startsWith(prefix)) return@runCatching null
            val bytes = node["allocatedBytes"]?.asLong() ?: return@runCatching null
            val total = node["totalBytes"]?.asLong() ?: return@runCatching null
            if (total <= 0 || bytes !in 0..total) {
                history = null
                return@runCatching null
            }
            var current = history
            val last = current?.samples?.lastOrNull()
            if (current == null || current.gid != gid || current.total != total ||
                (last != null && (at < last.at || at - last.at > MAX_SAMPLE_AGE_MILLIS || bytes < last.bytes))
            ) {
                current = AllocationHistory(gid, total)
                history = current
            }
            val previous = current.samples.lastOrNull()
            if (previous != null && bytes > previous.bytes) current.lastProgressAt = at
            if (previous?.at == at) current.samples.removeLast()
            current.samples.addLast(Sample(at, bytes))
            // Keep a baseline at the edge of the recent window, including intervals with zero progress.
            while (current.samples.size > 1 && current.samples[1].at <= at - ESTIMATION_WINDOW_MILLIS) {
                current.samples.removeFirst()
            }
            val first = current.samples.first()
            val elapsed = at - first.at
            val progressed = bytes - first.bytes
            val recentlyProgressed = current.lastProgressAt?.let { now - it <= MAX_SAMPLE_AGE_MILLIS } == true
            val remaining =
                when {
                    bytes == total -> {
                        0L
                    }

                    current.samples.size < MINIMUM_SAMPLE_COUNT || elapsed < MINIMUM_ESTIMATION_SPAN_MILLIS || progressed <= 0 ||
                        !recentlyProgressed -> {
                        null
                    }

                    else -> {
                        val bytesPerSecond = progressed * 1000.0 / elapsed
                        ceil((total - bytes) / bytesPerSecond).toLong()
                    }
                }
            AllocationProgress(bytes, total, remaining)
        }.getOrNull()

    private companion object {
        const val ESTIMATION_WINDOW_MILLIS = 30000L
        const val MINIMUM_ESTIMATION_SPAN_MILLIS = 5000L
        const val MAX_SAMPLE_AGE_MILLIS = 5000L
        const val MAX_FUTURE_SKEW_MILLIS = 1000L
        const val MINIMUM_SAMPLE_COUNT = 4
    }
}
