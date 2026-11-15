package com.ynixt.dmdl.application.job

import com.ynixt.dmdl.domain.service.Aria2LifecycleService
import com.ynixt.dmdl.domain.service.DownloadService
import com.ynixt.dmdl.domain.service.WebhookService
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class DownloadReconciliationJob(
    private val downloads: DownloadService,
    private val webhooks: WebhookService,
    private val aria2: Aria2LifecycleService,
) {
    @Volatile private var ready = false

    @EventListener(ApplicationReadyEvent::class)
    fun ready() {
        // Attempts a previous process left in progress are failed before anything is sent again.
        webhooks.recoverInterrupted()
        runCatching { aria2.syncStartupOptions() }
        downloads.reconcileStartup()
        ready = true
    }

    @Scheduled(fixedDelay = 1000)
    fun drain() {
        if (!ready) return
        // Covers settings saved from onboarding and settings alike; a no-op when nothing changed.
        runCatching { aria2.syncStartupOptions() }
        runCatching { downloads.drain() }
    }

    /** Wakes the webhook sender; the sending itself runs on its own thread. */
    @Scheduled(fixedDelay = 2000)
    fun dispatchWebhooks() {
        if (ready) runCatching { webhooks.dispatchPending() }
    }
}
