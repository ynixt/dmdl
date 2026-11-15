package com.ynixt.dmdl.resource.persistence

import com.ynixt.dmdl.domain.model.CategoryOrganization
import com.ynixt.dmdl.domain.model.ContentIdentity
import com.ynixt.dmdl.domain.model.ContentSnapshot
import com.ynixt.dmdl.domain.model.DebridProvider
import com.ynixt.dmdl.domain.model.DeliveryState
import com.ynixt.dmdl.domain.model.DeliveryTrigger
import com.ynixt.dmdl.domain.model.DownloadRequest
import com.ynixt.dmdl.domain.model.DownloadState
import com.ynixt.dmdl.domain.model.MediaCategory
import com.ynixt.dmdl.domain.model.OrganizationSnapshot
import com.ynixt.dmdl.domain.model.PlacementState
import com.ynixt.dmdl.domain.model.ReleaseProvenance
import com.ynixt.dmdl.domain.model.ReleaseReference
import com.ynixt.dmdl.domain.model.ReleaseReferenceType
import com.ynixt.dmdl.domain.model.ReleaseSnapshot
import com.ynixt.dmdl.domain.model.Webhook
import com.ynixt.dmdl.domain.model.WebhookActionResult
import com.ynixt.dmdl.domain.model.WebhookEvent
import com.ynixt.dmdl.domain.model.WebhookHeader
import com.ynixt.dmdl.resource.persistence.mapper.DownloadRequestMapper
import com.ynixt.dmdl.resource.persistence.service.SqliteDownloadStore
import com.ynixt.dmdl.resource.persistence.service.SqliteWebhookStore
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebhookPersistenceTest {
    /**
     * Managed by hand: on Windows a file scanner can briefly hold a fresh database file open, and JUnit's @TempDir then
     * fails a test that passed. Cleanup here is best effort.
     */
    private val directory: Path = Files.createTempDirectory("webhook-persistence-")

    @AfterTest
    fun cleanup() {
        runCatching { directory.toFile().deleteRecursively() }
    }

    private val json = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private val mapper = DownloadRequestMapper(json)

    /**
     * Unpooled connections: a pool may open a connection in the background while the test closes it, which on Windows
     * keeps the database file locked when JUnit deletes the temporary directory.
     */
    private fun dataSource(name: String) =
        SQLiteDataSource(
            SQLiteConfig().apply {
                enforceForeignKeys(true)
                setJournalMode(SQLiteConfig.JournalMode.WAL)
            },
        ).apply { url = "jdbc:sqlite:${directory.resolve(name)}" }

    private class Stores(
        val jdbc: JdbcTemplate,
        val downloads: SqliteDownloadStore,
        val webhooks: SqliteWebhookStore,
    )

    private fun withStores(block: (Stores) -> Unit) {
        dataSource("webhooks.db").let { source ->
            Flyway
                .configure()
                .dataSource(source)
                .locations("classpath:db/sqlite")
                .load()
                .migrate()
            val jdbc = JdbcTemplate(source)
            jdbc.update(
                """INSERT INTO application_settings(id, username, password_hash, organization_json, transfer_options_json,
                max_concurrent_downloads, created_at, updated_at) VALUES (1, 'admin', 'hash', '{}', '{}', 1, 'now', 'now')""",
            )
            val transaction = TransactionTemplate(DataSourceTransactionManager(source))
            block(Stores(jdbc, SqliteDownloadStore(jdbc, transaction, mapper), SqliteWebhookStore(jdbc, transaction, json)))
        }
    }

    private fun hook(
        name: String,
        vararg events: WebhookEvent,
        enabled: Boolean = true,
        manual: Boolean = false,
    ) = Webhook(UUID.randomUUID(), name, "POST", "https://example.com/$name", events = events.toSet(), manual = manual, enabled = enabled)

    /** Mirrors DownloadServiceImpl.execute: startOnce, then repeated TRANSFERRING progress checkpoints. */
    private fun SqliteDownloadStore.runTransfer(active: DownloadRequest): DownloadRequest {
        assertTrue(startOnce(active.id, active.execution.generation))
        repeat(3) { assertTrue(checkpoint(active.copy(state = DownloadState.TRANSFERRING))) }
        return find(active.id)!!
    }

    private fun SqliteWebhookStore.events(id: UUID) = forRequest(id).map { it.event }

    @Test
    fun `creation emits requested once and internal recovery emits nothing more`() =
        withStores { stores ->
            val all = hook("all", *WebhookEvent.entries.toTypedArray())
            stores.webhooks.replace(listOf(all))
            stores.downloads.insert(request())
            val active = assertNotNull(stores.downloads.reserveNext(UUID.randomUUID()))
            // Restart before bytes arrive: still exactly one transferring event once the transfer starts.
            assertTrue(stores.downloads.startOnce(active.id, active.execution.generation))
            assertTrue(stores.downloads.recoverOwnership(active.id, active.execution.generation))
            val transferring = stores.downloads.runTransfer(active)
            // Restart mid-transfer, twice: no new event.
            repeat(2) {
                assertTrue(stores.downloads.recoverOwnership(transferring.id, transferring.execution.generation))
                stores.downloads.runTransfer(transferring)
            }
            assertEquals(listOf("download.requested", "download.transferring"), stores.webhooks.events(active.id))
            assertTrue(stores.webhooks.forRequest(active.id).all { it.trigger == DeliveryTrigger.AUTOMATIC && it.webhookId == all.id })
        }

    @Test
    fun `pause resume and retry emit transferring again but never a second requested`() =
        withStores { stores ->
            stores.webhooks.replace(listOf(hook("all", *WebhookEvent.entries.toTypedArray())))
            stores.downloads.insert(request())
            val active = assertNotNull(stores.downloads.reserveNext(UUID.randomUUID()))
            val transferring = stores.downloads.runTransfer(active)
            val paused = assertNotNull(stores.downloads.changeState(active.id, transferring.execution.generation, DownloadState.PAUSED))
            assertNotNull(stores.downloads.changeState(active.id, paused.execution.generation, DownloadState.QUEUED))
            val resumed = stores.downloads.runTransfer(assertNotNull(stores.downloads.reserveNext(UUID.randomUUID())))
            assertTrue(stores.downloads.checkpoint(resumed.copy(state = DownloadState.FAILED, error = "KZ.TRANSFER_FAILED")))
            val failed = stores.downloads.find(active.id)!!
            assertNotNull(stores.downloads.changeState(active.id, failed.execution.generation, DownloadState.QUEUED))
            stores.downloads.runTransfer(assertNotNull(stores.downloads.reserveNext(UUID.randomUUID())))
            assertEquals(
                listOf(
                    "download.requested",
                    "download.transferring",
                    "download.paused",
                    "download.transferring",
                    "download.failed",
                    "download.transferring",
                ),
                stores.webhooks.events(active.id),
            )
        }

    @Test
    fun `needs action cancelled and completed each emit once`() =
        withStores { stores ->
            stores.webhooks.replace(listOf(hook("all", *WebhookEvent.entries.toTypedArray())))
            stores.downloads.insert(request())
            val waiting = assertNotNull(stores.downloads.reserveNext(UUID.randomUUID()))
            assertTrue(stores.downloads.startOnce(waiting.id, waiting.execution.generation))
            assertTrue(stores.downloads.checkpoint(waiting.copy(state = DownloadState.AWAITING_FILE_SELECTION)))
            val awaiting = stores.downloads.find(waiting.id)!!
            assertNotNull(stores.downloads.changeState(waiting.id, awaiting.execution.generation, DownloadState.CANCELLED))
            assertEquals(
                listOf("download.requested", "download.needs_action", "download.cancelled"),
                stores.webhooks.events(waiting.id),
            )

            stores.downloads.insert(request())
            val active = assertNotNull(stores.downloads.reserveNext(UUID.randomUUID()))
            val transferring = stores.downloads.runTransfer(active)
            assertTrue(stores.downloads.checkpoint(transferring.copy(state = DownloadState.FINALIZING)))
            val finalizing = stores.downloads.find(active.id)!!
            assertTrue(stores.downloads.recoverOwnership(finalizing.id, finalizing.execution.generation))
            assertTrue(stores.downloads.startOnce(finalizing.id, finalizing.execution.generation))
            assertTrue(stores.downloads.checkpoint(finalizing.copy(state = DownloadState.FINALIZING)))
            assertTrue(stores.downloads.checkpoint(completed(finalizing)))
            assertEquals(
                listOf("download.requested", "download.transferring", "download.completed"),
                stores.webhooks.events(active.id),
            )
        }

    @Test
    fun `only enabled webhooks subscribed to the event receive deliveries`() =
        withStores { stores ->
            val completedOnly = hook("completed", WebhookEvent.COMPLETED)
            val disabled = hook("disabled", WebhookEvent.REQUESTED, enabled = false)
            val requested = hook("requested", WebhookEvent.REQUESTED)
            val manualOnly = hook("manual", manual = true)
            stores.webhooks.replace(listOf(completedOnly, disabled, requested, manualOnly))
            stores.downloads.insert(request())
            val id =
                stores.downloads
                    .history()
                    .single()
                    .id
            assertEquals(listOf(requested.id), stores.webhooks.forRequest(id).map { it.webhookId })
        }

    @Test
    fun `claim finish resend and recovery follow one attempt at a time`() =
        withStores { stores ->
            val target = hook("target", WebhookEvent.REQUESTED, manual = true)
            stores.webhooks.replace(listOf(target))
            stores.downloads.insert(request())
            val id =
                stores.downloads
                    .history()
                    .single()
                    .id
            val delivery = stores.webhooks.pending().single()

            assertEquals(WebhookActionResult.CONFLICT, stores.webhooks.resend(id, delivery.id))
            val attempt = assertNotNull(stores.webhooks.claim(delivery.id))
            assertNull(stores.webhooks.claim(delivery.id))
            assertEquals(WebhookActionResult.CONFLICT, stores.webhooks.resend(id, delivery.id))
            assertFalse(stores.webhooks.finish(delivery.id, attempt + 1, true, 204, null))
            assertTrue(stores.webhooks.finish(delivery.id, attempt, false, 503, "KZ.WEBHOOK_HTTP_STATUS"))
            val failed = stores.webhooks.forRequest(id).single()
            assertEquals(DeliveryState.FAILED, failed.state)
            assertEquals(503, failed.httpStatus)
            assertTrue(stores.webhooks.pending().isEmpty())

            assertEquals(WebhookActionResult.NOT_FOUND, stores.webhooks.resend(UUID.randomUUID(), delivery.id))
            assertEquals(WebhookActionResult.OK, stores.webhooks.resend(id, delivery.id))
            val second = assertNotNull(stores.webhooks.claim(delivery.id))
            assertEquals(2, second)
            // A process stopped here leaves the attempt in progress; startup recovery fails it as interrupted.
            assertEquals(1, stores.webhooks.recoverInterrupted("KZ.WEBHOOK_INTERRUPTED"))
            val interrupted = stores.webhooks.forRequest(id).single()
            assertEquals(DeliveryState.FAILED, interrupted.state)
            assertFalse(interrupted.inProgress)
            assertEquals("KZ.WEBHOOK_INTERRUPTED", interrupted.error)
            assertFalse(stores.webhooks.finish(delivery.id, second, true, 204, null))

            assertEquals(WebhookActionResult.OK, stores.webhooks.resend(id, delivery.id))
            val third = assertNotNull(stores.webhooks.claim(delivery.id))
            assertTrue(stores.webhooks.finish(delivery.id, third, true, 204, null))
            assertEquals(WebhookActionResult.INELIGIBLE, stores.webhooks.resend(id, delivery.id))
        }

    @Test
    fun `manual dispatch adds its own deliveries without touching the download or automatic ones`() =
        withStores { stores ->
            val manual = hook("manual", WebhookEvent.REQUESTED, manual = true)
            val automaticOnly = hook("automatic", WebhookEvent.REQUESTED)
            val disabled = hook("disabled", manual = true, enabled = false)
            stores.webhooks.replace(listOf(manual, automaticOnly, disabled))
            stores.downloads.insert(request())
            val active = assertNotNull(stores.downloads.reserveNext(UUID.randomUUID()))
            val transferring = stores.downloads.runTransfer(active)
            assertNotNull(stores.downloads.changeState(active.id, transferring.execution.generation, DownloadState.PAUSED))
            val before = stores.downloads.find(active.id)!!
            val events = stores.downloads.events(active.id)
            val automatic = stores.webhooks.forRequest(active.id)

            assertEquals(WebhookActionResult.INELIGIBLE, stores.webhooks.dispatch(active.id, listOf(manual.id, automaticOnly.id)))
            assertEquals(WebhookActionResult.INELIGIBLE, stores.webhooks.dispatch(active.id, listOf(disabled.id)))
            assertEquals(WebhookActionResult.INELIGIBLE, stores.webhooks.dispatch(active.id, emptyList()))
            assertEquals(WebhookActionResult.NOT_FOUND, stores.webhooks.dispatch(UUID.randomUUID(), listOf(manual.id)))
            assertEquals(automatic, stores.webhooks.forRequest(active.id))

            assertEquals(WebhookActionResult.OK, stores.webhooks.dispatch(active.id, listOf(manual.id, manual.id)))
            val added = stores.webhooks.forRequest(active.id) - automatic.toSet()
            assertEquals(1, added.size)
            assertEquals(DeliveryTrigger.MANUAL, added.single().trigger)
            assertNull(added.single().event)
            assertEquals(automatic, stores.webhooks.forRequest(active.id).filter { it.trigger == DeliveryTrigger.AUTOMATIC })
            assertEquals(before, stores.downloads.find(active.id))
            assertEquals(events, stores.downloads.events(active.id))
            assertEquals(WebhookActionResult.INELIGIBLE, stores.webhooks.resend(active.id, added.single().id))
        }

    @Test
    fun `replace swaps names atomically, keeps history of deleted webhooks and history removal drops deliveries`() =
        withStores { stores ->
            val first = hook("A", WebhookEvent.REQUESTED, WebhookEvent.CANCELLED)
            val second = hook("B", WebhookEvent.REQUESTED)
            stores.webhooks.replace(listOf(first, second))
            stores.webhooks.replace(listOf(second.copy(name = "A"), first.copy(name = "B", headers = listOf(WebhookHeader("X", "1")))))
            assertEquals(listOf("A" to second.id, "B" to first.id), stores.webhooks.list().map { it.name to it.id })
            assertEquals(listOf(WebhookHeader("X", "1")), stores.webhooks.find(first.id)!!.headers)

            stores.downloads.insert(request())
            val id =
                stores.downloads
                    .history()
                    .single()
                    .id
            stores.webhooks.replace(listOf(second.copy(name = "A")))
            val kept = stores.webhooks.forRequest(id).single { it.webhookName == "B" }
            assertNull(kept.webhookId)
            assertEquals(WebhookActionResult.INELIGIBLE, stores.webhooks.resend(id, kept.id))

            val queued = stores.downloads.find(id)!!
            assertNotNull(stores.downloads.changeState(id, queued.execution.generation, DownloadState.CANCELLED))
            assertEquals(1, stores.downloads.removeFinishedFromHistory(id))
            assertTrue(stores.webhooks.forRequest(id).isEmpty())
            assertTrue(stores.webhooks.forVisibleRequests().isEmpty())
        }

    @Test
    fun `upgrading converts Jellyfin settings and notifications into a webhook and deliveries`() {
        data class Case(
            val enabled: Int,
            val url: String?,
            val token: String?,
        )
        listOf(
            Case(1, "https://jellyfin.local/", "tok\"en"),
            Case(0, "https://jellyfin.local", "token"),
            Case(0, null, null),
        ).forEachIndexed { index, case ->
            dataSource("upgrade-$index.db").let { source ->
                Flyway
                    .configure()
                    .dataSource(source)
                    .locations("classpath:db/sqlite")
                    .target("9")
                    .load()
                    .migrate()
                val jdbc = JdbcTemplate(source)
                jdbc.update(
                    """INSERT INTO application_settings(id, username, password_hash, organization_json, transfer_options_json,
                    max_concurrent_downloads, jellyfin_enabled, jellyfin_url, jellyfin_token, created_at, updated_at)
                    VALUES (1, 'admin', 'hash', '{}', '{}', 1, ?, ?, ?, 'now', 'now')""",
                    case.enabled,
                    case.url,
                    case.token,
                )
                val states = listOf("SENT", "FAILED", "PENDING", "SKIPPED")
                val requests =
                    states.map { state ->
                        val request =
                            completed(request().copy(execution = request().execution.copy(placement = PlacementState.PLACED)))
                        val row = mapper.toEntity(request)
                        jdbc.update(
                            """INSERT INTO download_request (id, content_json, release_json, provider, organization_json, state,
                            generation, owner, execution_json, progress_json, error, created_at, updated_at, completed_at)
                            VALUES (?, ?, ?, ?, ?, 'COMPLETED', 1, NULL, ?, ?, NULL, ?, ?, ?)""",
                            row.id,
                            row.contentJson,
                            row.releaseJson,
                            row.provider,
                            row.organizationJson,
                            row.executionJson,
                            row.progressJson,
                            row.createdAt,
                            row.updatedAt,
                            row.completedAt,
                        )
                        jdbc.update(
                            "INSERT INTO download_event(id, request_id, generation, state, occurred_at) VALUES (?, ?, 1, 'COMPLETED', ?)",
                            "event-${row.id}",
                            row.id,
                            row.completedAt,
                        )
                        jdbc.update(
                            "INSERT INTO jellyfin_notification(request_id, state, attempts, attempted_at, error) VALUES (?, ?, 2, ?, ?)",
                            row.id,
                            state,
                            "2026-11-14T10:00:00Z",
                            if (state == "FAILED") "Jellyfin refresh failed" else null,
                        )
                        request.id to state
                    }
                Flyway
                    .configure()
                    .dataSource(source)
                    .locations("classpath:db/sqlite")
                    .load()
                    .migrate()
                val store = SqliteWebhookStore(jdbc, TransactionTemplate(DataSourceTransactionManager(source)), json)
                val columns = jdbc.queryForList("SELECT name FROM pragma_table_info('application_settings')", String::class.java)
                assertTrue(columns.none { it?.startsWith("jellyfin") == true })
                assertEquals(
                    0,
                    jdbc.queryForObject("SELECT count(*) FROM sqlite_schema WHERE name = 'jellyfin_notification'", Int::class.java),
                )
                if (case.url == null) {
                    assertTrue(store.list().isEmpty())
                    assertTrue(store.forVisibleRequests().isEmpty())
                    return@let
                }
                val webhook = store.list().single()
                assertEquals("Jellyfin", webhook.name)
                assertEquals("https://jellyfin.local/Library/Refresh", webhook.url)
                assertEquals(setOf(WebhookEvent.COMPLETED), webhook.events)
                assertEquals(case.enabled == 1, webhook.enabled)
                assertTrue(webhook.manual)
                assertEquals("Authorization", webhook.headers.single().name)
                assertTrue(
                    webhook.headers
                        .single()
                        .value
                        .endsWith("Token=\"${case.token!!.replace("\"", "")}\""),
                )
                requests.forEach { (id, state) ->
                    val deliveries = store.forRequest(id)
                    if (state == "SKIPPED") {
                        assertTrue(deliveries.isEmpty())
                    } else {
                        val delivery = deliveries.single()
                        assertEquals(DeliveryState.valueOf(state), delivery.state)
                        assertEquals("download.completed", delivery.event)
                        assertEquals(2, delivery.attempts)
                        assertEquals(Instant.parse("2026-11-14T10:00:00Z"), delivery.attemptedAt)
                        assertEquals(webhook.id, delivery.webhookId)
                        assertEquals(if (state == "FAILED") "Jellyfin refresh failed" else null, delivery.error)
                    }
                }
                assertEquals(listOf(requests[2].first), store.pending().map { it.requestId })
            }
        }
    }

    private fun completed(request: DownloadRequest): DownloadRequest =
        request.copy(
            state = DownloadState.COMPLETED,
            completedAt = Instant.now(),
            execution = request.execution.copy(placement = PlacementState.PLACED),
        )

    private fun request(): DownloadRequest {
        val now = Instant.now()
        return DownloadRequest(
            UUID.randomUUID(),
            ContentSnapshot(ContentIdentity(UUID.randomUUID(), 1, "movie", "opaque:123"), MediaCategory.MOVIE, "Example"),
            ReleaseSnapshot(
                "Release",
                ReleaseProvenance(UUID.randomUUID(), 1, "Source", 0, 0),
                ReleaseReference(ReleaseReferenceType.INFO_HASH, "a".repeat(40)),
            ),
            DebridProvider.TORBOX,
            OrganizationSnapshot(1, directory.toString(), CategoryOrganization("{title}")),
            createdAt = now,
            updatedAt = now,
        )
    }
}
