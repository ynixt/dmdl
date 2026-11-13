package com.ynixt.dmdl.resource.gateway.files

import com.ynixt.dmdl.domain.gateway.FileFinalizer
import com.ynixt.dmdl.domain.model.DownloadRequest
import com.ynixt.dmdl.domain.model.DownloadState
import com.ynixt.dmdl.domain.model.OrganizationRenderer
import com.ynixt.dmdl.domain.model.PlacementState
import org.springframework.stereotype.Component
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** Transfer/resumption belongs to aria2; this adapter owns only validated final placement. */
@Component
class ValidatedFileFinalizer : FileFinalizer {
    private val renderer = OrganizationRenderer()

    override fun checkStorage(root: String) {
        val storage = Path.of(root).toAbsolutePath().normalize()
        Files.createDirectories(storage)
        val directory = Files.createTempDirectory(storage, ".kz-probe-")
        val source = directory.resolve("source")
        val link = directory.resolve("link")
        try {
            durableWrite(source, byteArrayOf(1))
            exclusiveLink(link, source)
            require(Files.isSameFile(source, link)) { "Storage does not support recoverable placement" }
        } finally {
            Files.deleteIfExists(link)
            Files.deleteIfExists(source)
            Files.deleteIfExists(directory)
        }
    }

    override fun finalize(
        request: DownloadRequest,
        saveCheckpoint: (DownloadRequest) -> Unit,
    ): DownloadRequest {
        var current = request
        val root = Path.of(request.organization.storageRoot).toAbsolutePath().normalize()
        val stagingRoot = Path.of(request.organization.stagingRoot).toAbsolutePath().normalize()
        require(
            Files.isDirectory(root) && Files.isWritable(root),
        ) { "Selected destination is unavailable or not writable; staging preserved" }
        val source =
            Path
                .of(
                    requireNotNull(request.execution.stagingPath) { "Transfer has no recorded output" },
                ).toAbsolutePath()
                .normalize()
        require(
            source.startsWith(stagingRoot.resolve(request.id.toString())),
        ) { "Staging output does not belong to this request" }
        renderer.validateParents(stagingRoot, source)
        if (request.execution.placement != PlacementState.NONE) {
            val target = Path.of(requireNotNull(request.execution.finalPath))
            renderer.validateParents(root, target)
            val witness = witness(request, root)
            if (Files.exists(target, NOFOLLOW_LINKS)) {
                require(owns(target, witness, request)) { "Destination conflict: existing output is not owned by this request" }
                return placed(request).also(saveCheckpoint)
            }
        }
        require(regular(source)) { "Completed transfer output is missing or invalid" }
        val expected = request.progress.totalBytes
        require(expected != null && expected > 0 && Files.size(source) == expected && request.progress.completedBytes == expected) {
            "Completed output size does not match the transfer"
        }
        actualExtension(source)
        val target =
            renderer.render(
                request.organization,
                request.content,
                requireNotNull(request.execution.originalFileName) {
                    "Original selected filename is missing"
                },
            )
        require(
            request.execution.finalPath == null || target.toString() == request.execution.finalPath,
        ) { "Finalization checkpoint path changed" }
        require(!Files.exists(target, NOFOLLOW_LINKS)) { "Destination conflict: existing file preserved" }
        Files.createDirectories(target.parent)
        renderer.validateParents(root, target)
        val hash = sha256(source)
        if (current.execution.placement == PlacementState.NONE) {
            val sameStore = Files.getFileStore(source) == Files.getFileStore(target.parent)
            val token = if (sameStore) null else UUID.randomUUID().toString()
            val temporary =
                if (sameStore) {
                    source.parent.resolve(".placement-witness")
                } else {
                    val directory = Files.createTempDirectory(target.parent, ".kz-")
                    durableWrite(directory.resolve(".owner"), "${request.id}:$token".toByteArray())
                    directory.resolve("output.part")
                }
            if (sameStore) {
                if (Files.exists(temporary, NOFOLLOW_LINKS)) {
                    require(regular(temporary) && Files.isSameFile(source, temporary)) { "Unknown placement witness preserved" }
                } else {
                    exclusiveLink(temporary, source)
                }
                force(source)
            }
            current =
                request.copy(
                    state = DownloadState.FINALIZING,
                    updatedAt = Instant.now(),
                    execution =
                        request.execution.copy(
                            finalPath = target.toString(),
                            outputSize = expected,
                            outputSha256 = hash,
                            placementTemporaryPath = temporary.toString(),
                            placementOwnershipToken = token,
                            placement = PlacementState.PLANNED,
                        ),
                )
            saveCheckpoint(current)
        }
        require(hash == current.execution.outputSha256 && expected == current.execution.outputSize) {
            "Staging output changed after finalization was planned"
        }
        val temporary = witness(current, root)
        if (current.execution.placementOwnershipToken != null) {
            validateMarker(current, temporary)
            // Only the marker-owned private directory may contain an interrupted copy.
            if (!regular(temporary) || Files.size(temporary) != expected || sha256(temporary) != hash) {
                require(!Files.isSymbolicLink(temporary)) { "Invalid placement temporary file" }
                Files.newInputStream(source).use { input ->
                    Files
                        .newOutputStream(
                            temporary,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE,
                        ).use(input::copyTo)
                }
                force(temporary)
            }
        } else {
            require(
                regular(temporary) && Files.isSameFile(source, temporary),
            ) { "Placement witness is missing or does not belong to staging" }
        }
        require(regular(temporary) && Files.size(temporary) == expected && sha256(temporary) == hash) {
            "Placement copy failed verification; staging preserved"
        }
        renderer.validateParents(root, target)
        exclusiveLink(target, temporary)
        require(owns(target, temporary, current)) { "Placed output failed identity verification" }
        // Keep the witness until COMPLETED and notification bookkeeping commit together.
        return placed(current).also(saveCheckpoint)
    }

