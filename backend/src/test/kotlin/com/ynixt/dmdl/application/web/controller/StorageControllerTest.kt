package com.ynixt.dmdl.application.web.controller

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StorageControllerTest {
    @TempDir lateinit var root: Path
    private val controller = StorageController()

    @Test
    fun `browses server directories and parents including paths with spaces and Unicode`() {
        val movies = Files.createDirectories(root.resolve("Filmes e séries/SSD"))
        Files.writeString(root.resolve("regular-file.txt"), "fixture")
        val listing = controller.directories(root.toString())
        assertEquals(root.toString(), listing.currentPath)
        assertEquals(root.parent.toString(), listing.parentPath)
        assertEquals(listOf("Filmes e séries"), listing.directories.map { it.name })
        assertEquals(movies.toString(), controller.directories(movies.toString()).currentPath)
        assertEquals(movies.toString(), controller.directories(movies.resolve("new-folder").toString()).currentPath)
        assertTrue(controller.directories(null).roots.isNotEmpty())
        assertEquals(null, controller.directories(null).currentPath)
        assertEquals(
            "KZ.DIRECTORY_UNAVAILABLE",
            assertFailsWith<IllegalArgumentException> { controller.directories("relative/path") }.message,
        )
    }
}
