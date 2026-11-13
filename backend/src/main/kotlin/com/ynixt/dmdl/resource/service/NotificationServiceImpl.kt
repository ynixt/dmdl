package com.ynixt.dmdl.resource.service

import com.ynixt.dmdl.domain.gateway.JellyfinGateway
import com.ynixt.dmdl.domain.model.NotificationState
import com.ynixt.dmdl.domain.repository.ConfigurationStore
import com.ynixt.dmdl.domain.repository.NotificationStore
import com.ynixt.dmdl.domain.service.NotificationService
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class NotificationServiceImpl(
    private val store: NotificationStore,
    private val configuration: ConfigurationStore,
    private val jellyfin: JellyfinGateway,
) : NotificationService {
    override fun dispatchPending() {
        store.pending().forEach(::dispatch)
    }

    override fun retry(id: UUID) {
        require(store.retry(id)) { "Only a completed request with a failed or skipped notification can retry" }
        dispatch(id)
    }

    private fun dispatch(id: UUID) {
        val attempt = store.claim(id) ?: return
        val settings = configuration.settings()?.jellyfin
        if (settings?.enabled != true) {
            store.finish(id, attempt, NotificationState.SKIPPED, null)
            return
        }
        try {
            jellyfin.refresh(settings)
            store.finish(id, attempt, NotificationState.SENT, null)
        } catch (
            _: Exception,
        ) {
            store.finish(
                id,
                attempt,
                NotificationState.FAILED,
                "Jellyfin refresh failed; check the connection/token and retry notification",
            )
        }
    }
}
