package com.ynixt.dmdl.application.config

import com.ynixt.dmdl.domain.model.CategoryOrganization
import com.ynixt.dmdl.domain.model.ContentIdentity
import com.ynixt.dmdl.domain.model.ContentSnapshot
import com.ynixt.dmdl.domain.model.DebridProvider
import com.ynixt.dmdl.domain.model.DownloadRequest
import com.ynixt.dmdl.domain.model.MediaCategory
import com.ynixt.dmdl.domain.model.OrganizationSettings
import com.ynixt.dmdl.domain.model.OrganizationSnapshot
import com.ynixt.dmdl.domain.model.ReleaseProvenance
import com.ynixt.dmdl.domain.model.ReleaseReference
import com.ynixt.dmdl.domain.model.ReleaseReferenceType
import com.ynixt.dmdl.domain.model.ReleaseSnapshot
import com.ynixt.dmdl.resource.persistence.entity.ApplicationSettingsEntity
import com.ynixt.dmdl.resource.persistence.entity.DownloadEventEntity
import com.ynixt.dmdl.resource.persistence.entity.DownloadRequestEntity
import com.ynixt.dmdl.resource.persistence.entity.JellyfinNotificationEntity
import com.ynixt.dmdl.resource.persistence.entity.MetadataAddonEntity
import com.ynixt.dmdl.resource.persistence.entity.SearchAddonEntity
import com.ynixt.dmdl.resource.persistence.mapper.DownloadRequestMapper
import org.flywaydb.core.Flyway
import org.hibernate.SessionFactory
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SqliteDatabaseConfigTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `migrates a durable real database with independent connection pragmas and Hibernate`() {
        val properties = SqliteDatabaseProperties(directory.resolve("nested/application.db").toString(), 1000)
        SqliteDatabaseConfig().dataSource(properties).use { source ->
            val baseline =
                Flyway
                    .configure()
                    .dataSource(source)
                    .locations("classpath:db/sqlite")
                    .target("1")
                    .load()
            assertEquals(1, baseline.migrate().migrationsExecuted)
            val flyway =
                Flyway
                    .configure()
                    .dataSource(source)
                    .locations("classpath:db/sqlite")
                    .load()
            assertEquals(6, flyway.migrate().migrationsExecuted)
            source.connection.use { first ->
                source.connection.use { second ->
                    listOf(first, second).forEach { connection ->
                        connection.createStatement().use { sql ->
                            mapOf("foreign_keys" to "1", "journal_mode" to "wal", "busy_timeout" to "1000").forEach { (key, value) ->
                                sql.executeQuery("PRAGMA $key").use { rows ->
                                    assertTrue(rows.next())
                                    assertEquals(value, rows.getString(1))
                                }
                            }
                        }
                    }
                }
            }
            val registry =
                StandardServiceRegistryBuilder()
                    .applySetting("hibernate.connection.datasource", source)
                    .applySetting("hibernate.dialect", "org.hibernate.community.dialect.SQLiteDialect")
                    .applySetting(
                        "hibernate.physical_naming_strategy",
                        "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy",
                    ).applySetting("hibernate.hbm2ddl.auto", "validate")
                    .build()
            try {
                val metadata = MetadataSources(registry)
                listOf(
                    DownloadRequestEntity::class.java,
                    DownloadEventEntity::class.java,
                    JellyfinNotificationEntity::class.java,
                    MetadataAddonEntity::class.java,
                    SearchAddonEntity::class.java,
                    ApplicationSettingsEntity::class.java,
                ).forEach(metadata::addAnnotatedClass)
                metadata.buildMetadata().buildSessionFactory().use { factory: SessionFactory ->
                    factory.openSession().use { session ->
                        assertEquals(
                            7L,
                            session
                                .createNativeQuery(
                                    "SELECT count(*) FROM flyway_schema_history WHERE success = 1",
                                    Long::class.java,
                                ).singleResult,
                        )
                    }
                    val mapper = DownloadRequestMapper(JsonMapper.builder().addModule(KotlinModule.Builder().build()).build())
                    val now = Instant.parse("2026-11-08T22:15:30.123456Z")
                    val request =
                        DownloadRequest(
                            UUID.randomUUID(),
                            ContentSnapshot(ContentIdentity(UUID.randomUUID(), 2, "movie", "opaque:123"), MediaCategory.MOVIE, "Example"),
                            ReleaseSnapshot(
                                "Manual release",
                                ReleaseProvenance(UUID.randomUUID(), 1, "Source", 0, 1),
                                ReleaseReference(ReleaseReferenceType.INFO_HASH, "a".repeat(40)),
                            ),
                            DebridProvider.TORBOX,
                            OrganizationSnapshot(1, directory.toString(), CategoryOrganization("{title}")),
                            createdAt = now,
                            updatedAt = now,
                        )
                    factory.openSession().use { session ->
                        session.beginTransaction()
                        session.persist(mapper.toEntity(request))
                        session.persist(
                            SearchAddonEntity(
                                UUID.randomUUID().toString(),
                                1,
                                "Source",
                                "https://example/manifest.json",
                                "{}",
                                true,
                                0,
                            ),
                        )
                        session.transaction.commit()
                    }
                    factory.openSession().use { session ->
                        assertEquals(request, mapper.toDomain(session.find(DownloadRequestEntity::class.java, request.id.toString())))
                        assertTrue(session.createQuery("from SearchAddonEntity", SearchAddonEntity::class.java).singleResult.enabled)
                    }
                }
            } finally {
                StandardServiceRegistryBuilder.destroy(registry)
            }
            source.connection.use { connection ->
                connection.createStatement().use { sql ->
                    assertFailsWith<java.sql.SQLException> {
                        sql.executeUpdate("INSERT INTO jellyfin_notification (request_id, state) VALUES ('missing', 'PENDING')")
                    }
                    sql.executeQuery("SELECT count(*) FROM sqlite_schema WHERE name = 'debrid_config'").use { rows ->
                        assertTrue(rows.next())
                        assertEquals(0, rows.getInt(1))
                    }
                    sql.executeUpdate("INSERT INTO metadata_addon VALUES (1, 'source', 1, 'Source', 'https://example/manifest.json', '{}')")
                    assertFailsWith<java.sql.SQLException> {
                        sql.executeUpdate(
                            "INSERT INTO metadata_addon VALUES (2, 'second', 1, 'Second', 'https://example/manifest.json', '{}')",
                        )
                    }
                }
            }
        }
        SqliteDatabaseConfig().dataSource(properties).use { source ->
            val flyway =
                Flyway
                    .configure()
                    .dataSource(source)
                    .locations("classpath:db/sqlite")
                    .load()
            assertEquals(0, flyway.migrate().migrationsExecuted)
            assertTrue(flyway.validateWithResult().validationSuccessful)
        }
    }

    @Test
    fun `fails clearly when application data parent is not a directory`() {
        val regularFile = Files.writeString(directory.resolve("not-a-directory"), "existing data")
        val error =
            assertFailsWith<IllegalStateException> {
                SqliteDatabaseConfig().dataSource(SqliteDatabaseProperties(regularFile.resolve("application.db").toString()))
            }
        assertTrue(error.message.orEmpty().contains("SQLite application-data directory is not writable"))
        assertEquals("existing data", Files.readString(regularFile))
    }
}
