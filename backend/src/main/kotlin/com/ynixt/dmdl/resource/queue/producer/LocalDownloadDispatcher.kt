package com.ynixt.dmdl.resource.queue.producer

import com.ynixt.dmdl.domain.model.DownloadWakeUp
import com.ynixt.dmdl.domain.queue.producer.DownloadWakeUpProducer
import com.ynixt.dmdl.domain.service.DownloadService
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Advisory local wake-ups; SQLite owns eligibility, capacity and execution generations. */
@Component
class LocalDownloadDispatcher(
    private val downloads: ObjectProvider<DownloadService>,
) : DownloadWakeUpProducer {
    private val workers = Executors.newVirtualThreadPerTaskExecutor()
    private val running = ConcurrentHashMap.newKeySet<DownloadWakeUp>()

    override fun publish(message: DownloadWakeUp) {
        if (!running.add(message)) return
        try {
            workers.submit {
                try {
                    downloads.getObject().execute(message)
                } finally {
                    running.remove(message)
                }
            }
        } catch (error: RuntimeException) {
            running.remove(message)
            throw error
        }
    }

    @PreDestroy
    fun stop() {
        workers.shutdownNow()
    }
}
