package com.ynixt.dmdl.domain.model

import java.util.UUID

data class DiscoveryQuery(
    val resourceType: String,
    val catalogId: String,
    val query: String,
)

data class MetadataVideo(
    val id: String,
    val season: Int,
    val episode: Int,
    val title: String? = null,
)

data class MetadataDetails(
    val identity: ContentIdentity,
    val title: String,
    val year: Int? = null,
    val posterUrl: String? = null,
    val description: String? = null,
    val videos: List<MetadataVideo> = emptyList(),
)

enum class SourceOutcome { LOADING, SUCCESS, EMPTY, INCOMPATIBLE, ERROR }

data class ReleaseGroup(
    val addonId: UUID,
    val addonName: String,
    val position: Int,
    val outcome: SourceOutcome,
    val releases: List<ReleaseSnapshot> = emptyList(),
    val message: String? = null,
)

data class ReleasePage(
    val group: ReleaseGroup,
    val page: Int,
    val pageSize: Int,
    val total: Int,
    val hasNext: Boolean,
)

enum class DownloadControl { PAUSE, RESUME, CANCEL, RETRY }

/** Wake-ups are advisory; consumers must acquire the persisted generation/owner before execution. */
data class DownloadWakeUp(
    val requestId: UUID,
    val generation: Long,
)

data class DownloadStatusUpdate(
    val request: DownloadRequest,
    val notification: JellyfinNotification?,
)