    override fun cleanupCompleted(request: DownloadRequest) {
        if (request.state != DownloadState.COMPLETED || request.execution.placement != PlacementState.PLACED) return
        val root = Path.of(request.organization.storageRoot).toAbsolutePath().normalize()
        val temporary = witness(request, root)
        if (!Files.exists(temporary, NOFOLLOW_LINKS)) return
        val target = Path.of(requireNotNull(request.execution.finalPath))
        renderer.validateParents(root, target)
        require(owns(target, temporary, request)) { "Completed output identity changed; cleanup refused" }
        val source = Path.of(requireNotNull(request.execution.stagingPath)).toAbsolutePath().normalize()
        val stagingRoot = Path.of(request.organization.stagingRoot).toAbsolutePath().normalize()
        require(source.startsWith(stagingRoot.resolve(request.id.toString())))
        renderer.validateParents(stagingRoot, source)
        if (Files.exists(source, NOFOLLOW_LINKS)) {
            require(
                regular(source) && Files.size(source) == request.execution.outputSize && sha256(source) == request.execution.outputSha256,
            )
            Files.delete(source)
        }
        Files.delete(temporary)
        if (request.execution.placementOwnershipToken != null) {
            validateMarker(request, temporary)
            Files.delete(temporary.parent.resolve(".owner"))
            // Non-recursive removal preserves any unexpected artifacts.
            Files.delete(temporary.parent)
        }
    }

