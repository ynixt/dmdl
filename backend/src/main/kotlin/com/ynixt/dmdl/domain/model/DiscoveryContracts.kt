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
    val thumbnail: String? = null,
    val overview: String? = null,
    val released: String? = null,
    /** Episode score as the addon sent it; its source is never known. */
    val rating: String? = null,
)

/** Where a title's rating comes from, as stated by the addon's `imdb` link; `null` when unknown. */
enum class RatingSource { IMDB, KITSU }

data class MetadataDetails(
    val identity: ContentIdentity,
    val title: String,
    val year: Int? = null,
    val posterUrl: String? = null,
    val description: String? = null,
    val videos: List<MetadataVideo> = emptyList(),
    val background: String? = null,
    /** Presentation classification; addon calls keep using [ContentIdentity.resourceType]. */
    val category: MediaCategory = localCategory(identity),
    val seasonPosters: Map<Int, String> = emptyMap(),
    val rating: String? = null,
    val ratingSource: RatingSource? = null,
)

/** Classification from the metadata alone: a Kitsu id means anime because Kitsu only indexes anime. */
fun localCategory(identity: ContentIdentity): MediaCategory =
    when {
        identity.resourceType == "movie" -> MediaCategory.MOVIE
        hasKitsuId(identity) -> MediaCategory.ANIME
        identity.resourceType == "anime" -> MediaCategory.ANIME
        else -> MediaCategory.SERIES
    }

fun hasKitsuId(identity: ContentIdentity): Boolean = identity.contentId.startsWith("kitsu:") || "kitsu" in identity.mappedIds

/** Ids Kitsu can map back to an anime, in lookup order. */
val ANIME_INDEX_PROVIDERS = listOf("mal", "anilist", "anidb", "tvdb")

enum class AnimeVerdict { ANIME, NOT_ANIME, UNKNOWN }

enum class MediaKind { MOVIE, SHOW }

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
    val deliveries: List<WebhookDelivery> = emptyList(),
)
