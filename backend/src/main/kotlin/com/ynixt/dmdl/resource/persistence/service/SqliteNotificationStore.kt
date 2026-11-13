package com.ynixt.dmdl.resource.persistence.service

import com.ynixt.dmdl.domain.model.JellyfinNotification
import com.ynixt.dmdl.domain.model.NotificationState
import com.ynixt.dmdl.domain.repository.NotificationStore
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class SqliteNotificationStore(
    private val jdbc: JdbcTemplate,
) : NotificationStore {
    override fun find(id: UUID): JellyfinNotification? =
        jdbc
            .query("SELECT * FROM jellyfin_notification WHERE request_id = ?", { row, _ ->
                JellyfinNotification(
                    id,
                    NotificationState.valueOf(row.getString("state")),
                    row.getInt("attempts"),
                    row.getString("attempted_at")?.let(Instant::parse),
                    row.getString("error"),
                )
            }, id.toString())
            .singleOrNull()

    override fun pending(): List<UUID> =
        jdbc.query("SELECT request_id FROM jellyfin_notification WHERE state = 'PENDING'", { row, _ -> UUID.fromString(row.getString(1)) })

    override fun retry(id: UUID): Boolean =
        jdbc.update(
            """UPDATE jellyfin_notification SET state = 'PENDING', error = NULL
        WHERE request_id = ? AND state IN ('FAILED', 'SKIPPED') AND EXISTS
        (SELECT 1 FROM download_request WHERE id = ? AND state = 'COMPLETED')""",
            id.toString(),
            id.toString(),
        ) == 1

    override fun claim(id: UUID): Int? =
        jdbc
            .query(
                """UPDATE jellyfin_notification SET state = 'FAILED', attempts = attempts + 1,
        attempted_at = ?, error = 'Notification interrupted; retry if needed'
        WHERE request_id = ? AND state = 'PENDING' AND EXISTS
        (SELECT 1 FROM download_request WHERE id = ? AND state = 'COMPLETED') RETURNING attempts""",
                { row, _ -> row.getInt(1) },
                Instant.now().toString(),
                id.toString(),
                id.toString(),
            ).singleOrNull()

    override fun finish(
        id: UUID,
        attempt: Int,
        state: NotificationState,
        error: String?,
    ) {
        jdbc.update(
            "UPDATE jellyfin_notification SET state = ?, error = ? WHERE request_id = ? AND attempts = ?",
            state.name,
            error,
            id.toString(),
            attempt,
        )
    }
}
