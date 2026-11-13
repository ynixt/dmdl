package com.ynixt.dmdl.application.web.controller

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path

data class StorageDirectory(
    val name: String,
    val path: String,
)

data class StorageDirectories(
    val currentPath: String?,
    val parentPath: String?,
    val roots: List<String>,
    val directories: List<StorageDirectory>,
)

@RestController
@RequestMapping("/api/storage")
class StorageController {
    @GetMapping("/directories")
    fun directories(
        @RequestParam(required = false) path: String?,
    ): StorageDirectories {
        val roots =
            FileSystems
                .getDefault()
                .rootDirectories
                .filter { Files.isDirectory(it) }
                .map { it.toString() }
        if (path.isNullOrBlank()) return StorageDirectories(null, null, roots, emptyList())
        return try {
            var current = Path.of(path)
            require(current.isAbsolute)
            current = current.normalize()
            while (!Files.isDirectory(current)) current = requireNotNull(current.parent)
            val entries =
                Files.list(current).use { children ->
                    children
                        .filter { Files.isDirectory(it) && Files.isReadable(it) }
                        .map { StorageDirectory(it.fileName.toString(), it.toString()) }
                        .toList()
                        .sortedBy { it.name.lowercase() }
                }
            StorageDirectories(current.toString(), current.parent?.toString(), roots, entries)
        } catch (_: Exception) {
            throw IllegalArgumentException("KZ.DIRECTORY_UNAVAILABLE")
        }
    }
}
