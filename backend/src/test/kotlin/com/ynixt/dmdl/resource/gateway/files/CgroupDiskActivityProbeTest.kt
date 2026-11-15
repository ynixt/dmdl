package com.ynixt.dmdl.resource.gateway.files

import com.ynixt.dmdl.domain.model.DiskActivityAvailability
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CgroupDiskActivityProbeTest {
    @TempDir lateinit var root: Path

    private val sys get() = root.resolve("sys")
    private val cgroup get() = root.resolve("cgroup")
    private val staging = Path.of("/media/.dmdl")

    private fun file(
        path: Path,
        text: String,
    ) {
        path.parent.createDirectories()
        path.writeText(text)
    }

    private fun disk(
        name: String,
        dev: String,
        vararg partitions: Pair<String, String>,
        slaves: List<String> = emptyList(),
    ) {
        file(sys.resolve("block/$name/dev"), "$dev\n")
        partitions.forEach { (partition, partitionDev) ->
            file(sys.resolve("block/$name/$partition/dev"), "$partitionDev\n")
            file(sys.resolve("block/$name/$partition/partition"), "1\n")
        }
        slaves.forEach { sys.resolve("block/$name/slaves/$it").createDirectories() }
    }

    private fun cgroupV2(ioStat: String) {
        file(cgroup.resolve("cgroup.controllers"), "cpu io memory pids\n")
        file(cgroup.resolve("io.stat"), ioStat)
        file(cgroup.resolve("memory.stat"), "anon 1000\nfile_dirty 300000000\nfile_writeback 100000000\nfile 5\n")
    }

    private var now = Instant.parse("2026-11-14T22:00:00Z")

    private fun probe(device: String?) =
        CgroupDiskActivityProbe(
            sys,
            cgroup,
            { device },
            object : Clock() {
                override fun instant() = now

                override fun getZone() = ZoneOffset.UTC

                override fun withZone(zone: java.time.ZoneId?) = this
            },
        )

    @Test
    fun `whole disk reports container writes and pending bytes`() {
        disk("sde", "8:64")
        cgroupV2("8:64 rbytes=20480 wbytes=366604288 rios=2 wios=431 dbytes=0 dios=0\n8:0 rbytes=1 wbytes=999\n")
        val activity = probe("8:64").read(staging)
        assertEquals(DiskActivityAvailability.AVAILABLE, activity.availability)
        assertEquals(366604288, activity.writtenBytes)
        assertEquals(400000000, activity.pendingWriteBytes)
    }

    @Test
    fun `partition resolves to its parent disk`() {
        disk("sdb", "8:16", "sdb1" to "8:17")
        cgroupV2("8:16 rbytes=0 wbytes=5000 rios=0 wios=1\n")
        assertEquals(5000, probe("8:17").read(staging).writtenBytes)
    }

    @Test
    fun `device mapper uses itself when accounted and its slaves otherwise`() {
        disk("sda", "8:0", "sda2" to "8:2")
        disk("sdc", "8:32")
        disk("dm-0", "253:0", slaves = listOf("sda2", "sdc"))
        cgroupV2("253:0 wbytes=7000\n8:0 wbytes=100\n8:32 wbytes=200\n")
        assertEquals(7000, probe("253:0").read(staging).writtenBytes)

        cgroupV2("8:0 wbytes=100\n8:32 wbytes=200\n")
        assertEquals(300, probe("253:0").read(staging).writtenBytes)
    }

    @Test
    fun `disk not yet written reports zero`() {
        disk("sde", "8:64")
        cgroupV2("")
        val activity = probe("8:64").read(staging)
        assertEquals(DiskActivityAvailability.AVAILABLE, activity.availability)
        assertEquals(0, activity.writtenBytes)
    }

    @Test
    fun `device unknown to sysfs has no block statistics`() {
        disk("sde", "8:64")
        cgroupV2("8:64 wbytes=1\n")
        val activity = probe("0:52").read(staging)
        assertEquals(DiskActivityAvailability.NO_BLOCK_STATS, activity.availability)
        assertNull(activity.writtenBytes)
        assertEquals(DiskActivityAvailability.NO_BLOCK_STATS, probe(null).read(staging).availability)
    }

    @Test
    fun `missing cgroup v2 files report cgroup v2 missing`() {
        disk("sde", "8:64")
        assertEquals(DiskActivityAvailability.CGROUP_V2_MISSING, probe("8:64").read(staging).availability)
        file(cgroup.resolve("io.stat"), "8:64 wbytes=1\n")
        file(cgroup.resolve("memory.stat"), "file_dirty 0\n")
        // cgroup v1 hierarchies have no cgroup.controllers file.
        assertEquals(DiskActivityAvailability.CGROUP_V2_MISSING, probe("8:64").read(staging).availability)
    }

    @Test
    fun `reads are cached for one second`() {
        disk("sde", "8:64")
        cgroupV2("8:64 wbytes=1000\n")
        val probe = probe("8:64")
        assertEquals(1000, probe.read(staging).writtenBytes)
        Files.writeString(cgroup.resolve("io.stat"), "8:64 wbytes=2000\n")
        assertEquals(1000, probe.read(staging).writtenBytes)
        now = now.plusMillis(1001)
        assertEquals(2000, probe.read(staging).writtenBytes)
    }
}
