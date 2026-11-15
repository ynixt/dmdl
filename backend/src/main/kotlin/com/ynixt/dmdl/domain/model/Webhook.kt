package com.ynixt.dmdl.domain.model

import java.time.Instant
import java.util.UUID

/** Automatic download events a webhook can subscribe to; [key] is the public name used in templates and the API. */
enum class WebhookEvent(
    val key: String,
) {
    REQUESTED("download.requested"),
    TRANSFERRING("download.transferring"),
    NEEDS_ACTION("download.needs_action"),
    PAUSED("download.paused"),
    FAILED("download.failed"),
    CANCELLED("download.cancelled"),
    COMPLETED("download.completed"),
    ;

    companion object {
        fun fromKey(key: String): WebhookEvent? = entries.singleOrNull { it.key == key }

        /**
         * The event a persisted state transition emits. Re-entering QUEUED after a resume or retry, PREPARING and
         * FINALIZING emit nothing; only creation emits [REQUESTED].
         */
        fun forTransition(
            state: DownloadState,
            created: Boolean,
        ): WebhookEvent? =
            when {
                created -> REQUESTED
                state == DownloadState.TRANSFERRING -> TRANSFERRING
                state == DownloadState.AWAITING_FILE_SELECTION -> NEEDS_ACTION
                state == DownloadState.PAUSED -> PAUSED
                state == DownloadState.FAILED -> FAILED
                state == DownloadState.CANCELLED -> CANCELLED
                state == DownloadState.COMPLETED -> COMPLETED
                else -> null
            }
    }
}

enum class WebhookBodyMode { NONE, TEMPLATE }

enum class DeliveryTrigger { AUTOMATIC, MANUAL }

enum class DeliveryState { PENDING, SENT, FAILED }

data class WebhookHeader(
    val name: String,
    val value: String,
) {
    override fun toString(): String = "WebhookHeader(name=$name, value=[redacted])"
}

data class Webhook(
    val id: UUID,
    val name: String,
    val method: String,
    val url: String,
    val headers: List<WebhookHeader> = emptyList(),
    val bodyMode: WebhookBodyMode = WebhookBodyMode.NONE,
    val body: String? = null,
    val events: Set<WebhookEvent> = emptySet(),
    val manual: Boolean = false,
    val enabled: Boolean = true,
) {
    override fun toString(): String = "Webhook(id=$id, name=$name, url=[redacted], headers=[redacted])"

    companion object {
        val methods = listOf("GET", "POST", "PUT", "PATCH", "DELETE")
    }
}

data class WebhookDelivery(
    val id: UUID,
    val requestId: UUID,
    val webhookId: UUID?,
    val webhookName: String,
    val trigger: DeliveryTrigger,
    /** Public event key such as `download.completed`; null for a manual dispatch. */
    val event: String?,
    val state: DeliveryState,
    val inProgress: Boolean = false,
    val attempts: Int = 0,
    val createdAt: Instant,
    val attemptedAt: Instant? = null,
    val httpStatus: Int? = null,
    val error: String? = null,
)

/** A fully rendered outbound request. It holds secrets, so it is never logged. */
data class RenderedWebhookRequest(
    val method: String,
    val url: String,
    val headers: List<WebhookHeader>,
    val body: String?,
) {
    override fun toString(): String = "RenderedWebhookRequest(method=$method, url=[redacted], headers=[redacted])"
}

/** Outcome of one HTTP attempt; [error] is a safe translatable code that never contains the URL or headers. */
data class WebhookResponse(
    val status: Int?,
    val error: String?,
) {
    val successful: Boolean get() = error == null && status != null && status in 200..299
}

/** A validation failure with a translatable [code] for one webhook [field]. */
class WebhookFailure(
    val field: String,
    val code: String,
) : RuntimeException(code)

/** Field errors keyed `webhooks.<index>.<field>`; nothing was saved. */
class WebhookListInvalid(
    val errors: Map<String, String>,
) : RuntimeException("KZ.WEBHOOKS_INVALID")

