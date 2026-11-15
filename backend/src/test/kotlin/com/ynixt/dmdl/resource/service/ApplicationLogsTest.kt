package com.ynixt.dmdl.resource.service

import com.ynixt.dmdl.domain.model.LogFilter
import com.ynixt.dmdl.domain.model.LogLevel
import com.ynixt.dmdl.domain.model.LogSource
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ApplicationLogsTest {
    @TempDir lateinit var directory: Path

    private val json = JsonMapper.builder().build()
    private var line = 0L

    private fun service(): ApplicationLogsImpl {
        Files.writeString(directory.resolve("run-id"), "fixture-run")
        return ApplicationLogsImpl(directory.toString())
    }

    private fun append(
        source: String,
        message: String,
        segment: Int = 1,
        truncated: Boolean = false,
        captured: String = "2026-11-15T10:00:00Z",
    ) {
        val target = Files.createDirectories(directory.resolve(source))
        Files.writeString(target.resolve("status.json"), """{"state":"available"}""")
        Files.writeString(
            target.resolve("%020d.jsonl".format(segment)),
            json.writeValueAsString(
                mapOf(
                    "sequence" to ++line,
                    "timestamp" to captured,
                    "message" to message,
                    "truncated" to truncated,
                ),
            ) +
                "\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    private fun settle(logs: ApplicationLogsImpl) {
        logs.refresh()
        Thread.sleep(220)
        logs.refresh()
    }

    @Test
    fun `quiet records normalize all source levels timestamps and preserve unknown output`() {
        val logs = service()
        append("backend", "2026-11-15T09:00:00.000Z ERROR boom")
        append("nginx", "2026/11/15 09:00:00 [crit] failed")
        append("aria2", "[aria2] 2026-11-15 09:00:00.000 [NOTICE] ready")
        settle(logs)
        val records = logs.history(LogFilter()).records
        assertEquals(
            mapOf(LogSource.backend to LogLevel.ERROR, LogSource.nginx to LogLevel.ERROR, LogSource.aria2 to LogLevel.INFO),
            records.associate {
                it.source to
                    it.severity
            },
        )
        assertEquals(Instant.parse("2026-11-15T09:00:00Z"), records.single { it.source == LogSource.backend }.timestamp)
        append("backend", "unclassified <script>text</script>")
        settle(logs)
        assertEquals(
            LogLevel.UNKNOWN,
            logs
                .history(LogFilter(LogSource.backend))
                .records
                .last()
                .severity,
        )
        assertEquals(
            Instant.parse("2026-11-15T10:00:00Z"),
            logs
                .history(LogFilter(LogSource.backend))
                .records
                .last()
                .timestamp,
        )
        assertEquals(
            LogLevel.INFO,
            LogRecordParser.level(LogSource.nginx, "127.0.0.1 - - [15/Nov/2026:09:00:00 +0000] \"GET / HTTP/1.1\" 200 10"),
        )
        mapOf(
            "TRACE" to LogLevel.TRACE,
            "DEBUG" to LogLevel.DEBUG,
            "WARNING" to LogLevel.WARN,
            "FATAL" to LogLevel.ERROR,
            "ALERT" to LogLevel.ERROR,
            "EMERG" to LogLevel.ERROR,
        ).forEach { (raw, expected) ->
            assertEquals(expected, LogRecordParser.level(LogSource.backend, "[$raw] message"))
        }
    }

    @Test
    fun `multiline filters use literal case insensitive complete text and stable updated identities`() {
        val logs = service()
        append("backend", "2026-11-15T09:00:00Z ERROR timeout [error].*")
        settle(logs)
        val snapshot = logs.history(LogFilter())
        append("backend", "    at ConnectionRefusedException")
        settle(logs)
        val filter = LogFilter(LogSource.backend, "connectionrefusedexception", setOf(LogLevel.ERROR))
        val grouped = logs.history(filter).records.single()
        assertEquals(snapshot.records.single().id, grouped.id)
        assertTrue(grouped.message.contains("timeout [error].*\n    at ConnectionRefusedException"))
        assertEquals(grouped, logs.replay(filter, snapshot.cursor).records.single())
        assertEquals(1, logs.history(LogFilter(text = "[ERROR].*")).records.size)
        assertTrue(logs.history(LogFilter(LogSource.aria2, "timeout")).records.isEmpty())
        assertTrue(logs.history(LogFilter(levels = setOf(LogLevel.INFO))).records.isEmpty())
        assertEquals(1, logs.history(LogFilter(text = "   ")).records.size)
    }

    @Test
    fun `recent mixed source history is ordered by capture time and pages timestamp ties`() {
        val logs = service()
        append("backend", "newest", captured = "2026-11-15T10:00:02Z")
        append("nginx", "oldest", captured = "2026-11-15T10:00:00Z")
        append("aria2", "middle", captured = "2026-11-15T10:00:01Z")
        append("backend", "newest tied", captured = "2026-11-15T10:00:02Z")
        settle(logs)
        val first = logs.history(LogFilter(), limit = 1)
        assertEquals("newest tied", first.records.single().message)
        val second = logs.history(LogFilter(), first.olderCursor, 1)
        assertEquals("newest", second.records.single().message)
        val rest = logs.history(LogFilter(), second.olderCursor)
        assertEquals(listOf("oldest", "middle"), rest.records.map { it.message })
    }

    @Test
    fun `history pages and replay cover handoff without duplicates and validate inputs`() {
        val logs = service()
        repeat(5) { append("backend", "2026-11-15T09:00:00Z INFO record $it") }
        settle(logs)
        val recent = logs.history(LogFilter(), limit = 2)
        assertTrue(recent.hasMore)
        val older = logs.history(LogFilter(), recent.olderCursor, 2)
        assertEquals(4, (recent.records + older.records).map { it.id }.toSet().size)
        append("backend", "2026-11-15T09:00:00Z INFO handoff")
        settle(logs)
        val replay = logs.replay(LogFilter(), recent.cursor)
        assertEquals("2026-11-15T09:00:00Z INFO handoff", replay.records.single().message)
        assertTrue(logs.replay(LogFilter(), replay.cursor).records.isEmpty())
        assertFailsWith<IllegalArgumentException> { logs.history(LogFilter(), limit = 1001) }
        assertFailsWith<IllegalArgumentException> { logs.history(LogFilter(), limit = 0) }
        assertFailsWith<IllegalArgumentException> { logs.replay(LogFilter(), "malformed") }
        assertEquals("unavailable", recent.sources.single { it.source == LogSource.nginx }.state)
        assertTrue(recent.sources.single { it.source == LogSource.backend }.oldestTimestamp != null)
    }

    @Test
    fun `rotation and run changes return explicit recovery and records are bounded`() {
        val logs = service()
        append("backend", "2026-11-15T09:00:00Z INFO first")
        settle(logs)
        val cursor = logs.history(LogFilter()).cursor
        Files.delete(directory.resolve("backend/%020d.jsonl".format(1)))
        append("backend", "2026-11-15T09:00:00Z ERROR " + "x".repeat(70000), 2)
        settle(logs)
        val replay = logs.replay(LogFilter(), cursor)
        assertEquals("retention", replay.gap)
        assertEquals(
            65536,
            replay.records
                .single()
                .message.length,
        )
        assertTrue(replay.records.single().truncated)
        Files.writeString(directory.resolve("run-id"), "new-run")
        settle(logs)
        val reset = logs.replay(LogFilter(), replay.cursor)
        assertEquals("reset", reset.gap)
        assertNotEquals(replay.records.single().id, reset.records.single().id)
    }
}
