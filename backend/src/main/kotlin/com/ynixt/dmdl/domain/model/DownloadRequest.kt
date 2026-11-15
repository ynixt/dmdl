package com.ynixt.dmdl.domain.model

import com.ynixt.dmdl.domain.enum.Aria2FileAllocation
import java.time.Instant
import java.util.UUID

enum class DebridProvider { TORBOX, ALL_DEBRID, REAL_DEBRID }

enum class MediaCategory { MOVIE, SERIES, ANIME }

enum class DownloadState {
    QUEUED,
    PREPARING,
    AWAITING_FILE_SELECTION,
    TRANSFERRING,
    FINALIZING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED,
    ;

    val reservesCapacity: Boolean get() = this in setOf(PREPARING, TRANSFERRING, FINALIZING)
}

enum class NotificationState { PENDING, SENT, FAILED, SKIPPED }

enum class PlacementState { NONE, PLANNED, PLACED }

/** Source identities are opaque; neither IMDb nor catalog membership is required. */
data class ContentIdentity(
    val sourceId: UUID,
    val sourceRevision: Long,
    val resourceType: String,
    val contentId: String,
    val videoId: String? = null,
    val mappedIds: Map<String, String> = emptyMap(),
) {
    init {
        require(sourceRevision > 0)
        require(resourceType.isNotBlank() && contentId.isNotBlank())
        require(videoId == null || videoId.isNotBlank())
        require(mappedIds.all { (key, value) -> key.isNotBlank() && value.isNotBlank() })
    }
}

data class ContentSnapshot(
    val identity: ContentIdentity,
    val category: MediaCategory,
    val title: String,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val posterUrl: String? = null,
) {
    init {
        require(title.isNotBlank())
        require(year == null || year in 1..9999)
        if (category == MediaCategory.MOVIE) {
            require(season == null && episode == null && identity.videoId == null) { "Select one movie" }
        } else {
            require(season != null && season >= 0 && episode != null && episode > 0 && identity.videoId != null) {
                "Select exactly one episode with its source video ID and season/episode numbers"
            }
        }
    }
}

data class ReleaseProvenance(
    val addonId: UUID,
    val addonRevision: Long,
    val addonName: String,
    val sourcePosition: Int,
    val resultPosition: Int,
    val boundProvider: DebridProvider? = null,
) {
    init {
        require(addonRevision > 0 && sourcePosition >= 0 && resultPosition >= 0 && addonName.isNotBlank())
    }
}

enum class ReleaseReferenceType { MAGNET, INFO_HASH, PROVIDER_HTTP }

data class ReleaseReference(
    val type: ReleaseReferenceType,
    val value: String,
) {
    init {
        require(
            when (type) {
                ReleaseReferenceType.MAGNET -> {
                    value.startsWith("magnet:?") && value.contains("xt=urn:btih:")
                }

                ReleaseReferenceType.INFO_HASH -> {
                    value.matches(Regex("(?:[a-fA-F0-9]{40}|[A-Z2-7a-z]{32})"))
                }

                ReleaseReferenceType.PROVIDER_HTTP -> {
                    runCatching { java.net.URI(value) }.getOrNull()?.let {
                        it.scheme in setOf("http", "https") && !it.host.isNullOrBlank() && it.userInfo == null
                    } == true
                }
            },
        ) { "Unsupported release reference" }
    }

    override fun toString(): String = "ReleaseReference(type=$type, value=[redacted])"
}

enum class DebridCacheStatus { CACHED, UNCACHED, UNKNOWN }

data class ReleaseSnapshot(
    val title: String,
    val provenance: ReleaseProvenance,
    val reference: ReleaseReference,
    val badgeText: String? = null,
    val badges: List<StreamBadge> = emptyList(),
    val cacheStatus: DebridCacheStatus = DebridCacheStatus.UNKNOWN,
) {
    init {
        require(title.isNotBlank())
    }
}

data class CategoryOrganization(
    val folderTemplate: String,
    val destinations: List<StorageDestination> = emptyList(),
    val defaultDestinationId: UUID? = null,
)

