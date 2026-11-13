package com.ynixt.dmdl.domain.model

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Bounded template language; the same renderer is used for previews and final output. */
class OrganizationRenderer {
    private val placeholder = Regex("\\{(title|year|season|episode|episodeTitle|providerId)(?::(0[1-4]))?}")
    private val forbidden = Regex("[<>:\"/\\\\|?*\\x00-\\x1f\\x7f]")
    private val reserved = Regex("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?")

    fun render(
        snapshot: OrganizationSnapshot,
        content: ContentSnapshot,
        originalFileName: String,
    ): Path {
        val config = snapshot.category
        validateBasename(originalFileName)
        val values =
            mapOf(
                "title" to safeComponent(content.title),
                "year" to content.year?.toString(),
                "season" to content.season?.toString(),
                "episode" to content.episode?.toString(),
                "episodeTitle" to content.episodeTitle?.takeIf(String::isNotBlank)?.let(::safeComponent),
                "providerId" to providerId(content),
            )
        val folder = template(config.folderTemplate, values)
        validateRelative(folder)
        val root = Path.of(snapshot.storageRoot).toAbsolutePath().normalize()
        require(Path.of(snapshot.storageRoot).isAbsolute) { "Storage root must be absolute" }
        val result =
            root
                .resolve(folder)
                .resolve(originalFileName)
                .normalize()
        require(result.startsWith(root) && result != root) { "Destination escapes storage root" }
        validateParents(root, result)
        return result
    }

    fun validate(
        settings: OrganizationSettings,
        checkStorage: (String) -> Unit = {},
    ) {
        val ids =
            settings.categories.values
                .flatMap { it.destinations }
                .map { it.id }
        require(ids.distinct().size == ids.size) { "Destination identifiers must be unique" }
        settings.categories.forEach { (category, config) ->
            require(
                if (config.destinations.isEmpty()) {
                    config.defaultDestinationId == null
                } else {
                    config.destinations.any {
                        it.id ==
                            config.defaultDestinationId
                    }
                },
            ) { "Choose a default destination for $category or leave this category without folders" }
            config.destinations.forEach { destination ->
                require(destination.name.isNotBlank()) { "Destination name is required" }
                require(Path.of(destination.path).isAbsolute) { "Destination path must be absolute" }
                checkStorage(destination.path)
                listOf(2024, null).forEach { year ->
                    val episodic = category != MediaCategory.MOVIE
                    val content =
                        ContentSnapshot(
                            ContentIdentity(
                                java.util.UUID(0, 1),
                                1,
                                if (episodic) "series" else "movie",
                                "preview",
                                if (episodic) "preview:0:1" else null,
                            ),
                            category,
                            "Example",
                            year,
                            if (episodic) 0 else null,
                            if (episodic) 1 else null,
                        )
                    render(OrganizationSnapshot(settings.revision, destination.path, config), content, "Example.Release.mkv")
                }
            }
        }
    }

    fun providerId(content: ContentSnapshot): String? {
        val order = if (content.category == MediaCategory.MOVIE) listOf("tmdb", "tvdb", "imdb") else listOf("tvdb", "tmdb", "imdb")
        return order.firstNotNullOfOrNull { provider ->
            content.identity.mappedIds[provider]
                ?.takeIf {
                    if (provider == "imdb") it.matches(Regex("tt[0-9]+")) else it.matches(Regex("[1-9][0-9]*"))
                }?.let { "[${provider}id-$it]" }
        }
    }

    fun validateBasename(value: String) {
        require(value.isNotBlank() && value !in setOf(".", "..") && '/' !in value && '\\' !in value && '\u0000' !in value) {
            "Original filename must be one valid filename component"
        }
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            require(!forbidden.containsMatchIn(value) && !reserved.matches(value) && value == value.trimEnd('.', ' ')) {
                "Original filename is unsupported by the destination filesystem"
            }
        }
    }

    /** Resolve every existing ancestor, including Windows junctions, before placement. */
    fun validateParents(
        root: Path,
        target: Path,
    ) {
        val normalizedRoot = root.toAbsolutePath().normalize()
        require(target.toAbsolutePath().normalize().startsWith(normalizedRoot)) { "Destination escapes storage root" }
        val rootReal = realThroughExistingParent(normalizedRoot)
        var candidate = normalizedRoot
        normalizedRoot.relativize(target.toAbsolutePath().normalize()).forEach { component ->
            candidate = candidate.resolve(component)
            if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                val real = candidate.toRealPath()
                require(real.startsWith(rootReal)) { "Destination parent escapes storage root through a link" }
            }
        }
    }

    private fun realThroughExistingParent(path: Path): Path {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return path.toRealPath()
        return realThroughExistingParent(requireNotNull(path.parent)).resolve(path.fileName).normalize()
    }

    private fun template(
        input: String,
        values: Map<String, String?>,
    ): String {
        require(input.isNotBlank()) { "Templates cannot be empty" }
        val result = StringBuilder()
        var cursor = 0
        while (cursor < input.length) {
            when (input[cursor]) {
                '[' -> {
                    val end = input.indexOf(']', cursor + 1)
                    require(end > cursor && '[' !in input.substring(cursor + 1, end)) { "Malformed optional template segment" }
                    val segment = input.substring(cursor + 1, end)
                    result.append(expand(segment, values, optional = true))
                    cursor = end + 1
                }

                ']' -> {
                    throw IllegalArgumentException("Unmatched optional template segment")
                }

                else -> {
                    val end = input.indexOfAny(charArrayOf('[', ']'), cursor).let { if (it < 0) input.length else it }
                    result.append(expand(input.substring(cursor, end), values, optional = false))
                    cursor = end
                }
            }
        }
        return result.toString().replace('\\', '/')
    }

    private fun expand(
        input: String,
        values: Map<String, String?>,
        optional: Boolean,
    ): String {
        val residual = placeholder.replace(input, "")
        require('{' !in residual && '}' !in residual) { "Unknown placeholder or invalid numeric formatting" }
        val matches = placeholder.findAll(input).toList()
        matches.forEach { match ->
            require(match.groupValues[2].isEmpty() || match.groupValues[1] in setOf("season", "episode")) {
                "Padding is supported only for season and episode"
            }
        }
        if (optional && matches.any { values[it.groupValues[1]] == null }) return ""
        return placeholder.replace(input) { match ->
            val value = requireNotNull(values[match.groupValues[1]]) { "Missing required template value: ${match.groupValues[1]}" }
            val padding = match.groupValues[2].takeIf(String::isNotEmpty)?.toInt()
            if (padding != null) value.padStart(padding, '0') else value
        }
    }

    private fun safeComponent(value: String): String {
        val safe =
            value
                .replace(forbidden, "_")
                .trim()
                .trimEnd('.', ' ')
                .take(150)
                .ifEmpty { "Untitled" }
        return if (reserved.matches(safe)) "_$safe" else safe
    }

    private fun validateRelative(value: String) {
        require(value.isNotBlank() && !value.startsWith('/') && !value.startsWith('\\') && !Regex("^[A-Za-z]:").containsMatchIn(value)) {
            "Destination must be relative to storage"
        }
        value.replace('\\', '/').split('/').forEach(::validateComponent)
    }

    private fun validateComponent(value: String) {
        require(
            value.isNotBlank() &&
                value !in setOf(".", "..") &&
                value.length <= 200 &&
                !forbidden.containsMatchIn(value) &&
                !reserved.matches(value) &&
                value == value.trimEnd('.', ' '),
        ) { "Invalid or reserved filename component" }
    }
}
