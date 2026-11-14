package com.ynixt.dmdl.resource.persistence

import com.ynixt.dmdl.application.config.SqliteDatabaseConfig
import com.ynixt.dmdl.application.config.SqliteDatabaseProperties
import com.ynixt.dmdl.domain.enum.Aria2FileAllocation
import com.ynixt.dmdl.domain.model.CategoryOrganization
import com.ynixt.dmdl.domain.model.ContentIdentity
import com.ynixt.dmdl.domain.model.ContentSnapshot
import com.ynixt.dmdl.domain.model.DebridProvider
import com.ynixt.dmdl.domain.model.DownloadExecution
import com.ynixt.dmdl.domain.model.DownloadRequest
import com.ynixt.dmdl.domain.model.DownloadState
import com.ynixt.dmdl.domain.model.MediaCategory
import com.ynixt.dmdl.domain.model.OrganizationSnapshot
import com.ynixt.dmdl.domain.model.ReleaseProvenance
import com.ynixt.dmdl.domain.model.ReleaseReference
import com.ynixt.dmdl.domain.model.ReleaseReferenceType
import com.ynixt.dmdl.domain.model.ReleaseSnapshot
import com.ynixt.dmdl.resource.persistence.mapper.DownloadRequestMapper
import com.ynixt.dmdl.resource.persistence.service.SqliteDownloadStore
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqliteDownloadStoreTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `competing connections respect capacity and reject duplicate and stale execution`() {
        val properties = SqliteDatabaseProperties(directory.resolve("queue.db").toString(), 1000)
        SqliteDatabaseConfig().dataSource(properties).use { source ->
            Flyway
                .configure()
                .dataSource(source)
                .locations("classpath:db/sqlite")
                .load()
                .migrate()
            val jdbc = JdbcTemplate(source)
            jdbc.update(
                """INSERT INTO application_settings(id, username, password_hash, organization_json, transfer_options_json,
                max_concurrent_downloads, created_at, updated_at) VALUES (1, 'admin', 'hash', '{}', '{"maxConcurrentPerDebrid":2}', 2, ?, ?)""",
                Instant.now().toString(),
                Instant.now().toString(),
            )
            val store =
                SqliteDownloadStore(
                    jdbc,
                    TransactionTemplate(DataSourceTransactionManager(source)),
                    DownloadRequestMapper(JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()),
                )
            repeat(6) { store.insert(request()) }
            val gate = CountDownLatch(1)
            val reservations =
                Executors.newFixedThreadPool(8).use { pool ->
                    val futures =
                        (1..8).map {
                            pool.submit(
                                Callable {
                                    gate.await()
                                    store.reserveNext(UUID.randomUUID())
                                },
                            )
                        }
                    gate.countDown()
                    futures.mapNotNull { it.get() }
                }
            assertEquals(2, reservations.size)
            assertEquals(2, reservations.map { it.id }.toSet().size)
            val first = reservations.first()
            assertTrue(store.startOnce(first.id, first.execution.generation))
            assertFalse(store.startOnce(first.id, first.execution.generation))
            val paused = assertNotNull(store.changeState(first.id, first.execution.generation, DownloadState.PAUSED))
            assertFalse(store.checkpoint(first.copy(state = DownloadState.TRANSFERRING)))
            assertNull(store.changeState(first.id, first.execution.generation, DownloadState.CANCELLED))
            assertEquals(DownloadState.PAUSED, store.find(first.id)?.state)
            assertNotNull(store.reserveNext(UUID.randomUUID()))
            assertNull(store.reserveNext(UUID.randomUUID()))
            assertNotNull(store.changeState(paused.id, paused.execution.generation, DownloadState.QUEUED))
            assertEquals(
                listOf(
                    DownloadState.QUEUED,
                    DownloadState.PREPARING,
                    DownloadState.PAUSED,
                    DownloadState.QUEUED,
                ),
                store.events(first.id).map {
                    it.state
                },
            )
            store.history().filter { it.state.reservesCapacity }.forEach {
                store.changeState(it.id, it.execution.generation, DownloadState.CANCELLED)
            }
            jdbc.update("UPDATE application_settings SET transfer_options_json = '{\"maxConcurrentPerDebrid\":1}'")
            store.insert(request().copy(provider = DebridProvider.REAL_DEBRID))
            val perDebridReservations =
                Executors.newFixedThreadPool(8).use { pool ->
                    pool.invokeAll((1..8).map { Callable { store.reserveNext(UUID.randomUUID()) } }).mapNotNull { it.get() }
                }
            assertEquals(setOf(DebridProvider.TORBOX, DebridProvider.REAL_DEBRID), perDebridReservations.map { it.provider }.toSet())
            assertEquals(2, perDebridReservations.size)
            assertNull(store.reserveNext(UUID.randomUUID()))
        }
        SqliteDatabaseConfig().dataSource(properties).use { source ->
            assertEquals(7, JdbcTemplate(source).queryForObject("SELECT count(*) FROM download_request", Int::class.java))
        }
    }

    @Test
    fun `awaiting a file releases capacity and continuation must reserve again`() {
        val properties = SqliteDatabaseProperties(directory.resolve("selection.db").toString(), 1000)
        SqliteDatabaseConfig().dataSource(properties).use { source ->
            Flyway
                .configure()
                .dataSource(source)
                .locations("classpath:db/sqlite")
                .load()
                .migrate()
            val jdbc = JdbcTemplate(source)
            jdbc.update(
                """INSERT INTO application_settings(id, username, password_hash, organization_json, transfer_options_json,
                max_concurrent_downloads, created_at, updated_at) VALUES (1, 'admin', 'hash', '{}', '{}', 1, ?, ?)""",
                Instant.now().toString(),
                Instant.now().toString(),
            )
            val store =
                SqliteDownloadStore(
                    jdbc,
                    TransactionTemplate(DataSourceTransactionManager(source)),
                    DownloadRequestMapper(JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()),
                )
            repeat(2) { store.insert(request()) }
            val active = assertNotNull(store.reserveNext(UUID.randomUUID()))
            assertTrue(store.startOnce(active.id, active.execution.generation))
            assertTrue(store.checkpoint(active.copy(state = DownloadState.AWAITING_FILE_SELECTION)))
            assertNotNull(store.reserveNext(UUID.randomUUID()))
            val waiting = assertNotNull(store.find(active.id))
            assertNull(waiting.execution.owner)
            assertNotNull(store.changeState(waiting.id, waiting.execution.generation, DownloadState.QUEUED))
            assertNull(store.reserveNext(UUID.randomUUID()))
        }
    }

    @Test
    fun `history removal migrates existing records restricts states and survives reopening`() {
        val properties = SqliteDatabaseProperties(directory.resolve("history.db").toString(), 1000)
        val entries =
            (DownloadState.entries + DownloadState.COMPLETED).map {
                it to request().copy(execution = DownloadExecution(fileAllocationOverride = Aria2FileAllocation.PREALLOC))
            }
        val completed = entries.filter { it.first == DownloadState.COMPLETED }.map { it.second.id }
        val cancelled = entries.single { it.first == DownloadState.CANCELLED }.second.id
        val mapper = DownloadRequestMapper(JsonMapper.builder().addModule(KotlinModule.Builder().build()).build())
        SqliteDatabaseConfig().dataSource(properties).use { source ->
            Flyway
                .configure()
                .dataSource(source)
                .locations("classpath:db/sqlite")
                .target("5")
                .load()
                .migrate()
            val jdbc = JdbcTemplate(source)
            val store = SqliteDownloadStore(jdbc, TransactionTemplate(DataSourceTransactionManager(source)), mapper)
            entries.forEach { (state, request) ->
                insertBeforeStartedAt(jdbc, mapper, request)
                jdbc.update(
                    "UPDATE download_request SET state = ?, owner = ?, completed_at = ? WHERE id = ?",
                    state.name,
                    if (state.reservesCapacity) UUID.randomUUID().toString() else null,
                    if (state == DownloadState.COMPLETED) Instant.now().toString() else null,
                    request.id.toString(),
                )
            }
            Flyway
                .configure()
                .dataSource(source)
                .locations("classpath:db/sqlite")
                .load()
                .migrate()
            assertEquals(entries.size, store.history(visibleOnly = true).size)
            entries.filter { it.first !in setOf(DownloadState.COMPLETED, DownloadState.CANCELLED) }.forEach { (_, request) ->
                assertEquals(0, store.removeFinishedFromHistory(request.id))
            }
            assertEquals(0, store.removeFinishedFromHistory(UUID.randomUUID()))
            assertEquals(1, store.removeFinishedFromHistory(completed.first()))
            assertEquals(0, store.removeFinishedFromHistory(completed.first()))
            assertFalse(store.history(visibleOnly = true).any { it.id == completed.first() })
            assertNotNull(store.find(completed.first()))
            assertTrue(store.events(completed.first()).isNotEmpty())
            assertEquals(1, store.removeFinishedFromHistory(cancelled))
            assertEquals(0, store.removeFinishedFromHistory(cancelled))
            assertNotNull(store.find(cancelled))
            assertFalse(store.find(cancelled)!!.execution.cancellationCleanupCompleted)
        }
        SqliteDatabaseConfig().dataSource(properties).use { source ->
            val store = SqliteDownloadStore(JdbcTemplate(source), TransactionTemplate(DataSourceTransactionManager(source)), mapper)
            assertEquals(entries.size - 2, store.history(visibleOnly = true).size)
            assertEquals(Aria2FileAllocation.PREALLOC, store.find(completed.first())!!.execution.fileAllocationOverride)
            assertEquals(1, store.removeFinishedFromHistory(null))
            assertEquals(0, store.removeFinishedFromHistory(null))
            assertEquals(
                entries.filter { it.first !in setOf(DownloadState.COMPLETED, DownloadState.CANCELLED) }.map { it.second.id }.toSet(),
                store.history(visibleOnly = true).map { it.id }.toSet(),
            )
            assertEquals(entries.size, store.history().size)
        }
    }

    @Test
    fun `unidentified HTTP services reserve global capacity without credentials`() {
        SqliteDatabaseConfig().dataSource(SqliteDatabaseProperties(directory.resolve("unidentified.db").toString())).use { source ->
            Flyway
                .configure()
                .dataSource(source)
                .locations("classpath:db/sqlite")
                .load()
                .migrate()
            val jdbc = JdbcTemplate(source)
            jdbc.update(
                """INSERT INTO application_settings
                    (id, username, password_hash, organization_json, transfer_options_json, max_concurrent_downloads, created_at, updated_at)
                    VALUES (1, 'admin', 'hash', '{}', '{"maxConcurrentPerDebrid":1}', 2, 'now', 'now')""",
            )
            val store =
                SqliteDownloadStore(
                    jdbc,
                    TransactionTemplate(DataSourceTransactionManager(source)),
                    DownloadRequestMapper(JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()),
                )
            repeat(2) {
                val request = request()
                store.insert(
                    request.copy(
                        provider = null,
                        release =
                            request.release.copy(
                                reference = ReleaseReference(ReleaseReferenceType.PROVIDER_HTTP, "https://addon.example/file.mkv"),
                            ),
                    ),
                )
            }
            repeat(2) { assertNull(assertNotNull(store.reserveNext(UUID.randomUUID())).provider) }
            assertNull(store.reserveNext(UUID.randomUUID()))
        }
    }

    @Test
    fun `started timestamp is backfilled from the earliest transferring event`() {
        val properties = SqliteDatabaseProperties(directory.resolve("started.db").toString(), 1000)
        val mapper = DownloadRequestMapper(JsonMapper.builder().addModule(KotlinModule.Builder().build()).build())
        val transferred = request()
        val queued = request()
        SqliteDatabaseConfig().dataSource(properties).use { source ->
            Flyway
                .configure()
                .dataSource(source)
                .locations("classpath:db/sqlite")
                .target("7")
                .load()
                .migrate()
            val jdbc = JdbcTemplate(source)
            listOf(transferred, queued).forEach { insertBeforeStartedAt(jdbc, mapper, it) }
            jdbc.update("UPDATE download_request SET state = 'FAILED', generation = 3 WHERE id = ?", transferred.id.toString())
            listOf(
                Triple(transferred, "QUEUED", "2026-11-14T14:40:00Z"),
                Triple(transferred, "TRANSFERRING", "2026-11-14T15:10:00.5Z"),
                Triple(transferred, "TRANSFERRING", "2026-11-14T14:41:00.25Z"),
                Triple(transferred, "TRANSFERRING", "2026-11-14T14:41:00Z"),
                Triple(transferred, "FAILED", "2026-11-14T14:50:00Z"),
                Triple(queued, "QUEUED", "2026-11-14T14:40:00Z"),
            ).forEachIndexed { generation, (request, state, at) ->
                jdbc.update(
                    "INSERT INTO download_event(id, request_id, generation, state, occurred_at) VALUES (?, ?, ?, ?, ?)",
                    UUID.randomUUID().toString(),
                    request.id.toString(),
                    generation,
                    state,
                    at,
                )
            }
            Flyway
                .configure()
                .dataSource(source)
                .locations("classpath:db/sqlite")
                .load()
                .migrate()
            val store = SqliteDownloadStore(jdbc, TransactionTemplate(DataSourceTransactionManager(source)), mapper)
            assertEquals(Instant.parse("2026-11-14T14:41:00Z"), store.find(transferred.id)!!.startedAt)
            assertNull(store.find(queued.id)!!.startedAt)
        }
    }

    @Test
    fun `started timestamp round-trips and keeps its first value`() {
        SqliteDatabaseConfig().dataSource(SqliteDatabaseProperties(directory.resolve("roundtrip.db").toString())).use { source ->
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
            val store =
                SqliteDownloadStore(
                    jdbc,
                    TransactionTemplate(DataSourceTransactionManager(source)),
                    DownloadRequestMapper(JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()),
                )
            store.insert(request())
            val active = assertNotNull(store.reserveNext(UUID.randomUUID()))
            assertNull(active.startedAt)
            assertTrue(store.startOnce(active.id, active.execution.generation))
            val first = Instant.parse("2026-11-14T14:41:00.123Z")
            assertTrue(store.checkpoint(active.copy(state = DownloadState.TRANSFERRING, startedAt = first)))
            assertEquals(first, store.find(active.id)!!.startedAt)
            assertTrue(store.checkpoint(active.copy(state = DownloadState.TRANSFERRING, startedAt = first.plusSeconds(1800))))
            assertEquals(first, store.find(active.id)!!.startedAt)
            assertTrue(store.checkpoint(active.copy(state = DownloadState.TRANSFERRING, startedAt = null)))
            assertEquals(first, store.find(active.id)!!.startedAt)
        }
    }

    /** Inserts like [SqliteDownloadStore.insert] did before V8 added `started_at`. */
    private fun insertBeforeStartedAt(
        jdbc: JdbcTemplate,
        mapper: DownloadRequestMapper,
        request: DownloadRequest,
    ) {
        val row = mapper.toEntity(request)
        jdbc.update(
            """INSERT INTO download_request (id, content_json, release_json, provider, organization_json, state, generation,
            owner, execution_json, progress_json, error, created_at, updated_at, completed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
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
        )
        jdbc.update(
            "INSERT INTO download_event(id, request_id, generation, state, occurred_at) VALUES (?, ?, ?, ?, ?)",
            UUID.randomUUID().toString(),
            row.id,
            row.generation,
            row.state,
            row.createdAt,
        )
    }

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