data class StorageDestination(
    val id: UUID,
    val name: String,
    val path: String,
)

data class OrganizationSnapshot(
    val revision: Long,
    val storageRoot: String,
    val category: CategoryOrganization,
    val destinationId: UUID? = null,
    val destinationName: String = "",
    val stagingRoot: String = storageRoot,
) {
    init {
        require(revision > 0 && storageRoot.isNotBlank())
    }
}

/** All external identifiers belong to this request and its selected provider. */
data class ProviderFile(
    val id: String,
    val name: String,
    val size: Long,
) {
    init {
        require(id.isNotBlank() && name.isNotBlank() && size >= 0)
    }
}

data class DownloadExecution(
    val generation: Long = 0,
    val owner: UUID? = null,
    val providerTorrentId: String? = null,
    val selectedFileId: String? = null,
    val originalFileName: String? = null,
    val availableFiles: List<ProviderFile> = emptyList(),
    val aria2Gid: String? = null,
    val aria2SessionId: String? = null,
    val stagingPath: String? = null,
    val finalPath: String? = null,
    val outputSize: Long? = null,
    val outputSha256: String? = null,
    val placementOwnershipToken: String? = null,
    val placementTemporaryPath: String? = null,
    val placement: PlacementState = PlacementState.NONE,
    val cancellationCleanupCompleted: Boolean = false,
    val fileAllocationOverride: Aria2FileAllocation? = null,
)

/** What a transferring download is doing right now; speed is only meaningful in [TRANSFERRING]. */
enum class TransferPhase {
    TRANSFERRING,
    WAITING_FOR_ARIA2,
    WRITING_TO_DISK,
    ARIA2_RESTARTING,
}

enum class DiskActivityAvailability {
    AVAILABLE,
    CGROUP_V2_MISSING,
    NO_BLOCK_STATS,
}

data class DownloadProgress(
    val completedBytes: Long = 0,
    val totalBytes: Long? = null,
    /** Observed speed: completed bytes gained over the last 10 s of wall-clock time. */
    val speedBytesPerSecond: Long = 0,
    val allocation: AllocationProgress? = null,
    val connections: Int? = null,
    /** aria2's own reported speed, kept for diagnostics. */
    val aria2SpeedBytesPerSecond: Long? = null,
    /** Observed speed over the last 60 s, used for the remaining time. */
    val averageBytesPerSecond: Long? = null,
    /** When aria2 last answered a status request for this transfer. */
    val aria2AnsweredAt: Instant? = null,
    val phase: TransferPhase? = null,
    val diskActivity: DiskActivityAvailability? = null,
    /** Container-wide bytes per second written to the staging disk. */
    val diskBytesPerSecond: Long? = null,
    /** Container-wide page-cache bytes waiting to be written to disk. */
    val pendingWriteBytes: Long? = null,
)

data class DownloadRequest(
    val id: UUID,
    val content: ContentSnapshot,
    val release: ReleaseSnapshot,
    val provider: DebridProvider?,
    val organization: OrganizationSnapshot,
    val state: DownloadState = DownloadState.QUEUED,
    val execution: DownloadExecution = DownloadExecution(),
    val progress: DownloadProgress = DownloadProgress(),
    val error: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant? = null,
    /** First time the request entered [DownloadState.TRANSFERRING]; kept across pauses and retries. */
    val startedAt: Instant? = null,
)

/** One point of a download's speed history, sampled while it transfers. */
data class SpeedSample(
    val at: Instant,
    val speedBytesPerSecond: Long,
    val connections: Int,
    /** Cumulative completed bytes; absent in samples recorded before this field existed. */
    val completedBytes: Long? = null,
    /** Cumulative container bytes written to the staging disk, when disk activity is available. */
    val diskBytes: Long? = null,
)

data class DownloadEvent(
    val id: UUID,
    val requestId: UUID,
    val generation: Long,
    val state: DownloadState,
    val occurredAt: Instant,
    val message: String? = null,
)

data class JellyfinNotification(
    val requestId: UUID,
    val state: NotificationState,
    val attempts: Int = 0,
    val attemptedAt: Instant? = null,
    val error: String? = null,
)
