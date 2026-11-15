package com.ynixt.dmdl.resource.service

import com.ynixt.dmdl.domain.model.LogLevel
import com.ynixt.dmdl.domain.model.LogSource
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal object LogRecordParser {
    private val ansi = Regex("\u001B\\[[;\\d]*m")
    private val dated = Regex("""^(?:\[aria2] )?\d{4}[-/]\d{2}[-/]\d{2}[ T]""")
    private val access = Regex("""^\S+ .*\[\d{2}/[A-Za-z]{3}/\d{4}:""")
    private val severity =
        Regex("""(?:\[|\s)(TRACE|DEBUG|INFO|NOTICE|WARN|WARNING|ERROR|FATAL|CRIT|ALERT|EMERG)(?:]|\s|:)""", RegexOption.IGNORE_CASE)
    private val continuation = Regex("""^(?:\s|Caused by:|Suppressed:|[\w.$]+(?:Exception|Error)(?::|$))""")
    private val iso = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:[.,]\d+)?(?:Z|[+-]\d{2}:?\d{2})""")
    private val local = Regex("""\d{4}[-/]\d{2}[-/]\d{2} \d{2}:\d{2}:\d{2}(?:\.\d+)?""")
    private val accessTime = Regex("""\[(\d{2}/[A-Za-z]{3}/\d{4}:\d{2}:\d{2}:\d{2} [+-]\d{4})]""")

    fun startsRecord(message: String): Boolean {
        val clean = ansi.replace(message, "")
        return dated.containsMatchIn(clean) || access.containsMatchIn(clean) ||
            clean.startsWith("[aria2]") || (clean.isNotEmpty() && !continuation.containsMatchIn(clean))
    }

    fun level(
        source: LogSource,
        message: String,
    ): LogLevel {
        if (source == LogSource.nginx && access.containsMatchIn(message)) return LogLevel.INFO
        return when (
            severity
                .find(ansi.replace(message, ""))
                ?.groupValues
                ?.get(1)
                ?.uppercase()
        ) {
            "TRACE" -> LogLevel.TRACE
            "DEBUG" -> LogLevel.DEBUG
            "INFO", "NOTICE" -> LogLevel.INFO
            "WARN", "WARNING" -> LogLevel.WARN
            "ERROR", "FATAL", "CRIT", "ALERT", "EMERG" -> LogLevel.ERROR
            else -> LogLevel.UNKNOWN
        }
    }

    fun timestamp(
        message: String,
        captured: Instant,
    ): Instant =
        runCatching {
            iso.find(message)?.value?.let { return@runCatching OffsetDateTime.parse(it.replace(',', '.')).toInstant() }
            accessTime.find(message)?.groupValues?.get(1)?.let {
                return@runCatching OffsetDateTime
                    .parse(
                        it,
                        DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss xx", java.util.Locale.ENGLISH),
                    ).toInstant()
            }
            local.find(message)?.value?.let {
                return@runCatching LocalDateTime
                    .parse(
                        it.replace('/', '-'),
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSS]"),
                    ).atZone(ZoneId.systemDefault())
                    .toInstant()
            }
            captured
        }.getOrDefault(captured)
}
