package com.ynixt.dmdl.application.web.controller

import com.ynixt.dmdl.domain.model.LogFilter
import com.ynixt.dmdl.domain.model.LogLevel
import com.ynixt.dmdl.domain.model.LogPage
import com.ynixt.dmdl.domain.model.LogSource
import com.ynixt.dmdl.domain.service.ApplicationLogs
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.event.EventListener
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.session.Session
import org.springframework.session.SessionRepository
import org.springframework.session.events.SessionDestroyedEvent
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

@RestController
@RequestMapping("/api/logs")
class LogsController(
    private val logs: ApplicationLogs,
    private val sessions: SessionRepository<out Session>,
) {
    private val streams = ConcurrentHashMap<SseEmitter, String>()
    private val workers = Executors.newVirtualThreadPerTaskExecutor()

    @GetMapping
    fun history(
        @RequestParam(defaultValue = "all") source: String,
        @RequestParam(defaultValue = "") text: String,
        @RequestParam(required = false) levels: List<String>?,
        @RequestParam(required = false) before: String?,
        @RequestParam(defaultValue = "200") limit: Int,
        response: HttpServletResponse,
    ): LogPage {
        response.setHeader("Cache-Control", "no-store")
        return logs.history(filter(source, text, levels), before, limit)
    }

    @GetMapping("/events", produces = ["text/event-stream"])
    fun events(
        @RequestParam(defaultValue = "all") source: String,
        @RequestParam(defaultValue = "") text: String,
        @RequestParam(required = false) levels: List<String>?,
        @RequestParam(required = false) cursor: String?,
        @RequestHeader(value = "Last-Event-ID", required = false) lastEventId: String?,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): SseEmitter {
        response.setHeader("Cache-Control", "no-store")
        response.setHeader("X-Accel-Buffering", "no")
        val selected = filter(source, text, levels)
        val sessionId = request.getSession(false)?.id ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        // Validate the cursor before committing a streaming response.
        val first = logs.replay(selected, lastEventId ?: cursor)
        val emitter = SseEmitter(0L)
        synchronized(streams) {
            if (streams.size >= 64) throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS)
            streams[emitter] = sessionId
        }
        emitter.onCompletion { streams.remove(emitter) }
        emitter.onTimeout { streams.remove(emitter) }
        emitter.onError { streams.remove(emitter) }
        workers.submit {
            try {
                var page = first
                while (streams.containsKey(emitter) && valid(sessionId)) {
                    // Each worker sends one bounded batch synchronously. Slow clients have no delivery queue.
                    emitter.send(
                        SseEmitter
                            .event()
                            .id(page.cursor)
                            .name("logs")
                            .data(page),
                    )
                    if (!page.hasMore) Thread.sleep(500)
                    page = logs.replay(selected, page.cursor)
                }
                if (streams.containsKey(emitter) && !valid(sessionId)) {
                    emitter.send(SseEmitter.event().name("session-ended").data(emptyMap<String, String>()))
                }
            } catch (_: Exception) {
                // The client may have disconnected or the session repository may be unavailable.
            } finally {
                streams.remove(emitter)
                emitter.complete()
            }
        }
        return emitter
    }

    @Scheduled(fixedDelay = 500)
    fun checkSessions() {
        streams.forEach { (emitter, id) ->
            if (!valid(id)) {
                streams.remove(emitter)
                emitter.complete()
            }
        }
    }

    private fun valid(id: String): Boolean = runCatching { sessions.findById(id)?.isExpired == false }.getOrDefault(false)

    @EventListener
    fun sessionDestroyed(event: SessionDestroyedEvent) {
        streams.filterValues { it == event.sessionId }.keys.forEach { emitter ->
            streams.remove(emitter)
            emitter.complete()
        }
    }

    @EventListener(ContextClosedEvent::class)
    fun close() {
        streams.keys.forEach(SseEmitter::complete)
        streams.clear()
        workers.shutdownNow()
    }

    private fun filter(
        source: String,
        text: String,
        levels: List<String>?,
    ): LogFilter {
        require(text.length <= 4096) { "Invalid log text filter" }
        val selected = if (source == "all") null else LogSource.entries.find { it.name == source }
        require(source == "all" || selected != null) { "Invalid log source" }
        val normalized =
            levels
                .orEmpty()
                .filter { it.isNotEmpty() }
                .map { value ->
                    requireNotNull(LogLevel.entries.find { it.name == value }) { "Invalid log severity" }
                }.toSet()
        return LogFilter(selected, text, normalized)
    }
}
