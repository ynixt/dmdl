package com.ynixt.dmdl.resource.gateway.files

import com.ynixt.dmdl.domain.gateway.DiskActivitySource
import com.ynixt.dmdl.domain.model.DiskActivity
import com.ynixt.dmdl.domain.model.DiskActivityAvailability
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Reads cgroup v2 `io.stat` / `memory.stat` of this container. cgroup v2 attributes buffered writeback to the
 * cgroup that dirtied the pages, and `io.stat` is keyed by whole-disk (or device-mapper) `major:minor`, so the
 * staging directory's device is resolved through sysfs: partitions to their disk, device-mapper volumes to themselves
 * or, when absent from `io.stat`, to their slaves. Devices unknown to sysfs (NFS, SMB, ZFS, overlay) have no block stats.
 */
@Component
class CgroupDiskActivityProbe(
    private val sysRoot: Path = Path.of("/sys"),
    private val cgroupRoot: Path = Path.of("/sys/fs/cgroup"),
    private val deviceOf: (Path) -> String? = ::linuxDevice,
    private val clock: Clock = Clock.systemUTC(),
) : DiskActivitySource {
    private data class Target(
        val preferred: Set<String>,
        val fallback: Set<String>,
    )

    private val targets = ConcurrentHashMap<Path, Target?>()

    @Volatile private var cached: Pair<Long, Map<Path, DiskActivity>> = 0L to emptyMap()

    override fun read(stagingRoot: Path): DiskActivity {
        val key = stagingRoot.toAbsolutePath().normalize()
        val now = clock.millis()
        cached.let { (at, values) -> if (now - at < CACHE_MILLIS) values[key]?.let { return it } }
        val result = runCatching { measure(key) }.getOrElse { DiskActivity(DiskActivityAvailability.NO_BLOCK_STATS) }
        synchronized(this) {
            val values = if (now - cached.first < CACHE_MILLIS) cached.second else emptyMap()
            cached = now to (values + (key to result))
        }
        return result
    }

    private fun measure(stagingRoot: Path): DiskActivity {
        val ioStat = cgroupRoot.resolve("io.stat")
        val memoryStat = cgroupRoot.resolve("memory.stat")
        if (!Files.exists(cgroupRoot.resolve("cgroup.controllers")) || !Files.exists(ioStat) || !Files.exists(memoryStat)) {
            return DiskActivity(DiskActivityAvailability.CGROUP_V2_MISSING)
        }
        val target =
            targets.computeIfAbsent(stagingRoot) { path -> deviceOf(path)?.let(::resolve) }
                ?: return DiskActivity(DiskActivityAvailability.NO_BLOCK_STATS)
        val written = writtenBytes(ioStat.readText())
        val devices = target.preferred.takeIf { written.keys.containsAll(it) } ?: target.fallback
        val memory = keyValues(memoryStat.readText())
        return DiskActivity(
            DiskActivityAvailability.AVAILABLE,
            devices.sumOf { written[it] ?: 0L },
            (memory["file_dirty"] ?: 0L) + (memory["file_writeback"] ?: 0L),
        )
    }

    /** Maps a device number to the `io.stat` keys that account for it, or null when sysfs does not know it. */
    private fun resolve(device: String): Target? {
        val block = sysRoot.resolve("block")
        if (!block.isDirectory()) return null
        val disks = mutableMapOf<String, Path>()
        val partitionOf = mutableMapOf<String, String>()
        val nameToDevice = mutableMapOf<String, String>()
        block.listDirectoryEntries().forEach { disk ->
            val diskDevice = devNumber(disk) ?: return@forEach
            disks[diskDevice] = disk
            nameToDevice[disk.name] = diskDevice
            disk.listDirectoryEntries().filter { Files.exists(it.resolve("partition")) }.forEach { partition ->
                devNumber(partition)?.let {
                    partitionOf[it] = diskDevice
                    nameToDevice[partition.name] = it
                }
            }
        }

        fun underlying(
            dev: String,
            depth: Int = 0,
        ): Set<String> {
            val whole = partitionOf[dev] ?: dev
            val slaves =
                disks[whole]
                    ?.resolve("slaves")
                    ?.takeIf { it.isDirectory() }
                    ?.listDirectoryEntries()
                    .orEmpty()
            if (slaves.isEmpty() || depth > 8) return setOf(whole)
            return slaves.mapNotNull { nameToDevice[it.name] }.flatMap { underlying(it, depth + 1) }.toSet()
        }

        val whole = partitionOf[device] ?: device.takeIf { it in disks } ?: return null
        return Target(setOf(whole), underlying(whole))
    }

    private fun devNumber(dir: Path): String? =
        dir
            .resolve("dev")
            .takeIf(Files::exists)
            ?.readText()
            ?.trim()

    private fun writtenBytes(ioStat: String): Map<String, Long> =
        ioStat.lines().filter(String::isNotBlank).associate { line ->
            val fields = line.trim().split(Regex("\\s+"))
            fields.first() to (fields.firstOrNull { it.startsWith("wbytes=") }?.substringAfter('=')?.toLongOrNull() ?: 0L)
        }

    private fun keyValues(text: String): Map<String, Long> =
        text
            .lines()
            .mapNotNull { line ->
                val parts = line.trim().split(' ')
                parts.getOrNull(1)?.toLongOrNull()?.let { parts[0] to it }
            }.toMap()

    companion object {
        private const val CACHE_MILLIS = 1000L

        /** `major:minor` of the device holding [path], using glibc's `st_dev` encoding. */
        fun linuxDevice(path: Path): String? =
            runCatching {
                val dev = (Files.getAttribute(path, "unix:dev") as Number).toLong()
                val major = ((dev ushr 8) and 0xfff) or ((dev ushr 32) and 0xfffff000L)
                val minor = (dev and 0xff) or ((dev ushr 12) and 0xffffff00L)
                "$major:$minor"
            }.getOrNull()
    }
}
