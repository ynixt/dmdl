package com.ynixt.dmdl.resource.gateway.files

import com.ynixt.dmdl.domain.model.ContentIdentity
import com.ynixt.dmdl.domain.model.ContentSnapshot
import com.ynixt.dmdl.domain.model.DebridProvider
import com.ynixt.dmdl.domain.model.DownloadExecution
import com.ynixt.dmdl.domain.model.DownloadProgress
import com.ynixt.dmdl.domain.model.DownloadRequest
import com.ynixt.dmdl.domain.model.DownloadState
import com.ynixt.dmdl.domain.model.FinalizationChecks
import com.ynixt.dmdl.domain.model.FinalizationProgress
import com.ynixt.dmdl.domain.model.FinalizationStep
import com.ynixt.dmdl.domain.model.MediaCategory
import com.ynixt.dmdl.domain.model.OrganizationRenderer
import com.ynixt.dmdl.domain.model.OrganizationSettings
import com.ynixt.dmdl.domain.model.OrganizationSnapshot
import com.ynixt.dmdl.domain.model.PlacementState
import com.ynixt.dmdl.domain.model.ReleaseProvenance
import com.ynixt.dmdl.domain.model.ReleaseReference
import com.ynixt.dmdl.domain.model.ReleaseReferenceType
import com.ynixt.dmdl.domain.model.ReleaseSnapshot
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ValidatedFileFinalizerTest {
    @TempDir lateinit var root: Path
    private val finalizer = ValidatedFileFinalizer()

    @Test
    fun `cancel removes only its private partial data and aria2 control files and is idempotent`() {
        val request = request()
        val directory = Path.of(request.execution.stagingPath!!).parent
        Files.writeString(directory.resolve("partial.mkv.aria2"), "aria2 checkpoint")
        val nested = Files.createDirectories(directory.resolve("nested"))
        Files.writeString(nested.resolve("partial.data"), "partial")
        val unrelated = Files.createDirectories(directory.parent.resolve(UUID.randomUUID().toString())).resolve("keep.mkv")
        Files.writeString(unrelated, "unrelated")
        val cancelled = request.copy(state = DownloadState.CANCELLED, execution = request.execution.copy(stagingPath = null))
        finalizer.cleanupCancelled(cancelled)
        finalizer.cleanupCancelled(cancelled)
        assertFalse(Files.exists(directory))
        assertEquals("unrelated", Files.readString(unrelated))
    }

    @Test
    fun `cancel removes verified placement artifacts and preserves an unrelated destination`() {
        val request = request()
        var journal = request
        assertFailsWith<SimulatedCrash> {
            finalizer.finalize(request) {
                journal = it
                throw SimulatedCrash()
            }
        }
        val target = Path.of(journal.execution.finalPath!!)
        Files.writeString(target, "unrelated destination")
        finalizer.cleanupCancelled(journal.copy(state = DownloadState.CANCELLED))
        assertEquals("unrelated destination", Files.readString(target))
        assertFalse(Files.exists(Path.of(request.execution.stagingPath!!).parent))
        Files.delete(target)
        val next = request()
        val placed = finalizer.finalize(next) {}
        finalizer.cleanupCancelled(placed.copy(state = DownloadState.CANCELLED))
        assertFalse(Files.exists(Path.of(placed.execution.finalPath!!)))
        assertFalse(Files.exists(Path.of(next.execution.stagingPath!!).parent))
    }

    @Test
    fun `cancel cleans a recorded destination copy and recovers after its marker was already removed`() {
        val request = request()
        var journal = request
        assertFailsWith<SimulatedCrash> {
            finalizer.finalize(request) {
                journal = it
                throw SimulatedCrash()
            }
        }
        val target = Path.of(journal.execution.finalPath!!)
        val directory = Files.createTempDirectory(target.parent, ".kz-")
        val token = UUID.randomUUID().toString()
        Files.writeString(directory.resolve(".owner"), "${request.id}:$token")
        Files.writeString(directory.resolve("output.part"), "partial copy")
        val cancelled =
            journal.copy(
                state = DownloadState.CANCELLED,
                execution =
                    journal.execution.copy(
                        placementTemporaryPath = directory.resolve("output.part").toString(),
                        placementOwnershipToken = token,
                    ),
            )
        finalizer.cleanupCancelled(cancelled)
        assertFalse(Files.exists(directory))
        Files.createDirectory(directory)
        finalizer.cleanupCancelled(cancelled)
        assertFalse(Files.exists(directory))
    }

    @Test fun `preserves real extension and recovers a crash after placement without a duplicate`() {
        val request = request()
        var journal = request
        assertFailsWith<SimulatedCrash> {
            finalizer.finalize(request) { checkpoint ->
                if (checkpoint.execution.placement == PlacementState.PLACED) throw SimulatedCrash()
                journal = checkpoint
            }
        }
        assertEquals(PlacementState.PLANNED, journal.execution.placement)
        val target = Path.of(assertNotNull(journal.execution.finalPath))
        assertEquals("Original.Release.2160p.H265.HDR.Atmos.MKV", target.fileName.toString())
        val recovered = finalizer.finalize(journal) { journal = it }
        assertEquals(PlacementState.PLACED, recovered.execution.placement)
        assertEquals(1024, Files.size(target))
        assertEquals(1, Files.list(target.parent).use { it.count() })
        val witness = Path.of(recovered.execution.placementTemporaryPath!!)
        assertTrue(Files.isSameFile(target, witness))
        finalizer.cleanupCompleted(recovered.copy(state = DownloadState.COMPLETED, completedAt = Instant.now()))
        assertFalse(Files.exists(witness))
        assertFalse(Files.exists(Path.of(request.execution.stagingPath!!)))
        assertTrue(Files.exists(target))
    }

    @Test fun `rejects unrelated destination and invalid output while retaining staging`() {
        val request = request()
        val target = OrganizationRenderer().render(request.organization, request.content, request.execution.originalFileName!!)
        Files.createDirectories(target.parent)
        Files.writeString(target, "someone else's file")
        assertFailsWith<IllegalArgumentException> { finalizer.finalize(request) {} }
        assertEquals("someone else's file", Files.readString(target))
        assertTrue(Files.exists(Path.of(request.execution.stagingPath!!)))
        Files.delete(target)
        Files.write(Path.of(request.execution.stagingPath!!), ByteArray(1024))
        assertFailsWith<IllegalArgumentException> { finalizer.finalize(request) {} }
        assertFalse(Files.exists(target))
    }

    @Test fun `recovers a crash before placement using the recorded immutable naming snapshot`() {
        val request = request()
        var journal = request
        assertFailsWith<SimulatedCrash> {
            finalizer.finalize(request) {
                journal = it
                throw SimulatedCrash()
            }
        }
        assertFalse(Files.exists(Path.of(journal.execution.finalPath!!)))
        val result = finalizer.finalize(journal) { journal = it }
        assertTrue(Files.exists(Path.of(result.execution.finalPath!!)))
        assertEquals(request.organization, result.organization)
    }

    @Test fun `identical bytes in an unrelated destination do not establish ownership`() {
        val request = request()
        var journal = request
        assertFailsWith<SimulatedCrash> {
            finalizer.finalize(request) {
                journal = it
                throw SimulatedCrash()
            }
        }
        val target = Path.of(journal.execution.finalPath!!)
        Files.copy(Path.of(request.execution.stagingPath!!), target)
        assertFailsWith<IllegalArgumentException> { finalizer.finalize(journal) {} }
        assertEquals(1024, Files.size(target))
        assertFalse(Files.isSameFile(target, Path.of(journal.execution.placementTemporaryPath!!)))
    }

    @Test fun `resumes a partial destination-volume copy only inside its owned directory`() {
        val request = request()
        var journal = request
        assertFailsWith<SimulatedCrash> {
            finalizer.finalize(request) {
                journal = it
                throw SimulatedCrash()
            }
        }
        val target = Path.of(journal.execution.finalPath!!)
        val directory = Files.createTempDirectory(target.parent, ".kz-")
        val token = UUID.randomUUID().toString()
        Files.writeString(directory.resolve(".owner"), "${request.id}:$token")
        Files.writeString(directory.resolve("output.part"), "interrupted copy")
        journal =
            journal.copy(
                execution =
                    journal.execution.copy(
                        placementTemporaryPath = directory.resolve("output.part").toString(),
                        placementOwnershipToken = token,
                    ),
            )
        val recovered = finalizer.finalize(journal) { journal = it }
        assertEquals(1024, Files.size(target))
        assertTrue(Files.isSameFile(target, directory.resolve("output.part")))
        finalizer.cleanupCompleted(recovered.copy(state = DownloadState.COMPLETED, completedAt = Instant.now()))
        assertFalse(Files.exists(directory))
        assertTrue(Files.exists(target))
    }

    private fun countingFinalizer(reads: MutableList<Path>) = ValidatedFileFinalizer { reads.add(it) }

    @Test fun `same filesystem reads the file once with every check on and once with the defaults`() {
        listOf(
            FinalizationChecks(hashDownloadedFile = true, verifyCopy = true, verifyPlacedFile = true),
            FinalizationChecks(),
        ).forEach { checks ->
            val reads = mutableListOf<Path>()
            val request = request()
            val placed = countingFinalizer(reads).finalize(request, checks) {}
            assertEquals(1, reads.size, "checks $checks")
            assertEquals(64, assertNotNull(placed.execution.outputSha256).length)
            // Both runs render the same destination name.
            Files.delete(Path.of(placed.execution.finalPath!!))
        }
    }

    @Test fun `with every check off nothing is hashed and placement still completes`() {
        val reads = mutableListOf<Path>()
        val placed =
            countingFinalizer(reads).finalize(
                request(),
                FinalizationChecks(hashDownloadedFile = false, verifyCopy = false, verifyPlacedFile = false),
            ) {}
        assertEquals(0, reads.size)
        assertEquals(PlacementState.PLACED, placed.execution.placement)
        assertEquals(null, placed.execution.outputSha256)
    }

    @Test fun `only the final-file check hashes the placed file once and records it`() {
        val reads = mutableListOf<Path>()
        val placed =
            countingFinalizer(reads).finalize(request(), FinalizationChecks(hashDownloadedFile = false, verifyPlacedFile = true)) {}
        assertEquals(1, reads.size)
        assertNotNull(placed.execution.outputSha256)
    }

    @Test fun `cleanup after completion and cancellation never hashes`() {
        val reads = mutableListOf<Path>()
        val finalizer = countingFinalizer(reads)
        val completed = finalizer.finalize(request()) {}
        reads.clear()
        finalizer.cleanupCompleted(completed.copy(state = DownloadState.COMPLETED, completedAt = Instant.now()))
        assertEquals(0, reads.size)
        assertTrue(Files.exists(Path.of(completed.execution.finalPath!!)))
        // The next request renders the same destination name.
        Files.delete(Path.of(completed.execution.finalPath!!))

        val cancelled = finalizer.finalize(request()) {}
        reads.clear()
        finalizer.cleanupCancelled(cancelled.copy(state = DownloadState.CANCELLED))
        assertEquals(0, reads.size)
        assertFalse(Files.exists(Path.of(cancelled.execution.finalPath!!)))
    }

    @Test fun `reports the verification, flushing and placing steps`() {
        val steps = mutableListOf<FinalizationProgress>()
        finalizer.finalize(request(), FinalizationChecks(), { steps += it }) {}
        assertEquals(
            listOf(FinalizationStep.HASHING_DOWNLOAD, FinalizationStep.FLUSHING, FinalizationStep.PLACING),
            steps.map { it.step }.distinct(),
        )
        val hashing = steps.filter { it.step == FinalizationStep.HASHING_DOWNLOAD }
        assertEquals(0L, hashing.first().processedBytes)
        assertEquals(1024L, hashing.last().processedBytes)
        assertTrue(hashing.all { it.totalBytes == 1024L })
    }

    @Test fun `a recorded hash from before the restart still detects a changed staging file`() {
        val request = request()
        var journal = request
        assertFailsWith<SimulatedCrash> {
            finalizer.finalize(request) {
                journal = it
                throw SimulatedCrash()
            }
        }
        assertNotNull(journal.execution.outputSha256)
        val staging = Path.of(request.execution.stagingPath!!)
        val bytes = Files.readAllBytes(staging).also { it[100] = 7 }
        Files.write(staging, bytes)
        assertFailsWith<IllegalArgumentException> { finalizer.finalize(journal) {} }
        assertFalse(Files.exists(Path.of(journal.execution.finalPath!!)))
    }

    private class SimulatedCrash : RuntimeException()

    @Test
    fun `places an original filename on another filesystem and cleans owned copy artifacts`() {
        val secondRoot = System.getenv("DMDL_TEST_SECOND_ROOT")?.let(Path::of)
        org.junit.jupiter.api.Assumptions
            .assumeTrue(secondRoot != null, "Set DMDL_TEST_SECOND_ROOT to exercise another filesystem")
        val destination = Files.createTempDirectory(requireNotNull(secondRoot), "dmdl-cross-volume-")
        val request = request().let { it.copy(organization = it.organization.copy(storageRoot = destination.toString())) }
        org.junit.jupiter.api.Assumptions.assumeTrue(
            Files.getFileStore(destination) != Files.getFileStore(root),
            "A different filesystem is required",
        )
        val completed = finalizer.finalize(request) {}
        val target = Path.of(completed.execution.finalPath!!)
        assertTrue(target.startsWith(destination))
        assertEquals("Original.Release.2160p.H265.HDR.Atmos.MKV", target.fileName.toString())
        assertNotNull(completed.execution.placementOwnershipToken)
        val temporary = Path.of(completed.execution.placementTemporaryPath!!)
        assertTrue(Files.isSameFile(target, temporary))
        finalizer.cleanupCompleted(completed.copy(state = DownloadState.COMPLETED))
        assertFalse(Files.exists(temporary.parent))
        assertFalse(Files.exists(Path.of(request.execution.stagingPath!!)))
        assertEquals(1024, Files.size(target))
        Files.delete(target)
        Files.delete(target.parent)
        Files.delete(destination)
    }

    private fun request(): DownloadRequest {
        val id = UUID.randomUUID()
        val staging = root.resolve(".dmdl").resolve(id.toString()).resolve("incorrect-container-name.mp4")
        Files.createDirectories(staging.parent)
        val bytes =
            ByteArray(1024).apply {
                this[0] = 0x1a
                this[1] = 0x45
                this[2] = 0xdf.toByte()
                this[3] = 0xa3.toByte()
            }
        Files.write(staging, bytes)
        val now = Instant.now()
        return DownloadRequest(
            id,
            ContentSnapshot(ContentIdentity(UUID.randomUUID(), 1, "movie", "opaque"), MediaCategory.MOVIE, "Example", 2024),
            ReleaseSnapshot(
                "Release",
                ReleaseProvenance(UUID.randomUUID(), 1, "Addon", 0, 0),
                ReleaseReference(ReleaseReferenceType.INFO_HASH, "a".repeat(40)),
            ),
            DebridProvider.TORBOX,
            OrganizationSnapshot(
                1,
                root.toString(),
                OrganizationSettings.defaults().getValue(MediaCategory.MOVIE),
                stagingRoot = root.resolve(".dmdl").toString(),
            ),
            state = DownloadState.FINALIZING,
            execution =
                DownloadExecution(
                    1,
                    UUID.randomUUID(),
                    stagingPath = staging.toString(),
                    originalFileName = "Original.Release.2160p.H265.HDR.Atmos.MKV",
                ),
            progress = DownloadProgress(1024, 1024),
            createdAt = now,
            updatedAt = now,
        )
    }
}