    override fun cleanupCancelled(request: DownloadRequest) {
        if (request.state != DownloadState.CANCELLED) return
        val stagingRoot = Path.of(request.organization.stagingRoot).toAbsolutePath().normalize()
        val directory = stagingRoot.resolve(request.id.toString()).normalize()
        require(directory.parent == stagingRoot && directory.startsWith(stagingRoot))
        renderer.validateParents(stagingRoot, directory)
        require(!Files.isSymbolicLink(directory)) { "Cancelled staging directory is a link" }
        if (Files.exists(directory, NOFOLLOW_LINKS)) {
            require(
                Files.isDirectory(directory, NOFOLLOW_LINKS) &&
                    directory.toRealPath() == stagingRoot.toRealPath().resolve(request.id.toString()),
            ) {
                "Cancelled staging directory does not belong to this request"
            }
        }
        request.execution.placementTemporaryPath?.let { path ->
            val temporary = Path.of(path).toAbsolutePath().normalize()
            if (Files.exists(temporary.parent, NOFOLLOW_LINKS)) {
                val root = Path.of(request.organization.storageRoot).toAbsolutePath().normalize()
                val target = Path.of(requireNotNull(request.execution.finalPath)).toAbsolutePath().normalize()
                renderer.validateParents(root, target)
                renderer.validateParents(root.takeIf { request.execution.placementOwnershipToken != null } ?: stagingRoot, temporary)
                if (request.execution.placementOwnershipToken != null &&
                    !Files.exists(temporary.parent.resolve(".owner"), NOFOLLOW_LINKS)
                ) {
                    require(
                        temporary.parent.parent == target.parent &&
                            temporary.parent.fileName
                                .toString()
                                .startsWith(".kz-"),
                    )
                    // A previous attempt may have removed the marker before its empty directory.
                    Files.delete(temporary.parent)
                    return@let
                }
                val verified = witness(request, root)
                if (owns(target, verified, request)) Files.delete(target)
                if (request.execution.placementOwnershipToken != null) {
                    Files.deleteIfExists(verified)
                    Files.deleteIfExists(verified.parent.resolve(".owner"))
                    Files.delete(verified.parent)
                }
            }
        }
        if (!Files.exists(directory, NOFOLLOW_LINKS)) return
        val ownedDirectory = directory.toRealPath()
        Files.walkFileTree(
            directory,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    require(dir.toRealPath().startsWith(ownedDirectory)) { "Cancelled staging directory contains an escaping link" }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    require(file.toAbsolutePath().normalize().startsWith(directory))
                    require(file.parent.toRealPath().startsWith(ownedDirectory))
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    dir: Path,
                    error: java.io.IOException?,
                ): FileVisitResult {
                    if (error != null) throw error
                    require(dir.toAbsolutePath().normalize().startsWith(directory))
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private fun witness(
        request: DownloadRequest,
        root: Path,
    ): Path {
        val temporary =
            Path
                .of(
                    requireNotNull(request.execution.placementTemporaryPath) {
                        "Finalization has no ownership witness"
                    },
                ).toAbsolutePath()
                .normalize()
        renderer.validateParents(
            if (request.execution.placementOwnershipToken ==
                null
            ) {
                Path.of(request.organization.stagingRoot)
            } else {
                root
            },
            temporary,
        )
        if (request.execution.placementOwnershipToken != null) {
            validateMarker(request, temporary)
        } else {
            require(
                temporary ==
                    Path
                        .of(requireNotNull(request.execution.stagingPath))
                        .toAbsolutePath()
                        .normalize()
                        .parent
                        .resolve(".placement-witness"),
            )
        }
        return temporary
    }

    private fun validateMarker(
        request: DownloadRequest,
        temporary: Path,
    ) {
        val target = Path.of(requireNotNull(request.execution.finalPath))
        require(
            temporary.parent.parent == target.parent &&
                temporary.parent.fileName
                    .toString()
                    .startsWith(".kz-"),
        )
        val marker = temporary.parent.resolve(".owner")
        require(regular(marker) && Files.readString(marker) == "${request.id}:${request.execution.placementOwnershipToken}") {
            "Unknown placement directory preserved"
        }
    }

    private fun placed(request: DownloadRequest) =
        request.copy(updatedAt = Instant.now(), execution = request.execution.copy(placement = PlacementState.PLACED))

    private fun regular(path: Path) = Files.isRegularFile(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)

    private fun owns(
        target: Path,
        witness: Path,
        request: DownloadRequest,
    ) = regular(target) &&
        regular(witness) &&
        Files.isSameFile(target, witness) &&
        Files.size(target) == request.execution.outputSize &&
        sha256(target) == request.execution.outputSha256

    private fun exclusiveLink(
        target: Path,
        source: Path,
    ) {
        try {
            Files.createLink(target, source)
        } catch (_: FileAlreadyExistsException) {
            throw IllegalArgumentException("Destination conflict: existing file preserved")
        } catch (_: UnsupportedOperationException) {
            throw IllegalArgumentException("Storage must support hard links for recoverable finalization; staging preserved")
        } catch (_: java.nio.file.FileSystemException) {
            throw IllegalArgumentException(
                "Could not place original filename ${target.fileName}; check destination filename limits, hard-link support and write access; staging preserved",
            )
        }
    }

    private fun durableWrite(
        path: Path,
        bytes: ByteArray,
    ) {
        Files.write(path, bytes, StandardOpenOption.CREATE_NEW)
        force(path)
    }

    private fun force(path: Path) {
        FileChannel.open(path, StandardOpenOption.WRITE).use { it.force(true) }
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val size = input.read(buffer)
                if (size <
                    0
                ) {
                    break
                }
                digest.update(buffer, 0, size)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun actualExtension(path: Path): String {
        val header = Files.newInputStream(path).use { it.readNBytes(512) }
        require(header.size >= 12) { "Output is too short to be a supported media file" }
        return when {
            header.take(4).map {
                it.toInt() and 255
            } == listOf(0x1a, 0x45, 0xdf, 0xa3) -> {
                if (header.toString(Charsets.ISO_8859_1).contains("webm")) "webm" else "mkv"
            }

            header.copyOfRange(4, 8).toString(Charsets.US_ASCII) == "ftyp" -> {
                if (header.copyOfRange(8, 12).toString(Charsets.US_ASCII) ==
                    "qt  "
                ) {
                    "mov"
                } else {
                    "mp4"
                }
            }

            header.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
                header.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "AVI " -> {
                "avi"
            }

            header[0] == 0x47.toByte() && header.size > 376 && header[188] == 0x47.toByte() && header[376] == 0x47.toByte() -> {
                "ts"
            }

            else -> {
                throw IllegalArgumentException("Output is not a recognized media container; staging file preserved")
            }
        }
    }
}
