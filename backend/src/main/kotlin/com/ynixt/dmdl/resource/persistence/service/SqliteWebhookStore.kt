package com.ynixt.dmdl.resource.persistence.service

import com.ynixt.dmdl.domain.model.DeliveryState
import com.ynixt.dmdl.domain.model.DeliveryTrigger
import com.ynixt.dmdl.domain.model.Webhook
import com.ynixt.dmdl.domain.model.WebhookActionResult
import com.ynixt.dmdl.domain.model.WebhookBodyMode
import com.ynixt.dmdl.domain.model.WebhookDelivery
import com.ynixt.dmdl.domain.model.WebhookEvent
import com.ynixt.dmdl.domain.model.WebhookHeader
import com.ynixt.dmdl.domain.repository.WebhookDeliveryStore
import com.ynixt.dmdl.domain.repository.WebhookStore
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

@Repository
class SqliteWebhookStore(
    private val jdbc: JdbcTemplate,
    transaction: TransactionTemplate,
    private val json: ObjectMapper,
) : WebhookStore,
    WebhookDeliveryStore {
    private val writes = BoundedSqliteWrites(transaction)

    override fun list(): List<Webhook> = jdbc.query("SELECT * FROM webhook ORDER BY display_order, name", { row, _ -> webhook(row) })

    override fun find(id: UUID): Webhook? =
        jdbc.query("SELECT * FROM webhook WHERE id = ?", { row, _ -> webhook(row) }, id.toString()).singleOrNull()

    override fun replace(webhooks: List<Webhook>) {
        writes.run {
            val keep = webhooks.map { it.id.toString() }.toSet()
            jdbc.queryForList("SELECT id FROM webhook", String::class.java).filter { it !in keep }.forEach {
                jdbc.update("DELETE FROM webhook WHERE id = ?", it)
            }
            // Swapping two names in one save must not trip the unique constraint halfway through.
            jdbc.update("UPDATE webhook SET name = '~' || id")
            val now = Instant.now().toString()
            webhooks.forEachIndexed { index, webhook ->
                jdbc.update(
                    """
                    INSERT INTO webhook (id, name, method, url_template, headers_json, body_mode, body_template, events_json,
                                         manual, enabled, display_order, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET name = excluded.name, method = excluded.method,
                        url_template = excluded.url_template, headers_json = excluded.headers_json, body_mode = excluded.body_mode,
                        body_template = excluded.body_template, events_json = excluded.events_json, manual = excluded.manual,
                        enabled = excluded.enabled, display_order = excluded.display_order, updated_at = excluded.updated_at
                    """.trimIndent(),
                    webhook.id.toString(),
                    webhook.name.trim(),
                    webhook.method,
                    webhook.url,
                    json.writeValueAsString(webhook.headers.map { mapOf("name" to it.name, "value" to it.value) }),
                    webhook.bodyMode.name,
                    webhook.body.takeIf { webhook.bodyMode == WebhookBodyMode.TEMPLATE },
                    json.writeValueAsString(WebhookEvent.entries.filter { it in webhook.events }.map { it.key }),
                    if (webhook.manual) 1 else 0,
                    if (webhook.enabled) 1 else 0,
                    index,
                    now,
                    now,
                )
            }
        }
    }

    override fun forRequest(requestId: UUID): List<WebhookDelivery> =
        jdbc.query(
            "SELECT * FROM webhook_delivery WHERE request_id = ? ORDER BY rowid",
            { row, _ -> delivery(row) },
            requestId.toString(),
        )

    override fun forVisibleRequests(): Map<UUID, List<WebhookDelivery>> =
        jdbc
            .query(
                """
                SELECT delivery.* FROM webhook_delivery delivery
                JOIN download_request request ON request.id = delivery.request_id
                WHERE request.history_removed = 0 ORDER BY delivery.rowid
                """.trimIndent(),
                { row, _ -> delivery(row) },
            ).groupBy { it.requestId }

    override fun pending(): List<WebhookDelivery> =
        jdbc.query(
            "SELECT * FROM webhook_delivery WHERE state = 'PENDING' AND in_progress = 0 ORDER BY rowid",
            { row, _ -> delivery(row) },
        )

    override fun claim(id: UUID): Int? =
        writes.run {
            jdbc
                .query(
                    """
                    UPDATE webhook_delivery SET in_progress = 1, attempts = attempts + 1, attempted_at = ?
                    WHERE id = ? AND state = 'PENDING' AND in_progress = 0 RETURNING attempts
                    """.trimIndent(),
                    { row, _ -> row.getInt(1) },
                    Instant.now().toString(),
                    id.toString(),
                ).singleOrNull()
        }

    override fun finish(
        id: UUID,
        attempt: Int,
        sent: Boolean,
        httpStatus: Int?,
        error: String?,
    ): Boolean =
        writes.run {
            jdbc.update(
                """
                UPDATE webhook_delivery SET state = ?, in_progress = 0, http_status = ?, error = ?
                WHERE id = ? AND attempts = ? AND in_progress = 1
                """.trimIndent(),
                if (sent) DeliveryState.SENT.name else DeliveryState.FAILED.name,
                httpStatus,
                if (sent) null else error,
                id.toString(),
                attempt,
            ) == 1
        }

    override fun dispatch(
        requestId: UUID,
        webhookIds: List<UUID>,
    ): WebhookActionResult =
        writes.run {
            val visible =
                jdbc.queryForObject(
                    "SELECT count(*) FROM download_request WHERE id = ? AND history_removed = 0",
                    Int::class.java,
                    requestId.toString(),
                )
            if (visible != 1) return@run WebhookActionResult.NOT_FOUND
            val ids = webhookIds.distinct()
            if (ids.isEmpty()) return@run WebhookActionResult.INELIGIBLE
            val eligible =
                ids.mapNotNull { id ->
                    jdbc
                        .query(
                            "SELECT name FROM webhook WHERE id = ? AND enabled = 1 AND manual = 1",
                            { row, _ -> id to row.getString(1) },
                            id.toString(),
                        ).singleOrNull()
                }
            if (eligible.size != ids.size) return@run WebhookActionResult.INELIGIBLE
            val now = Instant.now().toString()
            eligible.forEach { (id, name) ->
                jdbc.update(
                    """
                    INSERT INTO webhook_delivery (id, request_id, webhook_id, webhook_name, trigger_type, event, state, created_at)
                    VALUES (?, ?, ?, ?, 'MANUAL', NULL, 'PENDING', ?)
                    """.trimIndent(),
                    UUID.randomUUID().toString(),
                    requestId.toString(),
                    id.toString(),
                    name,
                    now,
                )
            }
            WebhookActionResult.OK
        }

    override fun resend(
        requestId: UUID,
        deliveryId: UUID,
    ): WebhookActionResult =
        writes.run {
            val current =
                jdbc
                    .query(
                        "SELECT * FROM webhook_delivery WHERE id = ? AND request_id = ?",
                        { row, _ -> delivery(row) },
                        deliveryId.toString(),
                        requestId.toString(),
                    ).singleOrNull() ?: return@run WebhookActionResult.NOT_FOUND
            when {
                current.trigger != DeliveryTrigger.AUTOMATIC || current.webhookId == null -> WebhookActionResult.INELIGIBLE

                current.state == DeliveryState.PENDING || current.inProgress -> WebhookActionResult.CONFLICT

                current.state != DeliveryState.FAILED -> WebhookActionResult.INELIGIBLE

                jdbc.update(
                    """
                    UPDATE webhook_delivery SET state = 'PENDING', error = NULL, http_status = NULL
                    WHERE id = ? AND state = 'FAILED' AND in_progress = 0
                    """.trimIndent(),
                    deliveryId.toString(),
                ) == 1 -> WebhookActionResult.OK

                else -> WebhookActionResult.CONFLICT
            }
        }

    override fun recoverInterrupted(error: String): Int =
        writes.run {
            jdbc.update("UPDATE webhook_delivery SET state = 'FAILED', in_progress = 0, error = ? WHERE in_progress = 1", error)
        }

    private fun webhook(row: ResultSet): Webhook =
        Webhook(
            UUID.fromString(row.getString("id")),
            row.getString("name"),
            row.getString("method"),
            row.getString("url_template"),
            json
                .readValue(row.getString("headers_json"), object : TypeReference<List<Map<String, String>>>() {})
                .map { WebhookHeader(it["name"].orEmpty(), it["value"].orEmpty()) },
            WebhookBodyMode.valueOf(row.getString("body_mode")),
            row.getString("body_template"),
            json
                .readValue(row.getString("events_json"), object : TypeReference<List<String>>() {})
                .mapNotNull(WebhookEvent::fromKey)
                .toSet(),
            row.getInt("manual") == 1,
            row.getInt("enabled") == 1,
        )

    private fun delivery(row: ResultSet): WebhookDelivery =
        WebhookDelivery(
            UUID.fromString(row.getString("id")),
            UUID.fromString(row.getString("request_id")),
            row.getString("webhook_id")?.let(UUID::fromString),
            row.getString("webhook_name"),
            DeliveryTrigger.valueOf(row.getString("trigger_type")),
            row.getString("event"),
            DeliveryState.valueOf(row.getString("state")),
            row.getInt("in_progress") == 1,
            row.getInt("attempts"),
            Instant.parse(row.getString("created_at")),
            row.getString("attempted_at")?.let(Instant::parse),
            row.getObject("http_status")?.let { (it as Number).toInt() },
            row.getString("error"),
        )
}
