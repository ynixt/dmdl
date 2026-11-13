package com.ynixt.dmdl.application.job

import com.ynixt.dmdl.domain.service.DownloadService
import com.ynixt.dmdl.domain.service.NotificationService
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class DownloadReconciliationJob(
    private val downloads: DownloadService,
    private val notifications: NotificationService,
) {
    @Volatile private var ready = false

    @EventListener(ApplicationReadyEvent::class)
    fun ready() {
        downloads.reconcileStartup()
        ready = true
    }

    @Scheduled(fixedDelay = 1000)
    fun drain() {
        if (ready) runCatching { downloads.drain() }
    }

    @Scheduled(fixedDelay = 5000)
    fun notifyCompleted() {
        if (ready) runCatching { notifications.dispatchPending() }
    }
}
