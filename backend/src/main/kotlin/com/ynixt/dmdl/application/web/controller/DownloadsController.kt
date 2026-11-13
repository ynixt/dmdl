package com.ynixt.dmdl.application.web.controller

import com.ynixt.dmdl.application.web.dto.CreateDownloadRequest
import com.ynixt.dmdl.application.web.dto.StrictRequest
import com.ynixt.dmdl.domain.model.ContentIdentity
import com.ynixt.dmdl.domain.model.ContentSnapshot
import com.ynixt.dmdl.domain.model.DiscoveryQuery
import com.ynixt.dmdl.domain.model.DownloadControl
import com.ynixt.dmdl.domain.model.DownloadStatusUpdate
import com.ynixt.dmdl.domain.model.MediaCategory
import com.ynixt.dmdl.domain.model.ReleaseGroup
import com.ynixt.dmdl.domain.model.ReleasePage
import com.ynixt.dmdl.domain.model.StreamBadgeRules
import com.ynixt.dmdl.domain.repository.ConfigurationStore
import com.ynixt.dmdl.domain.repository.DownloadStore
import com.ynixt.dmdl.domain.repository.NotificationStore
import com.ynixt.dmdl.domain.service.DiscoveryService
import com.ynixt.dmdl.domain.service.DownloadService
import com.ynixt.dmdl.domain.service.NotificationService
import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.event.EventListener
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class ControlInput(
    val generation: Long,
    val action: DownloadControl,
) : StrictRequest()

@RestController
@RequestMapping("/api")
class DownloadsController(
    private val downloads: DownloadService,
    private val store: DownloadStore,
    private val notifications: NotificationStore,
    private val notificationService: NotificationService,
    private val discovery: DiscoveryService,
    private val configuration: ConfigurationStore,
) {
    private val updates = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory())
    private val emitters = ConcurrentHashMap.newKeySet<SseEmitter>()

    @EventListener(ContextClosedEvent::class)
    fun closeStreams() {
        emitters.forEach(SseEmitter::complete)
        updates.shutdownNow()
    }

    @PostMapping("/discovery/search")
    fun search(
        @RequestBody query: DiscoveryQuery,
    ) = discovery.search(query)

    @PostMapping("/discovery/details")
    fun details(
        @RequestBody identity: ContentIdentity,
    ) = discovery.details(identity)

    @GetMapping("/discovery/content")
    fun content(
        @RequestParam resourceType: String,
        @RequestParam contentId: String,
        @RequestParam(required = false) season: Int? = null,
        @RequestParam(required = false) episode: Int? = null,
        @RequestParam(required = false) category: MediaCategory? = null,
    ) = discovery.content(resourceType, contentId, season, episode, category)

    @PostMapping("/discovery/releases/page")
    fun releasePage(
        @RequestBody content: ContentSnapshot,
        @RequestParam addonId: UUID,
        @RequestParam(defaultValue = "1") page: Int = 1,
        @RequestParam(defaultValue = "false") forceRefresh: Boolean = false,
    ): ReleasePage {
        val result = discovery.releasePage(content, addonId, page, forceRefresh)
        val rules = StreamBadgeRules.parse(configuration.settings()?.streamBadgeJson ?: StreamBadgeRules.defaultJson)
        return result.copy(
            group =
                result.group.copy(
                    releases =
                        result.group.releases.map { release ->
                            release.copy(badges = rules.match(release))
                        },
                ),
        )
    }

    @PostMapping("/discovery/releases")
    fun releases(
        @RequestBody content: ContentSnapshot,
        @RequestParam(defaultValue = "false") forceRefresh: Boolean = false,
    ): List<ReleaseGroup> {
        val rules = StreamBadgeRules.parse(configuration.settings()?.streamBadgeJson ?: StreamBadgeRules.defaultJson)
        return discovery.releases(content, forceRefresh).map { group ->
            group.copy(
                releases =
                    group.releases.map { release ->
                        release.copy(badges = rules.match(release))
                    },
            )
        }
    }

    @PostMapping("/downloads")
    fun create(
        @RequestBody input: CreateDownloadRequest,
    ) = downloads.submit(input.content, input.release, input.destinationId)

    @GetMapping("/downloads")
    fun list() = downloads.history().map { DownloadStatusUpdate(it, notifications.find(it.id)) }

    @DeleteMapping("/downloads/completed")
    fun removeFinished() = downloads.removeFinishedFromHistory()

    @DeleteMapping("/downloads/{id}")
    fun removeFinished(
        @PathVariable id: UUID,
    ) = downloads.removeFinishedFromHistory(id)

    @GetMapping("/downloads/{id}")
    fun get(
        @PathVariable id: UUID,
    ) = DownloadStatusUpdate(requireNotNull(downloads.find(id)) { "Download not found" }, notifications.find(id))

    @GetMapping("/downloads/{id}/history")
    fun history(
        @PathVariable id: UUID,
    ) = store.events(id)

    @PostMapping("/downloads/{id}/control")
    fun control(
        @PathVariable id: UUID,
        @RequestBody input: ControlInput,
    ) = downloads.control(id, input.generation, input.action)

    @PostMapping("/downloads/{id}/notification/retry")
    fun retryNotification(
        @PathVariable id: UUID,
    ) {
        notificationService.retry(id)
    }

    @GetMapping("/downloads/events", produces = ["text/event-stream"])
    fun events(): SseEmitter {
        val emitter = SseEmitter(0L)
        emitters.add(emitter)
        val task =
            updates.scheduleAtFixedRate({
                try {
                    emitter.send(SseEmitter.event().name("downloads").data(list()))
                } catch (
                    _: Exception,
                ) {
                    emitter.complete()
                }
            }, 0, 1, TimeUnit.SECONDS)
        emitter.onCompletion {
            task.cancel(false)
            emitters.remove(emitter)
        }
        emitter.onTimeout {
            task.cancel(false)
            emitters.remove(emitter)
        }
        emitter.onError {
            task.cancel(false)
            emitters.remove(emitter)
        }
        return emitter
    }
}
