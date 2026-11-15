package com.ynixt.dmdl.resource.persistence.service

import com.ynixt.dmdl.domain.model.DownloadEvent
import com.ynixt.dmdl.domain.model.DownloadFailure
import com.ynixt.dmdl.domain.model.DownloadRequest
import com.ynixt.dmdl.domain.model.DownloadState
import com.ynixt.dmdl.domain.model.WebhookEvent
import com.ynixt.dmdl.domain.repository.DownloadStore
import com.ynixt.dmdl.resource.persistence.entity.DownloadRequestEntity
import com.ynixt.dmdl.resource.persistence.mapper.DownloadRequestMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

@Repository
class SqliteDownloadStore(
    private val jdbc: JdbcTemplate,
    private val transaction: TransactionTemplate,
    private val mapper: DownloadRequestMapper,
) : DownloadStore {
    private val writes = BoundedSqliteWrites(transaction)

    override fun insert(request: DownloadRequest) {
        require(request.state == DownloadState.QUEUED && request.execution.owner == null)
        writes.run {
            val row = mapper.toEntity(request)
            val inserted =
                jdbc.update(
                    """
                    INSERT INTO download_request
                    (id, content_json, release_json, provider, organization_json, state, generation, owner,
                     execution_json, progress_json, error, created_at, updated_at, completed_at, started_at)
                    SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                    """.trimIndent(),
                    row.id,
                    row.contentJson,
                    row.releaseJson,
                    row.provider,
                    row.organizationJson,
                    row.state,
                    row.generation,
                    row.owner,
                    row.executionJson,
                    row.progressJson,
                    row.error,
                    row.createdAt,
                    row.updatedAt,
                    row.completedAt,
                    row.startedAt,
                )
            if (inserted !=
                1
            ) {
                throw DownloadFailure("Download request could not be inserted")
            }
            event(request.id, request.execution.generation, request.state, created = true)
        }
    }

    override fun find(id: UUID): DownloadRequest? =
        jdbc.query("SELECT * FROM download_request WHERE id = ?", { rows, _ -> read(rows) }, id.toString()).singleOrNull()

    override fun history(visibleOnly: Boolean): List<DownloadRequest> =
        jdbc.query(
            "SELECT * FROM download_request WHERE (? = 0 OR history_removed = 0) ORDER BY created_at DESC, id",
            { rows, _ -> read(rows) },
            if (visibleOnly) 1 else 0,
        )

    override fun removeFinishedFromHistory(id: UUID?): Int =
        writes.run {
            val removed =
                jdbc.update(
                    """UPDATE download_request SET history_removed = 1
                    WHERE state IN ('COMPLETED', 'CANCELLED') AND history_removed = 0 AND (? IS NULL OR id = ?)""",
                    id?.toString(),
                    id?.toString(),
                )
            // Removing a download from history removes its webhook deliveries too.
            jdbc.update(
                """DELETE FROM webhook_delivery WHERE request_id IN
                (SELECT id FROM download_request WHERE history_removed = 1 AND (? IS NULL OR id = ?))""",
                id?.toString(),
                id?.toString(),
            )
            removed
        }

    override fun events(id: UUID): List<DownloadEvent> =
        jdbc.query(
            "SELECT * FROM download_event WHERE request_id = ? ORDER BY occurred_at, id",
            { row, _ ->
                DownloadEvent(
                    UUID.fromString(row.getString("id")),
                    id,
                    row.getLong("generation"),
                    DownloadState.valueOf(row.getString("state")),
                    Instant.parse(row.getString("occurred_at")),
                    row.getString("message"),
                )
            },
            id.toString(),
        )

    /** Eligibility and capacity are checked by the same write statement, including across connections. */
    override fun reserveNext(owner: UUID): DownloadRequest? =
        writes.run {
            val request =
                jdbc
                    .query(
                        """
                        UPDATE download_request
                        SET state = 'PREPARING', generation = generation + 1, owner = ?, worker_started = 0, updated_at = ?
                        WHERE id = (SELECT candidate.id FROM download_request candidate
                            WHERE candidate.state = 'QUEUED'
                              AND (candidate.provider IS NULL OR (SELECT count(*) FROM download_request active
                                   WHERE active.provider = candidate.provider AND active.state IN ('PREPARING', 'TRANSFERRING', 'FINALIZING'))
                                  < COALESCE((SELECT json_extract(transfer_options_json, '$.maxConcurrentPerDebrid')
                                              FROM application_settings WHERE id = 1), 1))
                            ORDER BY candidate.created_at, candidate.id LIMIT 1)
                          AND state = 'QUEUED'
                          AND (SELECT count(*) FROM download_request WHERE state IN ('PREPARING', 'TRANSFERRING', 'FINALIZING'))
                              < (SELECT max_concurrent_downloads FROM application_settings WHERE id = 1)
                        RETURNING *
                        """.trimIndent(),
                        { row, _ -> read(row) },
                        owner.toString(),
                        Instant.now().toString(),
                    ).singleOrNull()
            request?.also { event(it.id, it.execution.generation, it.state) }
        }

    /** Duplicate local dispatch attempts cannot both begin the same generation. */
    override fun startOnce(
        id: UUID,
        generation: Long,
    ): Boolean =
        jdbc.update(
            """
            UPDATE download_request SET worker_started = 1
            WHERE id = ? AND generation = ? AND worker_started = 0 AND owner IS NOT NULL
              AND state IN ('PREPARING', 'TRANSFERRING', 'FINALIZING')
            """.trimIndent(),
            id.toString(),
            generation,
        ) == 1

    /** A stale worker loses authority as soon as a control action changes the generation. */
    override fun checkpoint(request: DownloadRequest): Boolean =
        writes.run {
            val row = mapper.toEntity(request)
            val updated =
                jdbc.update(
                    """
                    UPDATE download_request
                    SET state = ?, execution_json = ?, progress_json = ?, error = ?, updated_at = ?, completed_at = ?,
                        started_at = COALESCE(started_at, ?),
                        owner = CASE WHEN ? IN ('PREPARING', 'TRANSFERRING', 'FINALIZING') THEN owner ELSE NULL END
                    WHERE id = ? AND generation = ? AND owner = ? AND worker_started = 1
                      AND state IN ('PREPARING', 'TRANSFERRING', 'FINALIZING')
                    """.trimIndent(),
                    row.state,
                    row.executionJson,
                    row.progressJson,
                    row.error,
                    row.updatedAt,
                    row.completedAt,
                    row.startedAt,
                    row.state,
                    row.id,
                    row.generation,
                    row.owner,
                ) == 1
            if (updated) {
                if (request.state == DownloadState.COMPLETED) {
                    require(request.execution.placement == com.ynixt.dmdl.domain.model.PlacementState.PLACED)
                }
                event(request.id, request.execution.generation, request.state)
            }
            updated
        } == true

    override fun changeState(
        id: UUID,
        generation: Long,
        state: DownloadState,
    ): DownloadRequest? {
        require(state in setOf(DownloadState.QUEUED, DownloadState.PAUSED, DownloadState.CANCELLED))
        return writes.run {
            val allowed =
                when (state) {
                    DownloadState.QUEUED -> "'PAUSED', 'FAILED', 'AWAITING_FILE_SELECTION'"
                    DownloadState.PAUSED -> "'QUEUED', 'PREPARING', 'TRANSFERRING'"
                    else -> "'QUEUED', 'PREPARING', 'TRANSFERRING', 'AWAITING_FILE_SELECTION', 'PAUSED', 'FAILED'"
                }
            val changed =
                jdbc.update(
                    """
                    UPDATE download_request SET state = ?, generation = generation + 1, owner = NULL, worker_started = 0,
                    error = NULL, updated_at = ? WHERE id = ? AND generation = ? AND state IN ($allowed)
                    """.trimIndent(),
                    state.name,
                    Instant.now().toString(),
                    id.toString(),
                    generation,
                )
            if (changed != 1) null else find(id)?.also { event(id, it.execution.generation, state) }
        }
    }

    override fun recordCancellationCleanup(
        id: UUID,
        generation: Long,
        error: String?,
    ): Boolean =
        writes.run {
            val current = find(id) ?: return@run false
            if (current.state != DownloadState.CANCELLED || current.execution.generation != generation ||
                (current.execution.cancellationCleanupCompleted && error == null)
            ) {
                return@run false
            }
            if (error != null && current.error == error) return@run false
            val execution = current.execution.copy(cancellationCleanupCompleted = error == null)
            jdbc.update(
                """UPDATE download_request SET execution_json = ?, error = ?, updated_at = ?
                   WHERE id = ? AND generation = ? AND state = 'CANCELLED'""",
                mapper.toEntity(current.copy(execution = execution)).executionJson,
                error,
                Instant.now().toString(),
                id.toString(),
                generation,
            ) == 1
        } == true

    /** Startup only: reconcile external checkpoints before dispatching this generation again. */
    override fun recoverOwnership(
        id: UUID,
        generation: Long,
    ): Boolean =
        jdbc.update(
            """
            UPDATE download_request SET worker_started = 0 WHERE id = ? AND generation = ?
            AND state IN ('PREPARING', 'TRANSFERRING', 'FINALIZING')
            """.trimIndent(),
            id.toString(),
            generation,
        ) == 1

    /**
     * Records a transition unless it repeats the latest event of the same generation, and in the same transaction queues
     * one automatic delivery for every enabled webhook subscribed to the webhook event that transition emits.
     */
    private fun event(
        id: UUID,
        generation: Long,
        state: DownloadState,
        created: Boolean = false,
    ) {
        val eventId = UUID.randomUUID().toString()
        val now = Instant.now().toString()
        val recorded =
            jdbc.update(
                """
                INSERT INTO download_event(id, request_id, generation, state, occurred_at)
                SELECT ?, ?, ?, ?, ? WHERE NOT EXISTS
                (SELECT 1 FROM download_event WHERE rowid = (SELECT max(rowid) FROM download_event WHERE request_id = ?)
                 AND state = ? AND generation = ?)
                """.trimIndent(),
                eventId,
                id.toString(),
                generation,
                state.name,
                now,
                id.toString(),
                state.name,
                generation,
            ) == 1
        val webhookEvent = WebhookEvent.forTransition(state, created) ?: return
        if (!recorded) return
        jdbc
            .query(
                """
                SELECT id, name FROM webhook WHERE enabled = 1
                AND EXISTS (SELECT 1 FROM json_each(webhook.events_json) WHERE json_each.value = ?)
                ORDER BY display_order, name
                """.trimIndent(),
                { row, _ -> row.getString(1) to row.getString(2) },
                webhookEvent.key,
            ).forEach { (webhookId, name) ->
                jdbc.update(
                    """
                    INSERT INTO webhook_delivery (id, request_id, webhook_id, webhook_name, trigger_type, event, download_event_id,
                                                  state, created_at)
                    VALUES (?, ?, ?, ?, 'AUTOMATIC', ?, ?, 'PENDING', ?)
                    """.trimIndent(),
                    UUID.randomUUID().toString(),
                    id.toString(),
                    webhookId,
                    name,
                    webhookEvent.key,
                    eventId,
                    now,
                )
            }
    }

    private fun read(row: ResultSet): DownloadRequest =
        mapper.toDomain(
            DownloadRequestEntity(
                row.getString("id"),
                row.getString("content_json"),
                row.getString("release_json"),
                row.getString("provider"),
                row.getString("organization_json"),
                row.getString("state"),
                row.getLong("generation"),
                row.getString("owner"),
                row.getString("execution_json"),
                row.getString("progress_json"),
                row.getString("error"),
                row.getString("created_at"),
                row.getString("updated_at"),
                row.getString("completed_at"),
                row.getString("started_at"),
            ),
        )
}
