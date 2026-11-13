package com.ynixt.dmdl.resource.service

import com.ynixt.dmdl.application.config.Aria2Properties
import com.ynixt.dmdl.application.config.StorageProperties
import com.ynixt.dmdl.domain.enum.Aria2FileAllocation
import com.ynixt.dmdl.domain.gateway.Aria2Gateway
import com.ynixt.dmdl.domain.model.ApplicationSettings
import com.ynixt.dmdl.domain.model.Aria2TransferFile
import com.ynixt.dmdl.domain.model.Aria2TransferOptions
import com.ynixt.dmdl.domain.model.Aria2TransferState
import com.ynixt.dmdl.domain.model.Aria2TransferStatus
import com.ynixt.dmdl.domain.model.ContentIdentity
import com.ynixt.dmdl.domain.model.ContentSnapshot
import com.ynixt.dmdl.domain.model.DebridProvider
import com.ynixt.dmdl.domain.model.DownloadControl
import com.ynixt.dmdl.domain.model.DownloadExecution
import com.ynixt.dmdl.domain.model.DownloadProgress
import com.ynixt.dmdl.domain.model.DownloadRequest
import com.ynixt.dmdl.domain.model.DownloadState
import com.ynixt.dmdl.domain.model.DownloadWakeUp
import com.ynixt.dmdl.domain.model.MediaCategory
import com.ynixt.dmdl.domain.model.OrganizationSettings
import com.ynixt.dmdl.domain.model.OrganizationSnapshot
import com.ynixt.dmdl.domain.model.ReleaseProvenance
import com.ynixt.dmdl.domain.model.ReleaseReference
import com.ynixt.dmdl.domain.model.ReleaseReferenceType
import com.ynixt.dmdl.domain.model.ReleaseSnapshot
import com.ynixt.dmdl.domain.repository.ConfigurationStore
import com.ynixt.dmdl.domain.repository.DownloadStore
import com.ynixt.dmdl.resource.cache.LiveDownloadProgress
import com.ynixt.dmdl.resource.gateway.files.ValidatedFileFinalizer
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadLifecycleTest {
    @TempDir lateinit var root: Path
    private val store = mockk<DownloadStore>(relaxed = true)
    private val aria = mockk<Aria2Gateway>(relaxed = true)
    private val progress = mockk<LiveDownloadProgress>()
    private val configuration = mockk<ConfigurationStore>()
    private val finalizer = spyk(ValidatedFileFinalizer())
    private lateinit var current: AtomicReference<DownloadRequest>
    private lateinit var service: DownloadServiceImpl
    private val created = AtomicBoolean(false)
    private val removed = AtomicBoolean(false)

    @BeforeEach
    fun setup() {
        val id = UUID.randomUUID()
        val now = Instant.now()
        current =
            AtomicReference(
                DownloadRequest(
                    id,
                    ContentSnapshot(ContentIdentity(UUID.randomUUID(), 1, "movie", "tt1234"), MediaCategory.MOVIE, "Movie"),
                    ReleaseSnapshot(
                        "Fixture",
                        ReleaseProvenance(UUID.randomUUID(), 1, "Source", 0, 0, DebridProvider.TORBOX),
                        ReleaseReference(ReleaseReferenceType.PROVIDER_HTTP, "https://fixture.example/file.mkv"),
                    ),
                    DebridProvider.TORBOX,
                    OrganizationSnapshot(
                        1,
                        root.toString(),
                        OrganizationSettings.defaults().getValue(MediaCategory.MOVIE),
                        stagingRoot = root.resolve(".dmdl").toString(),
                    ),
                    state = DownloadState.PREPARING,
                    execution = DownloadExecution(1, UUID.randomUUID()),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        every { store.find(id) } answers { current.get() }
        every { store.history(any()) } answers { listOf(current.get()) }
        every { store.reserveNext(any()) } returns null
        every { store.startOnce(id, any()) } answers
            { current.get().state.reservesCapacity && current.get().execution.generation == secondArg<Long>() }
        every { store.checkpoint(any()) } answers {
            val before = current.get()
            val updated = firstArg<DownloadRequest>()
            before.state.reservesCapacity && before.execution.generation == updated.execution.generation &&
                current.compareAndSet(before, updated)
        }
        every { store.changeState(id, any(), any()) } answers {
            val before = current.get()
            val updated =
                before.copy(
                    state = thirdArg(),
                    execution = before.execution.copy(generation = before.execution.generation + 1, owner = null),
                )
            if (before.execution.generation == secondArg<Long>() && current.compareAndSet(before, updated)) updated else null
        }
        every { store.recordCancellationCleanup(id, any(), any()) } answers {
            val before = current.get()
            val error = thirdArg<String?>()
            if (before.state == DownloadState.CANCELLED && before.execution.generation == secondArg<Long>()) {
                current.set(before.copy(error = error, execution = before.execution.copy(cancellationCleanupCompleted = error == null)))
                true
            } else {
                false
            }
        }
        every { progress.find(any(), any()) } returns null
        every { progress.publish(any(), any(), any()) } just Runs
        every { aria.findStatus(any()) } answers {
            if (!created.get()) {
                null
            } else {
                Aria2TransferStatus(
                    firstArg(),
                    if (removed.get()) Aria2TransferState.REMOVED else Aria2TransferState.ACTIVE,
                    1024,
                    0,
                    0,
                    null,
                    null,
                    "/downloads/.dmdl/$id",
                    emptyList(),
                )
            }
        }
        every { aria.forceRemove(any()) } answers { removed.set(true) }
        every { aria.removeDownloadResult(any()) } answers { created.set(false) }
        every { configuration.settings() } returns ApplicationSettings("fixture", "hash")
        service =
            DownloadServiceImpl(
                store,
                configuration,
                mockk(),
                aria,
                finalizer,
                mockk(relaxed = true),
                progress,
                StorageProperties(root.toString()),
                Aria2Properties(),
            )
    }

    @Test
    fun `read endpoints use live transferring generation and durable terminal progress`() {
        val stored =
            current.get().copy(
                state = DownloadState.TRANSFERRING,
                progress = DownloadProgress(8, 100, 10, connections = 2, connectionSpeedsBytesPerSecond = listOf(4, 6)),
            )
        current.set(stored)
        every { progress.find(stored.id, 1) } returns
            DownloadProgress(64, 100, 40, connections = 3, connectionSpeedsBytesPerSecond = listOf(10, 10, 20))
        assertEquals(64, service.find(stored.id)!!.progress.completedBytes)
        assertEquals(
            listOf(10L, 10L, 20L),
            service
                .history()
                .single()
                .progress.connectionSpeedsBytesPerSecond,
        )
        listOf(DownloadState.PAUSED, DownloadState.COMPLETED, DownloadState.CANCELLED, DownloadState.FAILED).forEach { state ->
            current.set(stored.copy(state = state, execution = stored.execution.copy(generation = 2)))
            assertEquals(DownloadProgress(8, 100, 0), service.history().single().progress)
        }
        verify(exactly = 2) { progress.find(any(), any()) }
        every { progress.find(stored.id, 1) } returns null
        current.set(stored)
        assertEquals(stored.progress, service.find(stored.id)!!.progress)
    }

    @Test
    fun `publishes separate connection speeds and preserves progress when optional telemetry fails`() {
        val initial = current.get()
        listOf(false, true).forEach { unavailable ->
            current.set(initial)
            val active =
                Aria2TransferStatus(
                    "abcdef0123456789",
                    Aria2TransferState.ACTIVE,
                    1024,
                    256,
                    192,
                    null,
                    null,
                    "/downloads/.dmdl/${initial.id}",
                    emptyList(),
                    connections = 2,
                )
            every { aria.findStatus(any()) } returnsMany
                listOf(active, active, active.copy(state = Aria2TransferState.ERROR, errorCode = "2"))
            every { aria.connectionSpeeds(any()) } answers {
                if (unavailable) throw java.io.IOException("fixture optional telemetry failure")
                listOf(128L, 64L)
            }
            val published = mutableListOf<DownloadProgress>()
            every { progress.publish(any(), any(), capture(published)) } just Runs
            service.execute(DownloadWakeUp(initial.id, 1))
            val live = published.first()
            assertEquals(256, live.completedBytes)
            assertEquals(192, live.speedBytesPerSecond)
            assertEquals(2, live.connections)
            assertEquals(if (unavailable) null else listOf(128L, 64L), live.connectionSpeedsBytesPerSecond)
            assertNull(published.last().connections)
            assertNull(published.last().connectionSpeedsBytesPerSecond)
            assertEquals("KZ.ARIA2_NETWORK_FAILED", current.get().error)
        }
    }

    @Test
    fun `zero open connections publishes an empty speed list without requesting server statistics`() {
        val initial = current.get()
        val active =
            Aria2TransferStatus(
                "abcdef0123456789",
                Aria2TransferState.ACTIVE,
                1024,
                0,
                0,
                null,
                null,
                "",
                emptyList(),
                connections = 0,
            )
        every { aria.findStatus(any()) } returnsMany listOf(active, active, active.copy(state = Aria2TransferState.ERROR, errorCode = "2"))
        val published = mutableListOf<DownloadProgress>()
        every { progress.publish(any(), any(), capture(published)) } just Runs
        service.execute(DownloadWakeUp(initial.id, 1))
        assertEquals(0, published.first().connections)
        assertEquals(emptyList(), published.first().connectionSpeedsBytesPerSecond)
        verify(exactly = 0) { aria.connectionSpeeds(any()) }
    }

    @Test
    fun `failed cancellation cleanup is retried and records completion`() {
        val request = current.get()
        val directory = Files.createDirectories(root.resolve(".dmdl").resolve(request.id.toString()))
        Files.write(directory.resolve("partial.mkv"), ByteArray(1024))
        var attempts = 0
        every { finalizer.cleanupCancelled(any()) } answers {
            if (++attempts == 1) throw java.io.IOException("fixture permission failure")
            callOriginal()
        }
        val cancelled = service.control(request.id, 1, DownloadControl.CANCEL)
        assertEquals("KZ.CANCEL_CLEANUP_PENDING", cancelled.error)
        assertFalse(cancelled.execution.cancellationCleanupCompleted)
        assertTrue(Files.exists(directory))
        service.reconcileStartup()
        assertTrue(current.get().execution.cancellationCleanupCompleted)
        assertNull(current.get().error)
        assertFalse(Files.exists(directory))
        service.drain()
        verify(exactly = 2) { finalizer.cleanupCancelled(any()) }
    }

    @Test
    fun `cancellation fences delayed task creation before removing partial files`() {
        val request = current.get()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val directory = root.resolve(".dmdl").resolve(request.id.toString())
        every { aria.addUri(any(), any(), any(), any()) } answers {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            Files.write(directory.resolve("partial.mkv"), ByteArray(1024))
            created.set(true)
            secondArg()
        }
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        try {
            val worker = executor.submit { service.execute(DownloadWakeUp(request.id, 1)) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val cancellation = executor.submit<DownloadRequest> { service.control(request.id, 1, DownloadControl.CANCEL) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (current.get().state != DownloadState.CANCELLED && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(DownloadState.CANCELLED, current.get().state)
            assertFalse(cancellation.isDone)
            release.countDown()
            assertTrue(cancellation.get(10, TimeUnit.SECONDS).execution.cancellationCleanupCompleted)
            worker.get(10, TimeUnit.SECONDS)
            assertFalse(Files.exists(directory))
            assertFalse(created.get())
            verify(exactly = 1) { aria.forceRemove(any()) }
            service.execute(DownloadWakeUp(request.id, 1))
            verify(exactly = 1) { aria.addUri(any(), any(), any(), any()) }
        } finally {
            release.countDown()
            executor.shutdownNow()
            executor.close()
        }
    }

    @Test
    fun `cancellation removes current daemon staging when the recorded root was the former mapping`() {
        val request = current.get()
        current.set(request.copy(organization = request.organization.copy(stagingRoot = root.resolve("former").toString())))
        val directory = Files.createDirectories(root.resolve(".dmdl").resolve(request.id.toString()))
        Files.write(directory.resolve("partial.mkv"), ByteArray(2048))
        Files.write(directory.resolve("partial.mkv.aria2"), ByteArray(64))
        val unrelated = Files.write(directory.parent.resolve("other.txt"), byteArrayOf(1))
        val cancelled = service.control(request.id, request.execution.generation, DownloadControl.CANCEL)
        assertTrue(cancelled.execution.cancellationCleanupCompleted)
        assertFalse(Files.exists(directory))
        assertTrue(Files.exists(unrelated))
    }

    @Test
    fun `unsupported fallocate retries the same request with durable prealloc and completes original output`() {
        val request = current.get()
        val directory = root.resolve(".dmdl").resolve(request.id.toString())
        val modes = mutableListOf<Aria2FileAllocation>()
        val gids = mutableListOf<String>()
        val bytes =
            ByteArray(128).apply {
                this[0] = 0x1a
                this[1] = 0x45
                this[2] = 0xdf.toByte()
                this[3] = 0xa3.toByte()
            }
        every { aria.addUri(any(), any(), any(), any()) } answers {
            val options = arg<Aria2TransferOptions>(3)
            modes += options.fileAllocation
            gids += secondArg<String>()
            created.set(true)
            if (options.fileAllocation == Aria2FileAllocation.PREALLOC) Files.write(directory.resolve("Original.mkv"), bytes)
            secondArg()
        }
        every { aria.findStatus(any()) } answers {
            if (!created.get()) {
                null
            } else {
                Aria2TransferStatus(
                    firstArg(),
                    if (modes.last() == Aria2FileAllocation.FALLOC) Aria2TransferState.ERROR else Aria2TransferState.COMPLETE,
                    bytes.size.toLong(),
                    if (modes.last() == Aria2FileAllocation.FALLOC) 0 else bytes.size.toLong(),
                    0,
                    "17",
                    "fallocate failed. cause: Not supported",
                    "/downloads/.dmdl/${request.id}",
                    listOf(Aria2TransferFile("/downloads/.dmdl/${request.id}/Original.mkv", bytes.size.toLong())),
                )
            }
        }
        service.execute(DownloadWakeUp(request.id, 1))
        val completed = current.get()
        assertEquals(DownloadState.COMPLETED, completed.state)
        assertEquals(listOf(Aria2FileAllocation.FALLOC, Aria2FileAllocation.PREALLOC), modes)
        assertEquals(1, gids.toSet().size)
        assertEquals(Aria2FileAllocation.PREALLOC, completed.execution.fileAllocationOverride)
        assertEquals(bytes.size.toLong(), completed.progress.completedBytes)
        assertTrue(Files.isRegularFile(Path.of(completed.execution.finalPath!!)))
        assertEquals(Aria2FileAllocation.FALLOC, configuration.settings()!!.transfer.fileAllocation)
    }

    @Test
    fun `recorded allocation fallback survives resume and live tuning`() {
        val request =
            current.get().copy(
                execution =
                    current.get().execution.copy(
                        aria2Gid = "abcdef0123456789",
                        fileAllocationOverride = Aria2FileAllocation.PREALLOC,
                    ),
            )
        current.set(request)
        val paused = Aria2TransferStatus("abcdef0123456789", Aria2TransferState.PAUSED, 1024, 0, 0, null, null, "", emptyList())
        every { aria.findStatus(any()) } returnsMany listOf(paused, paused.copy(state = Aria2TransferState.ERROR, errorCode = "2"))
        service.execute(DownloadWakeUp(request.id, 1))
        assertEquals("KZ.ARIA2_NETWORK_FAILED", current.get().error)
        verify(exactly = 1) { aria.unpause("abcdef0123456789") }
        verify(exactly = 1) { aria.changeOptions("abcdef0123456789", match { it.fileAllocation == Aria2FileAllocation.PREALLOC }) }
        verify(exactly = 0) { aria.addUri(any(), any(), any(), any()) }
        current.set(current.get().copy(state = DownloadState.TRANSFERRING))
        every { store.history(any()) } answers {
            listOf(current.get().copy(execution = current.get().execution.copy(fileAllocationOverride = null)))
        }
        every { configuration.settings() } returns
            ApplicationSettings("fixture", "hash", transfer = Aria2TransferOptions(maxConnectionPerServer = 16))
        service.drain()
        verify {
            aria.changeOptions(
                "abcdef0123456789",
                match {
                    it.fileAllocation == Aria2FileAllocation.PREALLOC &&
                        it.maxConnectionPerServer == 16
                },
            )
        }
    }

    @Test
    fun `allocation fallback is bounded and further storage errors retain progress`() {
        val request = current.get()
        var attempts = 0
        every { aria.addUri(any(), any(), any(), any()) } answers {
            attempts++
            created.set(true)
            secondArg()
        }
        every { aria.findStatus(any()) } answers {
            if (!created.get()) {
                null
            } else {
                Aria2TransferStatus(
                    firstArg(),
                    Aria2TransferState.ERROR,
                    8192,
                    0,
                    0,
                    "17",
                    "fallocate failed. cause: Not supported",
                    "",
                    emptyList(),
                )
            }
        }
        service.execute(DownloadWakeUp(request.id, 1))
        assertEquals(2, attempts)
        assertEquals(DownloadState.FAILED, current.get().state)
        assertEquals("KZ.ARIA2_STORAGE_FAILED", current.get().error)
        assertEquals(8192, current.get().progress.totalBytes)
        assertEquals(Aria2FileAllocation.PREALLOC, current.get().execution.fileAllocationOverride)
    }

    @Test
    fun `direct transfer errors distinguish storage speed network and HTTP failures`() {
        val request = current.get()
        listOf(
            "17" to "KZ.ARIA2_STORAGE_FAILED",
            "9" to "KZ.ARIA2_STORAGE_FAILED",
            "5" to "KZ.ARIA2_SPEED_LIMIT",
            "2" to "KZ.ARIA2_NETWORK_FAILED",
            "19" to "KZ.ARIA2_NETWORK_FAILED",
            "22" to "KZ.HTTP_LINK_FAILED",
        ).forEach { (code, failure) ->
            current.set(request)
            every { aria.findStatus(any()) } returns
                Aria2TransferStatus(
                    "abcdef0123456789",
                    Aria2TransferState.ERROR,
                    4096,
                    0,
                    0,
                    code,
                    "diagnostic failure https://fixture.invalid/token-secret",
                    "",
                    emptyList(),
                )
            service.execute(DownloadWakeUp(request.id, 1))
            assertEquals(DownloadState.FAILED, current.get().state)
            assertEquals(failure, current.get().error)
            assertEquals(4096, current.get().progress.totalBytes)
        }
    }
}