enum class WebhookActionResult { OK, NOT_FOUND, INELIGIBLE, CONFLICT }

/** Sample data and template context shared by deliveries, the preview and the connection test. */
object WebhookContext {
    fun of(
        request: DownloadRequest,
        trigger: DeliveryTrigger,
        event: WebhookEvent?,
        now: Instant = Instant.now(),
    ): Map<String, Any?> {
        val content = request.content
        val completed = request.state == DownloadState.COMPLETED || event == WebhookEvent.COMPLETED
        val download =
            linkedMapOf<String, Any?>(
                "id" to request.id.toString(),
                "state" to request.state.name,
                "title" to content.title,
                "category" to content.category.name,
                "year" to content.year,
                "season" to content.season,
                "episode" to content.episode,
                "episodeTitle" to content.episodeTitle,
                "posterUrl" to content.posterUrl,
                "provider" to request.provider?.name,
                "path" to if (completed) request.execution.finalPath else null,
                "size" to if (completed) request.execution.outputSize ?: request.progress.totalBytes else null,
                "error" to request.error,
            )
        return linkedMapOf<String, Any?>(
            "event" to event?.key,
            "trigger" to trigger.name.lowercase(),
            "manual" to (trigger == DeliveryTrigger.MANUAL),
            "timestamp" to now.toString(),
            "download" to download,
        ).apply { download.forEach { (key, value) -> if (key != "id" && key != "state") put(key, value) } }
    }

    /** Fixed example download used by validation, preview and tests: a completed movie. */
    fun sample(state: DownloadState = DownloadState.COMPLETED): DownloadRequest {
        val now = Instant.parse("2026-01-01T12:00:00Z")
        return DownloadRequest(
            UUID.fromString("00000000-0000-4000-8000-000000000001"),
            ContentSnapshot(
                ContentIdentity(UUID.fromString("00000000-0000-4000-8000-000000000002"), 1, "movie", "tt0000001"),
                MediaCategory.MOVIE,
                "Example Movie",
                year = 2026,
                posterUrl = "https://example.com/poster.jpg",
            ),
            ReleaseSnapshot(
                "Example.Movie.2026.1080p.mkv",
                ReleaseProvenance(UUID.fromString("00000000-0000-4000-8000-000000000003"), 1, "Example", 0, 0),
                ReleaseReference(ReleaseReferenceType.PROVIDER_HTTP, "https://example.com/file.mkv"),
            ),
            DebridProvider.TORBOX,
            OrganizationSnapshot(1, "/media/movies", CategoryOrganization("{title}")),
            state = state,
            execution =
                DownloadExecution(
                    finalPath =
                        if (state ==
                            DownloadState.COMPLETED
                        ) {
                            "/media/movies/Example Movie (2026)/Example Movie (2026).mkv"
                        } else {
                            null
                        },
                    outputSize = if (state == DownloadState.COMPLETED) 4_294_967_296 else null,
                    placement = if (state == DownloadState.COMPLETED) PlacementState.PLACED else PlacementState.NONE,
                ),
            error = if (state == DownloadState.FAILED) "KZ.TRANSFER_FAILED" else null,
            createdAt = now,
            updatedAt = now,
            completedAt = if (state == DownloadState.COMPLETED) now else null,
        )
    }

    fun sampleFor(event: WebhookEvent?): DownloadRequest =
        sample(
            when (event) {
                WebhookEvent.REQUESTED -> DownloadState.QUEUED
                WebhookEvent.TRANSFERRING -> DownloadState.TRANSFERRING
                WebhookEvent.NEEDS_ACTION -> DownloadState.AWAITING_FILE_SELECTION
                WebhookEvent.PAUSED -> DownloadState.PAUSED
                WebhookEvent.FAILED -> DownloadState.FAILED
                WebhookEvent.CANCELLED -> DownloadState.CANCELLED
                WebhookEvent.COMPLETED, null -> DownloadState.COMPLETED
            },
        )
}
