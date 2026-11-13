package com.ynixt.dmdl.domain.model

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OrganizationRendererTest {
    @TempDir lateinit var root: Path
    private val renderer = OrganizationRenderer()
    private val original = "The Last of Us (2023) S02E07 (2160p MAX WEB-DL H265 DV HDR DDP Atmos 5.1 English - HONE).mkv"

    @Test
    fun `preserves every original filename character for movies series and anime`() {
        MediaCategory.entries.forEach { category ->
            val content = content(category)
            val path = renderer.render(snapshot(category), content, original)
            assertEquals(original, path.fileName.toString())
            val folder =
                if (category ==
                    MediaCategory.MOVIE
                ) {
                    "movies/Example (2024)"
                } else {
                    "${if (category == MediaCategory.ANIME) "anime" else "series"}/Example (2024)/Season 00"
                }
            assertEquals("$folder/$original", root.relativize(path).toString().replace('\\', '/'))
        }
    }

    @Test
    fun `renders literal bracketed provider IDs in preferred order with optional year`() {
        val series =
            content(MediaCategory.SERIES).copy(
                title = "Breaking Bad",
                year = 2008,
                identity = content(MediaCategory.SERIES).identity.copy(mappedIds = mapOf("tvdb" to "81189", "tmdb" to "1396")),
            )
        val path = renderer.render(snapshot(MediaCategory.SERIES), series, "Release.MKV")
        assertEquals(
            "Breaking Bad (2008) [tvdbid-81189]",
            path.parent.parent.fileName
                .toString(),
        )
        val movie =
            content(MediaCategory.MOVIE).copy(
                year = null,
                identity =
                    content(MediaCategory.MOVIE).identity.copy(
                        mappedIds =
                            mapOf("tvdb" to "81189", "tmdb" to "1396"),
                    ),
            )
        assertEquals(
            "Example [tmdbid-1396]",
            renderer
                .render(snapshot(MediaCategory.MOVIE), movie, "Release.MKV")
                .parent.fileName
                .toString(),
        )
        assertEquals("Release.MKV", path.fileName.toString())
    }

    @Test
    fun `uses known alternatives and preserves opaque identity without guessing provider`() {
        val content = content(MediaCategory.MOVIE)
        assertEquals(null, renderer.providerId(content))
        assertEquals(
            "[imdbid-tt123]",
            renderer.providerId(content.copy(identity = content.identity.copy(mappedIds = mapOf("imdb" to "tt123")))),
        )
        assertEquals(null, renderer.providerId(content.copy(identity = content.identity.copy(mappedIds = mapOf("tmdb" to "opaque:123")))))
    }

    @Test
    fun `validates multiple roots and rejects absent defaults duplicate IDs and relative paths`() {
        val settings = OrganizationSettings(categories = OrganizationSettings.defaults(root.toString()))
        renderer.validate(settings)
        val movie = settings.categories.getValue(MediaCategory.MOVIE)
        listOf(
            movie.copy(defaultDestinationId = null),
            movie.copy(destinations = emptyList()),
            movie.copy(destinations = listOf(movie.destinations.single().copy(path = "relative"))),
            movie.copy(destinations = movie.destinations + movie.destinations.single()),
        ).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> {
                renderer.validate(
                    settings.copy(
                        categories =
                            settings.categories + (MediaCategory.MOVIE to invalid),
                    ),
                )
            }
        }
        val second = StorageDestination(UUID.randomUUID(), "SSD", root.resolve("ssd").toString())
        renderer.validate(
            settings.copy(
                categories =
                    settings.categories + (MediaCategory.MOVIE to movie.copy(destinations = movie.destinations + second)),
            ),
        )
    }

    @Test
    fun `accepts optional categories and shared physical destinations with provider ID defaults`() {
        val defaults = OrganizationSettings.defaults(root.toString())
        defaults.values.forEach { assertEquals(true, it.folderTemplate.contains("{providerId}")) }
        val optional =
            OrganizationSettings(
                categories =
                    defaults.mapValues { (_, config) ->
                        config.copy(destinations = emptyList(), defaultDestinationId = null)
                    },
            )
        renderer.validate(optional)
        val mixed =
            OrganizationSettings(
                categories =
                    defaults.mapValues { (_, config) ->
                        config.copy(destinations = config.destinations.map { it.copy(path = root.toString()) })
                    },
            )
        renderer.validate(mixed)
    }

    @Test
    fun `sanitizes only metadata directories and rejects escaping components`() {
        val content = content(MediaCategory.MOVIE).copy(title = "A:B\\C/Title")
        assertEquals(
            "A_B_C_Title (2024)",
            renderer
                .render(snapshot(MediaCategory.MOVIE), content, original)
                .parent.fileName
                .toString(),
        )
        listOf("../escape", "folder/file.mkv", "folder\\file.mkv", ".", "..", "").forEach { invalid ->
            assertFailsWith<IllegalArgumentException> { renderer.render(snapshot(MediaCategory.MOVIE), content, invalid) }
        }
        listOf("{unknown}", "{title:02}", "[{title}", "[[{title}]]", "CON", "../{title}").forEach { invalid ->
            val snapshot = snapshot(MediaCategory.MOVIE)
            assertFailsWith<IllegalArgumentException> {
                renderer.render(snapshot.copy(category = snapshot.category.copy(folderTemplate = invalid)), content, original)
            }
        }
    }

    @Test
    fun `rejects existing symlink parent escaping selected root`() {
        val outside = Files.createDirectory(root.resolve("outside"))
        val storage = Files.createDirectory(root.resolve("storage"))
        val link = runCatching { Files.createSymbolicLink(storage.resolve("Example (2024)"), outside) }.getOrNull()
        org.junit.jupiter.api.Assumptions
            .assumeTrue(link != null, "Symlink creation requires host privileges")
        assertFailsWith<IllegalArgumentException> {
            renderer.render(snapshot(MediaCategory.MOVIE).copy(storageRoot = storage.toString()), content(MediaCategory.MOVIE), original)
        }
    }

    private fun snapshot(category: MediaCategory): OrganizationSnapshot {
        val directory =
            when (category) {
                MediaCategory.MOVIE -> "movies"
                MediaCategory.SERIES -> "series"
                MediaCategory.ANIME -> "anime"
            }
        return OrganizationSnapshot(
            1,
            root.resolve(directory).toString(),
            OrganizationSettings.defaults(root.toString()).getValue(category),
        )
    }

    private fun content(category: MediaCategory): ContentSnapshot =
        ContentSnapshot(
            ContentIdentity(
                UUID.randomUUID(),
                1,
                if (category ==
                    MediaCategory.MOVIE
                ) {
                    "movie"
                } else {
                    "series"
                },
                "opaque",
                if (category == MediaCategory.MOVIE) null else "opaque:0:1",
            ),
            category,
            "Example",
            2024,
            if (category == MediaCategory.MOVIE) null else 0,
            if (category == MediaCategory.MOVIE) null else 1,
        )
}
