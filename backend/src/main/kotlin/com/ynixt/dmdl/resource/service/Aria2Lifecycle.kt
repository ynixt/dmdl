package com.ynixt.dmdl.resource.service

import com.ynixt.dmdl.application.config.Aria2Properties
import com.ynixt.dmdl.domain.gateway.Aria2Gateway
import com.ynixt.dmdl.domain.model.Aria2RestartInProgress
import com.ynixt.dmdl.domain.model.Aria2RestartState
import com.ynixt.dmdl.domain.model.Aria2Status
import com.ynixt.dmdl.domain.repository.ConfigurationStore
import com.ynixt.dmdl.domain.service.Aria2LifecycleService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Instant

/**
 * Restarts the embedded aria2 without restarting the container, and keeps aria2's startup-only options file in
 * sync with the saved settings. The start-aria2.sh supervisor starts aria2 again only when the marker exists.
 */
@Service
class Aria2Lifecycle(
    private val aria2: Aria2Gateway,
    private val configuration: ConfigurationStore,
    private val properties: Aria2Properties,
    private val clock: Clock = Clock.systemUTC(),
    private val sleeper: (Long) -> Unit = Thread::sleep,
    private val launcher: (Runnable) -> Unit = { Thread.ofVirtual().name("aria2-restart").start(it) },
) : Aria2LifecycleService {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile private var state = Aria2RestartState.IDLE

    @Volatile private var startedAt: Instant? = null

    @Volatile private var writtenOptions: String? = null

    @Volatile private var completedRestarts: Long = 0

    /** Grows on every completed restart so callers can re-apply global options aria2 lost. */
    override fun restarts(): Long = completedRestarts

    override fun restarting(): Boolean = state == Aria2RestartState.RESTARTING

    override fun status(): Aria2Status {
        val saved = savedAutoSaveInterval()
        val running =
            if (restarting()) null else runCatching { aria2.globalOption("auto-save-interval")?.toIntOrNull() }.getOrNull()
        return Aria2Status(state, startedAt, running, saved, running != null && running != saved)
    }

    override fun restart() {
        synchronized(this) {
            if (state == Aria2RestartState.RESTARTING) throw Aria2RestartInProgress()
            state = Aria2RestartState.RESTARTING
            startedAt = clock.instant()
        }
        launcher.invoke(Runnable(::performRestart))
    }

    private fun performRestart() {
        val marker = properties.restartMarkerFile.takeIf(String::isNotBlank)?.let(Path::of)
        try {
            syncStartupOptions()
            val before = runCatching { aria2.sessionId() }.getOrNull()
            marker?.let {
                Files.createDirectories(it.toAbsolutePath().parent)
                Files.writeString(it, "restart\n")
            }
            try {
                aria2.shutdown()
            } catch (error: Exception) {
                log.warn("aria2 did not accept a clean shutdown; forcing it: {}", error.message)
                runCatching { aria2.forceShutdown() }
            }
            val deadline = clock.millis() + properties.restartTimeout.toMillis()
            while (clock.millis() < deadline) {
                sleeper(1000)
                val session = runCatching { aria2.sessionId() }.getOrNull()
                if (session != null && session != before) {
                    completedRestarts++
                    state = Aria2RestartState.IDLE
                    log.info("aria2 restarted")
                    return
                }
            }
            log.warn("aria2 did not come back within {} after a restart request", properties.restartTimeout)
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            log.warn("aria2 restart failed: {}", error.message)
        }
        marker?.let { runCatching { Files.deleteIfExists(it) } }
        state = Aria2RestartState.FAILED
    }

    /** Writes the startup-only options file when it differs from the saved settings. Cheap when nothing changed. */
    override fun syncStartupOptions() {
        val file = properties.startupOptionsFile.takeIf(String::isNotBlank)?.let(Path::of) ?: return
        val content = "auto-save-interval=${savedAutoSaveInterval()}\n"
        if (content == writtenOptions && Files.exists(file)) return
        Files.createDirectories(file.toAbsolutePath().parent)
        val temporary = file.resolveSibling("${file.fileName}.tmp")
        Files.writeString(temporary, content)
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        writtenOptions = content
    }

    private fun savedAutoSaveInterval(): Int = configuration.settings()?.transfer?.autoSaveIntervalSeconds ?: DEFAULT_AUTO_SAVE_INTERVAL

    companion object {
        const val DEFAULT_AUTO_SAVE_INTERVAL = 60
    }
}
