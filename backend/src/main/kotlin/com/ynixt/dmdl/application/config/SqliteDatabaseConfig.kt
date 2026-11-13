package com.ynixt.dmdl.application.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.sqlite.SQLiteConfig
import java.nio.file.Files
import java.nio.file.Path

@ConfigurationProperties("application.database")
data class SqliteDatabaseProperties(
    val path: String = "./data/dmdl.db",
    val busyTimeoutMillis: Int = 5000,
)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SqliteDatabaseProperties::class)
class SqliteDatabaseConfig {
    @Bean
    fun dataSource(properties: SqliteDatabaseProperties): HikariDataSource {
        require(properties.busyTimeoutMillis in 100..30000) { "SQLite busy timeout must be between 100 and 30000 ms" }
        val file = Path.of(properties.path).toAbsolutePath().normalize()
        try {
            Files.createDirectories(file.parent)
            require(!Files.exists(file) || Files.isRegularFile(file))
            Files.createTempFile(file.parent, ".sqlite-write-check-", ".tmp").also(Files::delete)
        } catch (exception: Exception) {
            throw IllegalStateException("SQLite application-data directory is not writable: ${file.parent}", exception)
        }
        val sqlite =
            SQLiteConfig().apply {
                enforceForeignKeys(true)
                setJournalMode(SQLiteConfig.JournalMode.WAL)
                setSynchronous(SQLiteConfig.SynchronousMode.NORMAL)
                setBusyTimeout(properties.busyTimeoutMillis)
            }
        return HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:$file"
                driverClassName = "org.sqlite.JDBC"
                dataSourceProperties = sqlite.toProperties()
                maximumPoolSize = 3
                minimumIdle = 1
                connectionTimeout = 10000
                poolName = "dmdl-sqlite"
            },
        )
    }
}
