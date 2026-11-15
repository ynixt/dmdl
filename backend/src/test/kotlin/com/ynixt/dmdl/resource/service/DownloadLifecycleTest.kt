package com.ynixt.dmdl.resource.service

import com.ynixt.dmdl.application.config.Aria2Properties
import com.ynixt.dmdl.application.config.StorageProperties
import com.ynixt.dmdl.domain.enum.Aria2FileAllocation
import com.ynixt.dmdl.domain.gateway.Aria2Gateway
import com.ynixt.dmdl.domain.gateway.DiskActivitySource
import com.ynixt.dmdl.domain.model.ApplicationSettings
import com.ynixt.dmdl.domain.model.Aria2TransferFile
import com.ynixt.dmdl.domain.model.Aria2TransferOptions
import com.ynixt.dmdl.domain.model.Aria2TransferState
import com.ynixt.dmdl.domain.model.Aria2TransferStatus
import com.ynixt.dmdl.domain.model.ContentIdentity
import com.ynixt.dmdl.domain.model.ContentSnapshot
import com.ynixt.dmdl.domain.model.DebridProvider
import com.ynixt.dmdl.domain.model.DiskActivity
import com.ynixt.dmdl.domain.model.DiskActivityAvailability
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
import com.ynixt.dmdl.domain.model.SpeedSample
import com.ynixt.dmdl.domain.model.TransferPhase
import com.ynixt.dmdl.domain.repository.ConfigurationStore
import com.ynixt.dmdl.domain.repository.DownloadStore
import com.ynixt.dmdl.domain.service.Aria2LifecycleService
import com.ynixt.dmdl.resource.cache.DownloadSpeedHistory
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
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val MB = 1_000_000L

