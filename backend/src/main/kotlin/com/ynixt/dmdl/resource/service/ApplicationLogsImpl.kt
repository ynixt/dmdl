package com.ynixt.dmdl.resource.service

import com.ynixt.dmdl.domain.model.LogFilter
import com.ynixt.dmdl.domain.model.LogPage
import com.ynixt.dmdl.domain.model.LogRecord
import com.ynixt.dmdl.domain.model.LogSource
import com.ynixt.dmdl.domain.model.LogSourceStatus
import com.ynixt.dmdl.domain.service.ApplicationLogs
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Base64

@Service
class ApplicationLogsImpl(
    @Value("\${application.logs.directory:/run/dmdl/logs}") directory: String,
) : ApplicationLogs {
    private val root = Path.of(directory)
    private val json = JsonMapper.builder().build()
    private var run = "unavailable"
    private var sequence = 0L
    private var floor = 0L
    private var characters = 0L
    private val records = linkedMapOf<String, Stored>()
    private val readers = LogSource.entries.associateWith { Reader() }

    private class Reader {
        var segment = ""
        var offset = 0L
        var current: Stored? = null
        var touched = 0L
        var dirty = false
        var state = "unavailable"
    }

    private data class Stored(
        var record: LogRecord,
        var segment: String,
        val captureKey: String,
        var version: Long = 0,
    )

    private data class Cursor(
        val run: String,
        val version: Long,
        val before: String?,
    )

    @Synchronized
    @Scheduled(fixedDelay = 250)
    fun refresh() {
        val identity = runCatching { Files.readString(root.resolve("run-id")).trim() }.getOrNull() ?: "unavailable"
        if (identity != run) {
            run = identity
            records.clear()
            characters = 0
            sequence = 0
            floor = 0
            readers.keys.forEach {
                readers[it]!!.apply {
                    segment = ""
                    offset = 0
                    current = null
                    dirty = false
                }
            }
        }
        readers.forEach { (source, reader) ->
            try {
                read(source, reader)
            } catch (_: Exception) {
                reader.state = "failed"
            }
        }
        while (records.size > 50000 || characters > 45L * 1024 * 1024) {
            evict(records.keys.first())
        }
    }

    private fun read(
        source: LogSource,
        reader: Reader,
    ) {
        val directory = root.resolve(source.name)
        if (!Files.isDirectory(directory)) {
            reader.state = "unavailable"
            return
        }
        reader.state =
            runCatching { json.readTree(Files.readString(directory.resolve("status.json")))["state"].asString() }.getOrDefault("failed")
        val segments =
            Files.list(directory).use { paths ->
                paths.filter { it.fileName.toString().matches(Regex("\\d{20}\\.jsonl")) }.sorted().toList()
            }
        val oldest = segments.firstOrNull()?.fileName?.toString()
        if (oldest != null) {
            if (reader.segment.isNotEmpty() && reader.segment < oldest) {
                sequence++
                floor = sequence
            }
            records.values
                .filter { it.record.source == source && it.segment < oldest }
                .map { it.record.id }
                .forEach(::evict)
            if (reader.current?.segment?.let { it < oldest } == true) reader.current = null
        }
        var bytes = 0L
        for (path in segments) {
            val name = path.fileName.toString()
            if (name < reader.segment) continue
            if (name != reader.segment) {
                reader.segment = name
                reader.offset = 0
            }
            Files.newInputStream(path).use { file ->
                file.skipNBytes(reader.offset)
                val input = file.buffered(65536)
                while (bytes < 4L * 1024 * 1024) {
                    val raw = readLine(input) ?: break
                    reader.offset += raw.size + 1
                    bytes += raw.size + 1
                    val line = json.readTree(raw)
                    accept(
                        source,
                        reader,
                        name,
                        line["sequence"].asLong(),
                        Instant.parse(line["timestamp"].asString()),
                        line["message"].asString(),
                        line["truncated"].asBoolean(),
                    )
                }
            }
            if (bytes >= 4L * 1024 * 1024) break
        }
        if (reader.dirty && System.nanoTime() - reader.touched >= 200_000_000) publish(reader)
    }

    private fun readLine(input: InputStream): ByteArray? {
        val output = ByteArrayOutputStream()
        while (output.size() <= 512 * 1024) {
            val byte = input.read()
            if (byte == -1) return null
            if (byte == 10) return output.toByteArray()
            output.write(byte)
        }
        error("Capture record exceeds its bound")
    }

    private fun accept(
        source: LogSource,
        reader: Reader,
        segment: String,
        line: Long,
        captured: Instant,
        message: String,
        truncated: Boolean,
    ) {
        val previous = reader.current
        if (previous == null || LogRecordParser.startsRecord(message)) {
            publish(reader)
            val id = encode("$run|${source.name}|$line")
            reader.current =
                Stored(
                    LogRecord(
                        id,
                        LogRecordParser.timestamp(message, captured),
                        source,
                        LogRecordParser.level(source, message),
                        message.take(65536),
                        truncated || message.length > 65536,
                    ),
                    segment,
                    "%012d.%09d~%s~%020d".format(captured.epochSecond, captured.nano, source.name, line),
                )
        } else {
            val room = (65536 - previous.record.message.length).coerceAtLeast(0)
            val addition = "\n$message"
            val updated =
                previous.record.copy(
                    message = previous.record.message + addition.take(room),
                    truncated =
                        previous.record.truncated || truncated || addition.length > room,
                )
            if (previous.version > 0) characters += updated.message.length - previous.record.message.length
            previous.record = updated
            previous.segment = segment
        }
        reader.touched = System.nanoTime()
        reader.dirty = true
    }

    private fun publish(reader: Reader) {
        val stored = reader.current ?: return
        if (!reader.dirty) return
        sequence++
        stored.version = sequence
        if (!records.containsKey(stored.record.id)) {
            records[stored.record.id] = stored
            characters += stored.record.message.length
        }
        reader.dirty = false
    }

    private fun evict(id: String) {
        val removed = records.remove(id) ?: return
        characters -= removed.record.message.length
        floor = maxOf(floor, removed.version)
        readers.values.filter { it.current === removed }.forEach {
            it.current = null
            it.dirty = false
        }
    }

    @Synchronized
    override fun history(
        filter: LogFilter,
        before: String?,
        limit: Int,
    ): LogPage {
        require(limit in 1..1000) { "Invalid log limit" }
        refresh()
        val position = before?.let(::decodeCursor)
        require(position == null || position.before != null) { "Invalid log history cursor" }
        val gap = gap(position)
        val candidates =
            records.values
                .filter {
                    (gap != null || position == null || it.captureKey < position.before!!) &&
                        filter.matches(it.record)
                }.sortedBy { it.captureKey }
        val selected = candidates.takeLast(limit)
        return page(selected, sequence, candidates.size > limit, gap)
    }

    @Synchronized
    override fun replay(
        filter: LogFilter,
        cursor: String?,
        limit: Int,
    ): LogPage {
        require(limit in 1..1000) { "Invalid log limit" }
        refresh()
        val position = cursor?.let(::decodeCursor)
        require(position?.before == null) { "Invalid log replay cursor" }
        val gap = gap(position)
        if (position == null || gap != null) return history(filter, null, limit).copy(gap = gap)
        val changed = records.values.filter { it.version > position.version }.sortedBy { it.version }
        // Advance through nonmatching output too, so a quiet filtered stream keeps a current cursor.
        val scanned = changed.take(limit)
        val watermark = if (changed.size > limit) scanned.last().version else sequence
        return page(scanned.filter { filter.matches(it.record) }, watermark, changed.size > limit, null)
    }

    private fun gap(position: Cursor?): String? =
        when {
            position == null -> null
            position.run != run || position.version > sequence -> "reset"
            position.version < floor -> "retention"
            else -> null
        }

    private fun page(
        selected: List<Stored>,
        watermark: Long,
        more: Boolean,
        gap: String?,
    ): LogPage =
        LogPage(
            selected.map { it.record },
            cursor(watermark),
            selected.minByOrNull { it.captureKey }?.let { encode("1|$run|$watermark|${it.captureKey}") },
            more,
            readers.map { (source, reader) ->
                LogSourceStatus(
                    source,
                    reader.state,
                    records.values
                        .firstOrNull { it.record.source == source }
                        ?.record
                        ?.timestamp,
                )
            },
            gap,
        )

    private fun cursor(value: Long): String = encode("1|$run|$value")

    private fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())

    private fun decodeCursor(value: String): Cursor {
        require(value.length in 1..256) { "Invalid log cursor" }
        val parts = runCatching { String(Base64.getUrlDecoder().decode(value)).split('|') }.getOrNull()
        require(
            parts != null && parts.size in 3..4 && parts[0] == "1" && parts[1].matches(Regex("[A-Za-z0-9-]{1,64}")),
        ) { "Invalid log cursor" }
        val number = parts[2].toLongOrNull()
        require(number != null && number >= 0) { "Invalid log cursor" }
        val before = parts.getOrNull(3)
        require(before == null || before.matches(Regex("\\d{12}\\.\\d{9}~(backend|nginx|aria2)~\\d{20}"))) { "Invalid log cursor" }
        return Cursor(parts[1], number, before)
    }
}
