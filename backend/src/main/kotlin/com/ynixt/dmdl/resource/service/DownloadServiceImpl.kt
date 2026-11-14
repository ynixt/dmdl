package com.ynixt.dmdl.resource.service

import com.ynixt.dmdl.application.config.Aria2Properties
import com.ynixt.dmdl.application.config.StorageProperties
import com.ynixt.dmdl.domain.enum.Aria2FileAllocation
import com.ynixt.dmdl.domain.gateway.AllocationProgressSource
import com.ynixt.dmdl.domain.gateway.Aria2Gateway
import com.ynixt.dmdl.domain.gateway.FileFinalizer
import com.ynixt.dmdl.domain.model.Aria2Gid
import com.ynixt.dmdl.domain.model.Aria2TransferOptions
import com.ynixt.dmdl.domain.model.Aria2TransferState
import com.ynixt.dmdl.domain.model.Aria2TransferStatus
import com.ynixt.dmdl.domain.model.ContentSnapshot
import com.ynixt.dmdl.domain.model.DownloadControl
import com.ynixt.dmdl.domain.model.DownloadExecution
import com.ynixt.dmdl.domain.model.DownloadFailure
import com.ynixt.dmdl.domain.model.DownloadProgress
import com.ynixt.dmdl.domain.model.DownloadRequest
import com.ynixt.dmdl.domain.model.DownloadState
import com.ynixt.dmdl.domain.model.DownloadWakeUp
import com.ynixt.dmdl.domain.model.MediaCategory
import com.ynixt.dmdl.domain.model.OrganizationRenderer
import com.ynixt.dmdl.domain.model.OrganizationSnapshot
import com.ynixt.dmdl.domain.model.PlacementState
import com.ynixt.dmdl.domain.model.ReleaseReferenceType
import com.ynixt.dmdl.domain.model.ReleaseSnapshot
import com.ynixt.dmdl.domain.model.SpeedSample
import com.ynixt.dmdl.domain.queue.producer.DownloadWakeUpProducer
import com.ynixt.dmdl.domain.repository.ConfigurationStore
import com.ynixt.dmdl.domain.repository.DownloadStore
import com.ynixt.dmdl.domain.service.DiscoveryService
import com.ynixt.dmdl.domain.service.DownloadService
import com.ynixt.dmdl.resource.cache.DownloadSpeedHistory
import com.ynixt.dmdl.resource.cache.LiveDownloadProgress
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Service
class DownloadServiceImpl(
    private val store: DownloadStore,
    private val configuration: ConfigurationStore,
    private val discovery: DiscoveryService,
    private val aria2: Aria2Gateway,
    private val finalizer: FileFinalizer,
    private val producer: DownloadWakeUpProducer,
    private val progress: LiveDownloadProgress,
    private val speedHistory: DownloadSpeedHistory,
    private val storage: StorageProperties,
    private val aria2Properties: Aria2Properties,
    private val allocation: AllocationProgressSource? = null,
    private val clock: Clock = Clock.systemUTC(),
) : DownloadService {
    private val owner = UUID.randomUUID()
    private val transferLocks = Array(64) { ReentrantLock() }

    @Volatile private var appliedTuning: Pair<Int, Aria2TransferOptions>? = null

    override fun history(): List<DownloadRequest> = store.history(visibleOnly = true).map(::withLiveProgress)

    override fun removeFinishedFromHistory(id: UUID?): Int {
        val finished =
            store
                .history(visibleOnly = true)
                .filter { it.state in setOf(DownloadState.COMPLETED, DownloadState.CANCELLED) && (id == null || it.id == id) }
                .map { it.id }
        return store.removeFinishedFromHistory(id).also { finished.forEach(speedHistory::delete) }
    }

    override fun find(id: UUID): DownloadRequest? = store.find(id)?.let(::withLiveProgress)

    override fun speedHistory(id: UUID): List<SpeedSample>? = store.find(id)?.let { speedHistory.find(id) }

    private fun withLiveProgress(request: DownloadRequest): DownloadRequest =
        if (request.state == DownloadState.TRANSFERRING) {
            request.copy(progress = progress.find(request.id, request.execution.generation) ?: request.progress)
        } else {
            request.copy(
                progress =
                    request.progress.copy(
                        speedBytesPerSecond = 0,
                        allocation = null,
                        connections = null,
                        connectionSpeedsBytesPerSecond = null,
                    ),
            )
        }

    override fun submit(
        content: ContentSnapshot,
        release: ReleaseSnapshot,
        destinationId: UUID,
    ): DownloadRequest {
        val settings = requireNotNull(configuration.settings()) { "Complete application setup" }
        val metadata = discovery.details(content.identity.copy(videoId = null))
        val normalized =
            if (content.category == MediaCategory.MOVIE) {
                require(metadata.identity.resourceType == "movie") { "Select one movie" }
                ContentSnapshot(metadata.identity, MediaCategory.MOVIE, metadata.title, metadata.year, posterUrl = metadata.posterUrl)
            } else {
                val video =
                    metadata.videos.singleOrNull { it.id == content.identity.videoId }
                        ?: throw DownloadFailure("Select one current metadata episode")
                ContentSnapshot(
                    metadata.identity.copy(videoId = video.id),
                    content.category,
                    metadata.title,
                    metadata.year,
                    video.season,
                    video.episode,
                    video.title,
                    metadata.posterUrl,
                )
            }
        val source =
            configuration.searchAddons().singleOrNull {
                it.id == release.provenance.addonId &&
                    it.enabled &&
                    it.revision == release.provenance.addonRevision
            }
                ?: throw DownloadFailure("Release source changed; query releases again")
        // Accept only references returned by the configured source for this content.
        val serverRelease =
            discovery.releases(normalized).firstOrNull { it.addonId == source.id }?.releases?.firstOrNull {
                it.reference == release.reference &&
                    it.provenance.resultPosition == release.provenance.resultPosition
            }
                ?: throw DownloadFailure("Selected release is no longer available; query releases again")
        require(serverRelease.reference.type == ReleaseReferenceType.PROVIDER_HTTP) { "KZ.ADDON_HTTP_REQUIRED" }
        val category = settings.organization.categories.getValue(normalized.category)
        val destination =
            requireNotNull(
                category.destinations.singleOrNull { it.id == destinationId },
            ) { "Select a registered destination for this category" }
        require(Files.isDirectory(Path.of(destination.path)) && Files.isWritable(Path.of(destination.path))) {
            "Selected destination is unavailable or not writable"
        }
        finalizer.checkStorage(destination.path)
        val stagingRoot =
            aria2Properties.applicationStagingRoot.takeIf(String::isNotBlank)
                ?: Path
                    .of(storage.root)
                    .toAbsolutePath()
                    .normalize()
                    .resolve(".dmdl")
                    .toString()
        val snapshot =
            OrganizationSnapshot(
                settings.organization.revision,
                Path
                    .of(destination.path)
                    .toAbsolutePath()
                    .normalize()
                    .toString(),
                category,
                destination.id,
                destination.name,
                Path
                    .of(stagingRoot)
                    .toAbsolutePath()
                    .normalize()
                    .toString(),
            )
        OrganizationRenderer().render(snapshot, normalized, "Example.Release.mkv")
        val now = Instant.now()
        val request =
            DownloadRequest(
                UUID.randomUUID(),
                normalized,
                serverRelease,
                serverRelease.provenance.boundProvider,
                snapshot,
                createdAt = now,
                updatedAt = now,
            )
        store.insert(request)
        drain()
        return store.find(request.id) ?: request
    }

    override fun control(
        id: UUID,
        generation: Long,
        action: DownloadControl,
    ): DownloadRequest {
        val before = store.find(id) ?: throw DownloadFailure("Download request not found")
        val state =
            when (action) {
                DownloadControl.PAUSE -> DownloadState.PAUSED
                DownloadControl.CANCEL -> DownloadState.CANCELLED
                else -> DownloadState.QUEUED
            }
        if (action == DownloadControl.RESUME) require(before.state == DownloadState.PAUSED) { "Only paused downloads can resume" }
        if (action == DownloadControl.RETRY) require(before.state == DownloadState.FAILED) { "Only failed downloads can retry" }
        if (state == DownloadState.QUEUED) {
            require(before.release.reference.type == ReleaseReferenceType.PROVIDER_HTTP) { "KZ.ADDON_HTTP_REQUIRED" }
        }
        val changed =
            store.changeState(id, generation, state) ?: throw DownloadFailure("Download state changed; refresh before applying this action")
        if (state == DownloadState.CANCELLED) speedHistory.delete(id)
        if (state == DownloadState.PAUSED) {
            before.execution.aria2Gid?.let { gid ->
                runCatching {
                    withTransferLock(id) {
                        val current = store.find(id)
                        if (current?.state == state && current.execution.generation == changed.execution.generation) aria2.pause(gid)
                    }
                }
            }
        }
        drain()
        return find(id) ?: changed
    }

    override fun reconcileStartup() {
        store.history().filter { it.state.reservesCapacity }.forEach { request ->
            store.recoverOwnership(request.id, request.execution.generation)
        }
        drain()
    }

    override fun drain() {
        val settings = configuration.settings() ?: return
        store.history().filter { it.state == DownloadState.CANCELLED && cancellationNeedsCleanup(it) }.forEach(
            ::cleanupCancellation,
        )
        store.history().filter { it.state == DownloadState.COMPLETED }.forEach { runCatching { finalizer.cleanupCompleted(it) } }
        val tuning = settings.maxConcurrentDownloads to settings.transfer
        if (appliedTuning != tuning) {
            runCatching {
                aria2.changeGlobalMaxConcurrentDownloads(settings.maxConcurrentDownloads, settings.transfer.diskCacheMib)
                store
                    .history()
                    .filter {
                        it.state == DownloadState.TRANSFERRING
                    }.forEach { request ->
                        withTransferLock(request.id) {
                            val current = store.find(request.id)
                            if (current?.state != DownloadState.TRANSFERRING ||
                                current.execution.generation != request.execution.generation
                            ) {
                                return@withTransferLock
                            }
                            current.execution.aria2Gid?.let {
                                aria2.changeOptions(
                                    it,
                                    effectiveTransferOptions(current, settings.transfer),
                                )
                            }
                        }
                    }
                appliedTuning = tuning
            }
        }
        store
            .history()
            .filter {
                it.state.reservesCapacity
            }.forEach { runCatching { producer.publish(DownloadWakeUp(it.id, it.execution.generation)) } }
        while (true) {
            val request = store.reserveNext(owner) ?: break
            runCatching { producer.publish(DownloadWakeUp(request.id, request.execution.generation)) }
        }
        // Also reconcile a control action that raced daemon task creation.
        store.history().filter { it.state == DownloadState.PAUSED }.forEach { request ->
            request.execution.aria2Gid?.let { gid ->
                runCatching {
                    withTransferLock(request.id) {
                        val current = store.find(request.id)
                        if (current?.state != DownloadState.PAUSED ||
                            current.execution.generation != request.execution.generation
                        ) {
                            return@withTransferLock
                        }
                        val status = aria2.findStatus(gid)
                        if (status?.state in setOf(Aria2TransferState.ACTIVE, Aria2TransferState.WAITING)) aria2.pause(gid)
                    }
                }
            }
        }
    }

    override fun execute(message: DownloadWakeUp) {
        if (!store.startOnce(message.requestId, message.generation)) return
        var request = store.find(message.requestId) ?: return

        fun save(updated: DownloadRequest) {
            val stamped =
                if (updated.state == DownloadState.TRANSFERRING && updated.startedAt == null) {
                    updated.copy(startedAt = clock.instant())
                } else {
                    updated
                }
            if (!store.checkpoint(stamped.copy(updatedAt = Instant.now()))) throw DownloadFailure("Execution ownership changed")
            request = stamped
            if (stamped.state == DownloadState.COMPLETED) speedHistory.delete(stamped.id)
        }
        try {
            require(request.release.reference.type == ReleaseReferenceType.PROVIDER_HTTP) { "KZ.ADDON_HTTP_REQUIRED" }
            if (request.execution.placement != PlacementState.NONE || request.state == DownloadState.FINALIZING) {
                val placed = finalizer.finalize(request, ::save)
                save(placed.copy(state = DownloadState.COMPLETED, completedAt = Instant.now()))
                return
            }
            val root = Path.of(request.organization.stagingRoot)
            val staging =
                root
                    .resolve(request.id.toString())
                    .toAbsolutePath()
                    .normalize()
            val gid = request.execution.aria2Gid ?: Aria2Gid.from(request.id)
            var options = effectiveTransferOptions(request, requireNotNull(configuration.settings()).transfer)

            val link = request.release.reference.value
            val existing = findTransfer(gid)
            val applicationStaging = root
            require(staging.startsWith(applicationStaging)) { "Request staging must be under the application aria2 staging root" }
            val daemonDirectory =
                Path
                    .of(
                        aria2Properties.daemonStagingRoot,
                    ).resolve(applicationStaging.relativize(staging))
                    .toString()
                    .replace('\\', '/')
            withTransferLock(request.id) {
                if (!owned(request)) return
                OrganizationRenderer().validateParents(root, staging)
                Files.createDirectories(staging)
                save(request.copy(state = DownloadState.TRANSFERRING, execution = request.execution.copy(aria2Gid = gid)))
                if (existing == null) {
                    aria2.addUri(link, gid, daemonDirectory, options)
                } else if (existing.state in setOf(Aria2TransferState.ERROR, Aria2TransferState.REMOVED)) {
                    aria2.removeDownloadResult(gid)
                    aria2.addUri(link, gid, daemonDirectory, options)
                } else if (existing.state == Aria2TransferState.PAUSED) {
                    aria2.changeOptions(gid, options)
                    aria2.unpause(gid)
                }
            }
            var lastWrite = 0L
            // Each execution starts a fresh sampling window, so a restart loses at most one interval.
            var lastSample = clock.millis()
            while (owned(request)) {
                val status = findTransfer(gid)
                if (status == null) {
                    withTransferLock(request.id) {
                        if (!owned(request)) return
                        aria2.addUri(link, gid, daemonDirectory, options)
                    }
                    Thread.sleep(1000)
                    continue
                }
                val live =
                    DownloadProgress(
                        status.completedLength,
                        status.totalLength.takeIf { it > 0 },
                        status.downloadSpeed,
                        if (status.state == Aria2TransferState.ACTIVE && status.completedLength == 0L) allocation?.find(gid) else null,
                        connections = status.connections.takeIf { status.state == Aria2TransferState.ACTIVE },
                        connectionSpeedsBytesPerSecond = connectionSpeeds(status),
                    )
                progress.publish(request.id, request.execution.generation, live)
                if (status.state == Aria2TransferState.ERROR) {
                    save(request.copy(progress = live))
                    if (options.fileAllocation == Aria2FileAllocation.FALLOC && unsupportedFallocate(status)) {
                        withTransferLock(request.id) {
                            if (!owned(request)) return
                            options = options.copy(fileAllocation = Aria2FileAllocation.PREALLOC)
                            save(request.copy(execution = request.execution.copy(fileAllocationOverride = Aria2FileAllocation.PREALLOC)))
                            aria2.removeDownloadResult(gid)
                            aria2.addUri(link, gid, daemonDirectory, options)
                        }
                    } else {
                        throw DownloadFailure(transferFailure(status))
                    }
                } else if (status.state == Aria2TransferState.COMPLETE) {
                    require(status.files.size == 1) { "Expected exactly one completed output file" }
                    val daemonRoot = Path.of(aria2Properties.daemonStagingRoot).normalize()
                    val daemonFile = Path.of(status.files.single().path).normalize()
                    require(daemonFile.startsWith(daemonRoot)) { "aria2 output escaped the configured daemon staging root" }
                    val local = applicationStaging.resolve(daemonRoot.relativize(daemonFile)).normalize()
                    require(local.startsWith(staging)) { "aria2 output does not belong to this request" }
                    save(
                        request.copy(
                            state = DownloadState.FINALIZING,
                            progress = live,
                            execution =
                                request.execution.copy(
                                    stagingPath = local.toString(),
                                    aria2SessionId = aria2.sessionId(),
                                    originalFileName = request.execution.originalFileName ?: local.fileName.toString(),
                                ),
                        ),
                    )
                    val placed = finalizer.finalize(request, ::save)
                    save(placed.copy(state = DownloadState.COMPLETED, completedAt = Instant.now()))
                    return
                } else if (status.state in
                    setOf(Aria2TransferState.REMOVED, Aria2TransferState.UNKNOWN)
                ) {
                    throw DownloadFailure("aria2 task is unavailable; retry the request")
                }
                if (status.state == Aria2TransferState.ACTIVE && clock.millis() - lastSample >= SPEED_SAMPLE_INTERVAL.toMillis()) {
                    lastSample = clock.millis()
                    speedHistory.append(
                        request.id,
                        SpeedSample(
                            clock.instant(),
                            if (live.allocation != null) 0 else live.speedBytesPerSecond,
                            live.connections ?: 0,
                        ),
                    )
                }
                if (System.currentTimeMillis() - lastWrite >= aria2Properties.progressPublicationInterval.toMillis().coerceAtLeast(1000)) {
                    save(request.copy(progress = live))
                    lastWrite = System.currentTimeMillis()
                }
                Thread.sleep(aria2Properties.pollInterval.toMillis().coerceAtLeast(100))
            }
        } catch (error: Exception) {
            if (error is InterruptedException || Thread.currentThread().isInterrupted) {
                Thread.currentThread().interrupt()
                return
            }
            if (owned(
                    request,
                )
            ) {
                store.checkpoint(request.copy(state = DownloadState.FAILED, error = safeError(error), updatedAt = Instant.now()))
            }
        }
    }

    private fun connectionSpeeds(status: Aria2TransferStatus): List<Long>? {
        if (status.state != Aria2TransferState.ACTIVE) return null
        if (status.connections == 0) return emptyList()
        return try {
            aria2.connectionSpeeds(status.gid)
        } catch (error: Exception) {
            if (error is InterruptedException || Thread.currentThread().isInterrupted) throw error
            null
        }
    }

    private fun effectiveTransferOptions(
        request: DownloadRequest,
        options: Aria2TransferOptions,
    ): Aria2TransferOptions =
        if (options.fileAllocation == Aria2FileAllocation.FALLOC && request.execution.fileAllocationOverride != null) {
            options.copy(fileAllocation = request.execution.fileAllocationOverride)
        } else {
            options
        }

    private fun unsupportedFallocate(status: Aria2TransferStatus): Boolean =
        status.errorCode == "17" && status.errorMessage?.let {
            it.contains("fallocate", ignoreCase = true) &&
                (it.contains("not supported", ignoreCase = true) || it.contains("not implemented", ignoreCase = true))
        } == true

    private fun transferFailure(status: Aria2TransferStatus): String =
        when (status.errorCode) {
            "9", "13", "14", "15", "16", "17", "18" -> "KZ.ARIA2_STORAGE_FAILED"
            "5" -> "KZ.ARIA2_SPEED_LIMIT"
            "2", "6", "19" -> "KZ.ARIA2_NETWORK_FAILED"
            else -> "KZ.HTTP_LINK_FAILED"
        }

    private fun findTransfer(gid: String): Aria2TransferStatus? {
        repeat(10) { attempt ->
            try {
                return aria2.findStatus(gid)
            } catch (error: Exception) {
                if (error is InterruptedException || Thread.currentThread().isInterrupted) throw error
                if (attempt == 9) throw DownloadFailure("aria2 is unavailable; check the daemon and retry this request")
                Thread.sleep(1000)
            }
        }
        throw DownloadFailure("aria2 is unavailable")
    }

    private fun cleanupCancellation(request: DownloadRequest) {
        try {
            withTransferLock(request.id) {
                val current = store.find(request.id) ?: return
                if (current.state != DownloadState.CANCELLED || !cancellationNeedsCleanup(current)) return
                current.execution.aria2Gid?.let(::stopCancelledTransfer)
                finalizer.cleanupCancelled(current)
                if (mappedStagingRoot() != Path.of(current.organization.stagingRoot).toAbsolutePath().normalize()) {
                    finalizer.cleanupCancelled(
                        current.copy(
                            organization =
                                current.organization.copy(
                                    stagingRoot = mappedStagingRoot().toString(),
                                ),
                            execution = current.execution.copy(placementTemporaryPath = null),
                        ),
                    )
                }
                store.recordCancellationCleanup(current.id, current.execution.generation, null)
            }
        } catch (error: Exception) {
            if (error is InterruptedException || Thread.currentThread().isInterrupted) {
                Thread.currentThread().interrupt()
                return
            }
            store.recordCancellationCleanup(
                request.id,
                request.execution.generation,
                "KZ.CANCEL_CLEANUP_PENDING",
            )
        }
    }

    private fun mappedStagingRoot(): Path =
        Path
            .of(
                aria2Properties.applicationStagingRoot.takeIf(String::isNotBlank)
                    ?: Path.of(storage.root).resolve(".dmdl").toString(),
            ).toAbsolutePath()
            .normalize()

    private fun cancellationNeedsCleanup(request: DownloadRequest): Boolean =
        !request.execution.cancellationCleanupCompleted || Files.exists(mappedStagingRoot().resolve(request.id.toString()))

    private fun stopCancelledTransfer(gid: String) {
        repeat(20) { attempt ->
            val status = aria2.findStatus(gid) ?: return
            if (status.state in setOf(Aria2TransferState.REMOVED, Aria2TransferState.ERROR, Aria2TransferState.COMPLETE)) {
                aria2.removeDownloadResult(gid)
                return
            }
            if (attempt == 0) aria2.forceRemove(gid)
            Thread.sleep(50)
        }
        throw DownloadFailure("aria2 has not released the cancelled task")
    }

    private inline fun <T> withTransferLock(
        id: UUID,
        action: () -> T,
    ): T = transferLocks[(id.hashCode() and Int.MAX_VALUE) % transferLocks.size].withLock(action)

    private fun owned(request: DownloadRequest): Boolean =
        store.find(request.id)?.let {
            it.execution.generation == request.execution.generation &&
                it.execution.owner == request.execution.owner &&
                it.state.reservesCapacity
        } ==
            true

    private fun safeError(error: Exception): String =
        when (error) {
            is DownloadFailure, is IllegalArgumentException -> error.message ?: "Download failed validation"
            else -> "Download could not continue; check storage, aria2 and the addon link before retrying"
        }

    private companion object {
        val SPEED_SAMPLE_INTERVAL: Duration = Duration.ofSeconds(30)
    }
}