class DownloadLifecycleTest {
    @TempDir lateinit var root: Path
    private val store = mockk<DownloadStore>(relaxed = true)
    private val aria = mockk<Aria2Gateway>(relaxed = true)
    private val progress = mockk<LiveDownloadProgress>()
    private val speedHistory = mockk<DownloadSpeedHistory>(relaxed = true)
    private val clock = MutableClock(Instant.parse("2026-11-14T14:41:00Z"))
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
        service = service(Aria2Properties())
    }

    private fun service(
        properties: Aria2Properties,
        disk: DiskActivitySource? = null,
        lifecycle: Aria2LifecycleService? = null,
    ) = DownloadServiceImpl(
        store,
        configuration,
        mockk(),
        aria,
        finalizer,
        mockk(relaxed = true),
        progress,
        speedHistory,
        StorageProperties(root.toString()),
        properties,
        clock = clock,
        diskActivity = disk,
        lifecycle = lifecycle,
    )

    private val fastPoll = Aria2Properties(pollInterval = Duration.ofMillis(100))

    /** aria2 answers with [bytes] completed, advancing the clock one second per answer. */
    private fun growing(
        id: UUID,
        bytes: Long,
    ): Aria2TransferStatus {
        clock.advance(Duration.ofSeconds(1))
        return transferStatus(id).copy(completedLength = bytes, totalLength = 100L * MB, downloadSpeed = 250L * MB)
    }

    /** Blocks the status call while [seconds] of clock time pass in steps the loop can observe. */
    private fun stall(seconds: Int) {
        repeat(seconds) {
            clock.advance(Duration.ofSeconds(1))
            Thread.sleep(25)
        }
    }

    private class MutableClock(
        start: Instant,
    ) : Clock() {
        private val millis = AtomicLong(start.toEpochMilli())

        fun advance(duration: Duration): Instant = Instant.ofEpochMilli(millis.addAndGet(duration.toMillis()))

        override fun instant(): Instant = Instant.ofEpochMilli(millis.get())

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this
    }

    private fun transferStatus(
        id: UUID,
        state: Aria2TransferState = Aria2TransferState.ACTIVE,
    ) = Aria2TransferStatus(
        "abcdef0123456789",
        state,
        4096,
        1024,
        512,
        if (state == Aria2TransferState.ERROR) "2" else null,
        null,
        "/downloads/.dmdl/$id",
        emptyList(),
        connections = 8,
    )

    @Test
    fun `first transfer sets started timestamp and a retry after failure keeps it`() {
        val request = current.get()
        assertNull(request.startedAt)
        val firstStart = clock.instant()
        every { aria.findStatus(any()) } returnsMany
            listOf(transferStatus(request.id), transferStatus(request.id), transferStatus(request.id, Aria2TransferState.ERROR))
        service.execute(DownloadWakeUp(request.id, 1))
        assertEquals(DownloadState.FAILED, current.get().state)
        assertEquals(firstStart, current.get().startedAt)

        clock.advance(Duration.ofMinutes(29))
        val retried = service.control(request.id, current.get().execution.generation, DownloadControl.RETRY)
        assertEquals(DownloadState.QUEUED, retried.state)
        current.set(
            current.get().copy(
                state = DownloadState.PREPARING,
                execution = current.get().execution.copy(generation = 3, owner = UUID.randomUUID()),
            ),
        )
        every { aria.findStatus(any()) } returnsMany
            listOf(transferStatus(request.id), transferStatus(request.id), transferStatus(request.id, Aria2TransferState.ERROR))
        service.execute(DownloadWakeUp(request.id, 3))
        assertEquals(3, current.get().execution.generation)
        assertEquals(DownloadState.FAILED, current.get().state)
        assertEquals(firstStart, current.get().startedAt)
    }

    @Test
    fun `a 95 second transfer records a speed sample every 30 seconds and a failure keeps the history`() {
        val request = current.get()
        val loopStart = AtomicLong(-1)
        every { aria.findStatus(any()) } answers {
            val now = clock.advance(Duration.ofSeconds(5)).toEpochMilli()
            if (loopStart.get() < 0) {
                loopStart.set(now)
                transferStatus(request.id)
            } else if (now - loopStart.get() <= 95_000) {
                transferStatus(request.id)
            } else {
                transferStatus(request.id, Aria2TransferState.ERROR)
            }
        }
        val samples = mutableListOf<SpeedSample>()
        every { speedHistory.append(request.id, capture(samples)) } just Runs
        service(Aria2Properties(pollInterval = Duration.ofMillis(100))).execute(DownloadWakeUp(request.id, 1))
        assertEquals(DownloadState.FAILED, current.get().state)
        assertEquals(3, samples.size)
        assertEquals(listOf(30_000L, 60_000L, 90_000L), samples.map { it.at.toEpochMilli() - loopStart.get() })
        // aria2 reports 512 B/s but completed bytes never grow, so the observed speed is 0.
        assertTrue(samples.all { it.speedBytesPerSecond == 0L && it.connections == 8 && it.completedBytes == 1024L })
        verify(exactly = 0) { speedHistory.delete(any()) }
    }

    @Test
    fun `a frozen aria2 keeps progress flowing with a waiting phase and a decaying speed`() {
        val request = current.get()
        val calls = AtomicLong(0)
        every { aria.findStatus(any()) } answers {
            when (val call = calls.incrementAndGet()) {
                in 1L..6L -> {
                    growing(request.id, call * 10 * MB)
                }

                7L -> {
                    stall(30)
                    // aria2 comes back reporting a catch-up burst of 250 MB/s.
                    growing(request.id, 60 * MB)
                }

                else -> {
                    transferStatus(request.id, Aria2TransferState.ERROR)
                }
            }
        }
        val published = mutableListOf<DownloadProgress>()
        every { progress.publish(any(), any(), capture(published)) } just Runs
        service(fastPoll).execute(DownloadWakeUp(request.id, 1))

        val waiting = published.filter { it.phase == TransferPhase.WAITING_FOR_ARIA2 }
        assertTrue(waiting.size >= 3, "progress keeps being published while aria2 is frozen")
        assertEquals(0, waiting.last().speedBytesPerSecond)
        val afterFreeze = published.last { it.phase == TransferPhase.TRANSFERRING && it.completedBytes == 60 * MB }
        assertEquals(0, afterFreeze.speedBytesPerSecond)
        assertEquals(250 * MB, afterFreeze.aria2SpeedBytesPerSecond)
        assertEquals("KZ.ARIA2_NETWORK_FAILED", current.get().error)
    }

    @Test
    fun `aria2 unavailable for over 110 seconds fails the transfer`() {
        val request = current.get()
        val calls = AtomicLong(0)
        every { aria.findStatus(any()) } answers {
            if (calls.incrementAndGet() <= 2) {
                growing(request.id, 10 * MB)
            } else {
                clock.advance(Duration.ofSeconds(15))
                throw java.io.IOException("connection refused")
            }
        }
        service(fastPoll).execute(DownloadWakeUp(request.id, 1))
        assertEquals(DownloadState.FAILED, current.get().state)
        assertEquals("aria2 is unavailable; check the daemon and retry this request", current.get().error)
    }

    @Test
    fun `aria2 unavailable for 60 seconds does not fail the transfer`() {
        val request = current.get()
        val calls = AtomicLong(0)
        every { aria.findStatus(any()) } answers {
            when (calls.incrementAndGet()) {
                1L, 2L -> {
                    growing(request.id, 10 * MB)
                }

                in 3L..6L -> {
                    clock.advance(Duration.ofSeconds(15))
                    throw java.io.IOException("connection refused")
                }

                else -> {
                    transferStatus(request.id, Aria2TransferState.ERROR)
                }
            }
        }
        service(fastPoll).execute(DownloadWakeUp(request.id, 1))
        assertEquals("KZ.ARIA2_NETWORK_FAILED", current.get().error)
    }

    @Test
    fun `an aria2 restart shows a restarting phase and pauses the unavailability clock`() {
        val request = current.get()
        val restarting = AtomicBoolean(false)
        val lifecycle = mockk<Aria2LifecycleService>()
        every { lifecycle.restarting() } answers { restarting.get() }
        every { lifecycle.restarts() } returns 0
        val calls = AtomicLong(0)
        every { aria.findStatus(any()) } answers {
            when (calls.incrementAndGet()) {
                1L, 2L -> {
                    growing(request.id, 10 * MB)
                }

                in 3L..9L -> {
                    restarting.set(true)
                    clock.advance(Duration.ofSeconds(30))
                    throw java.io.IOException("connection refused")
                }

                10L -> {
                    restarting.set(false)
                    growing(request.id, 20 * MB)
                }

                else -> {
                    transferStatus(request.id, Aria2TransferState.ERROR)
                }
            }
        }
        val published = mutableListOf<DownloadProgress>()
        every { progress.publish(any(), any(), capture(published)) } just Runs
        service(fastPoll, lifecycle = lifecycle).execute(DownloadWakeUp(request.id, 1))
        assertTrue(published.any { it.phase == TransferPhase.ARIA2_RESTARTING })
        assertTrue(published.any { it.completedBytes == 20 * MB }, "the transfer resumes after the restart")
        assertEquals("KZ.ARIA2_NETWORK_FAILED", current.get().error)
    }

    @Test
    fun `a restarted aria2 reporting no size and zero bytes keeps the known progress and causes no spike`() {
        val request = current.get()
        val calls = AtomicLong(0)
        every { aria.findStatus(any()) } answers {
            when (val call = calls.incrementAndGet()) {
                in 1L..6L -> {
                    growing(request.id, 70_000 * MB + call * 30 * MB)
                }

                // Restored from the session but not yet reconnected: unknown size, nothing completed.
                in 7L..20L -> {
                    clock.advance(Duration.ofSeconds(1))
                    transferStatus(request.id).copy(totalLength = 0, completedLength = 0, downloadSpeed = 0)
                }

                in 21L..26L -> {
                    growing(request.id, 70_180 * MB + (call - 20) * 30 * MB)
                }

                else -> {
                    transferStatus(request.id, Aria2TransferState.ERROR)
                }
            }
        }
        val published = mutableListOf<DownloadProgress>()
        every { progress.publish(any(), any(), capture(published)) } just Runs
        service(fastPoll).execute(DownloadWakeUp(request.id, 1))

        val beforeError = published.dropLast(1)
        assertTrue(beforeError.none { it.completedBytes == 0L }, "the progress bar never drops to zero")
        val reload = beforeError.indexOfFirst { it.phase == TransferPhase.WAITING_FOR_ARIA2 }
        assertTrue(reload > 0, "the reload shows the waiting phase")
        val afterReload = beforeError.drop(reload)
        assertTrue(afterReload.all { it.speedBytesPerSecond <= 30 * MB }, "no speed spike after the reload")
        assertTrue(afterReload.all { (it.averageBytesPerSecond ?: 0) <= 30 * MB }, "no average spike after the reload")
    }

    @Test
    fun `resuming a paused download keeps its stored progress while aria2 reloads and causes no spike`() {
        val request = current.get()
        // Progress stored when the download was paused.
        current.set(request.copy(progress = DownloadProgress(6_710 * MB, 86_500 * MB, 0)))
        val calls = AtomicLong(0)
        every { aria.findStatus(any()) } answers {
            when (val call = calls.incrementAndGet()) {
                in 1L..4L -> {
                    clock.advance(Duration.ofSeconds(1))
                    transferStatus(request.id).copy(totalLength = 0, completedLength = 0, downloadSpeed = 0)
                }

                in 5L..12L -> {
                    growing(request.id, 6_780 * MB + (call - 5) * 35 * MB).copy(totalLength = 86_500 * MB)
                }

                else -> {
                    transferStatus(request.id, Aria2TransferState.ERROR)
                }
            }
        }
        val published = mutableListOf<DownloadProgress>()
        every { progress.publish(any(), any(), capture(published)) } just Runs
        service(fastPoll).execute(DownloadWakeUp(request.id, 1))

        val beforeError = published.dropLast(1)
        assertTrue(beforeError.none { it.completedBytes == 0L }, "the stored progress is shown while aria2 reloads")
        assertEquals(TransferPhase.WAITING_FOR_ARIA2, beforeError.first().phase)
        assertTrue(beforeError.all { it.speedBytesPerSecond <= 250 * MB }, "no speed spike from a zero baseline")
        assertTrue(beforeError.all { (it.averageBytesPerSecond ?: 0) <= 250 * MB }, "no average spike from a zero baseline")
        val measured = beforeError.filter { it.phase == TransferPhase.TRANSFERRING && (it.aria2SpeedBytesPerSecond ?: 0) > 0 }
        assertTrue(measured.last().averageBytesPerSecond!! <= 35 * MB)
    }

    @Test
    fun `a slow database checkpoint does not stop live progress and never overwrites a newer state`() {
        val request = current.get()
        val calls = AtomicLong(0)
        every { aria.findStatus(any()) } answers {
            when (val call = calls.incrementAndGet()) {
                in 1L..20L -> growing(request.id, call * 10 * MB)
                else -> transferStatus(request.id, Aria2TransferState.ERROR)
            }
        }
        val slowWrites = AtomicLong(0)
        every { store.checkpoint(any()) } answers {
            val updated = firstArg<DownloadRequest>()
            // Progress checkpoints hit a disk busy flushing the download: each takes over a second.
            if (updated.state == DownloadState.TRANSFERRING && updated.progress.completedBytes > 0) {
                slowWrites.incrementAndGet()
                Thread.sleep(1200)
            }
            val before = current.get()
            before.state.reservesCapacity && before.execution.generation == updated.execution.generation &&
                current.compareAndSet(before, updated)
        }
        val publishedAt = mutableListOf<Long>()
        every { progress.publish(any(), any(), any()) } answers { publishedAt += System.nanoTime() }
        service(fastPoll).execute(DownloadWakeUp(request.id, 1))

        assertTrue(slowWrites.get() >= 1)
        val longestGapMillis = publishedAt.zipWithNext { a, b -> (b - a) / 1_000_000 }.max()
        assertTrue(longestGapMillis < 1000, "progress kept flowing during slow checkpoints (longest gap $longestGapMillis ms)")
        assertEquals(DownloadState.FAILED, current.get().state)
        assertEquals("KZ.ARIA2_NETWORK_FAILED", current.get().error)
    }

    @Test
    fun `a freeze with pending disk writes shows the writing to disk phase`() {
        val request = current.get()
        val written = AtomicLong(0)
        val disk =
            object : DiskActivitySource {
                override fun read(stagingRoot: Path) =
                    DiskActivity(DiskActivityAvailability.AVAILABLE, written.addAndGet(40 * MB), 400L * 1024 * 1024)
            }
        val calls = AtomicLong(0)
        every { aria.findStatus(any()) } answers {
            when (val call = calls.incrementAndGet()) {
                in 1L..4L -> {
                    growing(request.id, call * 10 * MB)
                }

                5L -> {
                    stall(12)
                    growing(request.id, 40 * MB)
                }

                else -> {
                    transferStatus(request.id, Aria2TransferState.ERROR)
                }
            }
        }
        val published = mutableListOf<DownloadProgress>()
        every { progress.publish(any(), any(), capture(published)) } just Runs
        service(fastPoll, disk = disk).execute(DownloadWakeUp(request.id, 1))
        val writing = published.filter { it.phase == TransferPhase.WRITING_TO_DISK }
        assertTrue(writing.isNotEmpty())
        assertEquals(400L * 1024 * 1024, writing.first().pendingWriteBytes)
        assertTrue(published.all { it.diskActivity == DiskActivityAvailability.AVAILABLE })
        assertTrue(published.any { (it.diskBytesPerSecond ?: 0) > 0 })
    }

    @Test
    fun `pausing keeps the speed history and removing a finished download deletes it`() {
        val request = current.get()
        service.control(request.id, 1, DownloadControl.PAUSE)
        assertEquals(DownloadState.PAUSED, current.get().state)
        verify(exactly = 0) { speedHistory.delete(any()) }

        current.set(current.get().copy(state = DownloadState.COMPLETED, completedAt = Instant.now()))
        every { store.removeFinishedFromHistory(request.id) } returns 1
        assertEquals(1, service.removeFinishedFromHistory(request.id))
        verify(exactly = 1) { speedHistory.delete(request.id) }
    }

    @Test
    fun `speed history is read only for existing downloads`() {
        val request = current.get()
        val sample = SpeedSample(clock.instant(), 2048, 4)
        every { speedHistory.find(request.id) } returns listOf(sample)
        assertEquals(listOf(sample), assertNotNull(service.speedHistory(request.id)))
        val unknown = UUID.randomUUID()
        every { store.find(unknown) } returns null
        assertNull(service.speedHistory(unknown))
        verify(exactly = 0) { speedHistory.find(unknown) }
    }

    @Test
    fun `read endpoints use live transferring generation and durable terminal progress`() {
        val stored =
            current.get().copy(
                state = DownloadState.TRANSFERRING,
                progress = DownloadProgress(8, 100, 10, connections = 2),
            )
        current.set(stored)
        every { progress.find(stored.id, 1) } returns
            DownloadProgress(64, 100, 40, connections = 3)
        assertEquals(64, service.find(stored.id)!!.progress.completedBytes)
        assertEquals(
            3,
            service
                .history()
                .single()
                .progress.connections,
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
    fun `publishes the open connection count only while active`() {
        val initial = current.get()
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
        val published = mutableListOf<DownloadProgress>()
        every { progress.publish(any(), any(), capture(published)) } just Runs
        service.execute(DownloadWakeUp(initial.id, 1))
        val live = published.first()
        assertEquals(256, live.completedBytes)
        assertEquals(192, live.speedBytesPerSecond)
        assertEquals(2, live.connections)
        assertNull(published.last().connections)
        assertEquals("KZ.ARIA2_NETWORK_FAILED", current.get().error)
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
        verify(exactly = 1) { speedHistory.delete(request.id) }
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
        assertNotNull(completed.startedAt)
        verify(exactly = 1) { speedHistory.delete(request.id) }
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

    @Test
    fun `submits series metadata episode with Anime destination and category`() {
        val discovery = mockk<com.ynixt.dmdl.domain.service.DiscoveryService>()
        val addon =
            com.ynixt.dmdl.domain.model.AddonConfiguration(
                UUID.randomUUID(),
                1,
                "https://example/manifest.json",
                com.ynixt.dmdl.domain.model
                    .AddonManifest("stream", "Stream", emptyList(), emptyList(), emptyList()),
            )
        val identity = ContentIdentity(UUID.randomUUID(), 1, "series", "tt123")
        val metadata =
            com.ynixt.dmdl.domain.model.MetadataDetails(
                identity,
                "Show",
                videos =
                    listOf(
                        com.ynixt.dmdl.domain.model
                            .MetadataVideo("ep", 2, 7, "Seven"),
                    ),
            )
        val content =
            ContentSnapshot(
                identity.copy(videoId = "ep"),
                MediaCategory.ANIME,
                "Show",
                season = 2,
                episode = 7,
                episodeTitle = "Seven",
            )
        val release = current.get().release.copy(provenance = ReleaseProvenance(addon.id, 1, addon.name, 0, 0))
        val categories = OrganizationSettings.defaults(root.toString())
        val destination = categories.getValue(MediaCategory.ANIME).destinations.single()
        Files.createDirectories(Path.of(destination.path))
        every { configuration.settings() } returns
            ApplicationSettings(
                "fixture",
                "hash",
                organization = OrganizationSettings(categories = categories),
            )
        every { configuration.searchAddons() } returns listOf(addon)
        every { discovery.details(any(), false) } returns metadata
        every { discovery.releases(content) } returns
            listOf(
                com.ynixt.dmdl.domain.model.ReleaseGroup(
                    addon.id,
                    addon.name,
                    0,
                    com.ynixt.dmdl.domain.model.SourceOutcome.SUCCESS,
                    listOf(release),
                ),
            )
        val service =
            DownloadServiceImpl(
                store,
                configuration,
                discovery,
                aria,
                finalizer,
                mockk(relaxed = true),
                progress,
                speedHistory,
                StorageProperties(root.toString()),
                Aria2Properties(),
            )
        every { store.find(any()) } returns null
        val submitted = service.submit(content, release, destination.id)
        assertEquals(MediaCategory.ANIME, submitted.content.category)
        assertEquals("series", submitted.content.identity.resourceType)
        assertEquals(destination.id, submitted.organization.destinationId)
        assertEquals(destination.path, submitted.organization.storageRoot)
        verify(exactly = 1) { discovery.releases(content) }
    }
}
