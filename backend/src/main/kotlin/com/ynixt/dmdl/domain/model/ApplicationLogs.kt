package com.ynixt.dmdl.domain.model

import java.time.Instant

@Suppress("ktlint:standard:enum-entry-name-case")
enum class LogSource { backend, nginx, aria2 }

enum class LogLevel { TRACE, DEBUG, INFO, WARN, ERROR, UNKNOWN }

data class LogRecord(
    val id: String,
    val timestamp: Instant,
    val source: LogSource,
    val severity: LogLevel,
    val message: String,
    val truncated: Boolean = false,
)

data class LogFilter(
    val source: LogSource? = null,
    val text: String = "",
    val levels: Set<LogLevel> = emptySet(),
) {
    fun matches(record: LogRecord): Boolean =
        (source == null || source == record.source) &&
            (levels.isEmpty() || record.severity in levels) &&
            (text.isBlank() || record.message.contains(text, ignoreCase = true))
}

data class LogSourceStatus(
    val source: LogSource,
    val state: String,
    val oldestTimestamp: Instant?,
)

data class LogPage(
    val records: List<LogRecord>,
    val cursor: String,
    val olderCursor: String?,
    val hasMore: Boolean,
    val sources: List<LogSourceStatus>,
    val gap: String? = null,
)
