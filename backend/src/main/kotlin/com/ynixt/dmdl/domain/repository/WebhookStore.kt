package com.ynixt.dmdl.domain.repository

import com.ynixt.dmdl.domain.model.Webhook
import com.ynixt.dmdl.domain.model.WebhookActionResult
import com.ynixt.dmdl.domain.model.WebhookDelivery
import java.util.UUID

interface WebhookStore {
    /** Webhooks in display order. */
    fun list(): List<Webhook>

    fun find(id: UUID): Webhook?

    /** Atomically makes the stored list equal to [webhooks]: existing ids are updated, missing ids deleted, new ids created. */
    fun replace(webhooks: List<Webhook>)
}

interface WebhookDeliveryStore {
    fun forRequest(requestId: UUID): List<WebhookDelivery>

    /** Deliveries of every download still visible in history, grouped by download, each in creation order. */
    fun forVisibleRequests(): Map<UUID, List<WebhookDelivery>>

    /** Pending deliveries that are not being attempted, in creation order. */
    fun pending(): List<WebhookDelivery>

    /** Starts an attempt on a pending delivery and returns its attempt number, or null when it is not claimable. */
    fun claim(id: UUID): Int?

    /** Records the outcome of [attempt]; a stale or unknown attempt changes nothing. */
    fun finish(
        id: UUID,
        attempt: Int,
        sent: Boolean,
        httpStatus: Int?,
        error: String?,
    ): Boolean

    /** Queues one manual delivery per webhook for a download. All ids must be enabled manual webhooks. */
    fun dispatch(
        requestId: UUID,
        webhookIds: List<UUID>,
    ): WebhookActionResult

    /** Queues a failed automatic delivery again. */
    fun resend(
        requestId: UUID,
        deliveryId: UUID,
    ): WebhookActionResult

    /** Startup only: fails attempts a previous process left in progress. Returns how many were recovered. */
    fun recoverInterrupted(error: String): Int
}
