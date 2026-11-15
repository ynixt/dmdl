package com.ynixt.dmdl.resource.gateway.files

import com.ynixt.dmdl.domain.gateway.FileFinalizer
import com.ynixt.dmdl.domain.model.DownloadRequest
import com.ynixt.dmdl.domain.model.DownloadState
import com.ynixt.dmdl.domain.model.FinalizationChecks
import com.ynixt.dmdl.domain.model.FinalizationProgress
import com.ynixt.dmdl.domain.model.FinalizationStep
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
class ValidatedFileFinalizer(
    /** Called each time a file is actually read to compute its hash; lets tests count reads. */
    private val hashReads: (Path) -> Unit = {},
) : FileFinalizer {
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
        checks: FinalizationChecks,
        onProgress: (FinalizationProgress) -> Unit,
        saveCheckpoint: (DownloadRequest) -> Unit,
    ): DownloadRequest {
        var current = request
        val hashes = HashCache(onProgress)
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
                val placedHash = placedHash(target, witness, request.execution.outputSize, checks, request.execution.outputSha256, hashes)
                require(placedHash != null) { "Destination conflict: existing output is not owned by this request" }
                onProgress(FinalizationProgress(FinalizationStep.PLACING))
                return placed(request, request.execution.outputSha256 ?: placedHash.takeIf(String::isNotEmpty)).also(saveCheckpoint)
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
        val sameStore = Files.getFileStore(source) == Files.getFileStore(target.parent)
        // On one filesystem the downloaded file becomes the final file through hard links, so one read covers every check.
        val sourceHash =
            if (sameStore &&
                checks.hashDownloadedFile
            ) {
                hashes.of(source, FinalizationStep.HASHING_DOWNLOAD, expected)
            } else {
                null
            }
        if (current.execution.placement == PlacementState.NONE) {
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
                onProgress(FinalizationProgress(FinalizationStep.FLUSHING))
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
                            outputSha256 = sourceHash,
                            placementTemporaryPath = temporary.toString(),
                            placementOwnershipToken = token,
                            placement = PlacementState.PLANNED,
                        ),
                )
            saveCheckpoint(current)
        }
        val recorded = current.execution.outputSha256
        require(expected == current.execution.outputSize && (sourceHash == null || recorded == null || sourceHash == recorded)) {
            "Staging output changed after finalization was planned"
        }
        var reference = sourceHash ?: recorded
        val temporary = witness(current, root)
        if (current.execution.placementOwnershipToken != null) {
            validateMarker(current, temporary)
            require(!Files.isSymbolicLink(temporary)) { "Invalid placement temporary file" }
            // Only the marker-owned private directory may contain an interrupted copy, and it is never trusted: rewrite it.
            val needsHash = checks.hashDownloadedFile || checks.verifyCopy || checks.verifyPlacedFile
            val copiedHash = copy(source, temporary, expected, needsHash, onProgress)
            onProgress(FinalizationProgress(FinalizationStep.FLUSHING))
            force(temporary)
            if (copiedHash != null) {
                require(recorded == null || copiedHash == recorded) { "Staging output changed after finalization was planned" }
                hashes.remember(source, copiedHash)
                reference = copiedHash
            }
            if (checks.verifyCopy) {
                require(hashes.of(temporary, FinalizationStep.VERIFYING_COPY, expected) == reference) {
                    "Placement copy failed verification; staging preserved"
                }
            }
        } else {
            require(
                regular(temporary) && Files.isSameFile(source, temporary),
            ) { "Placement witness is missing or does not belong to staging" }
        }
        require(regular(temporary) && Files.size(temporary) == expected) {
            "Placement copy failed verification; staging preserved"
        }
        renderer.validateParents(root, target)
        onProgress(FinalizationProgress(FinalizationStep.PLACING))
        exclusiveLink(target, temporary)
        val placedHash = placedHash(target, temporary, expected, checks, reference, hashes)
        require(placedHash != null) { "Placed output failed identity verification" }
        // Keep the witness until COMPLETED and notification bookkeeping commit together.
        return placed(current, reference ?: placedHash.takeIf(String::isNotEmpty)).also(saveCheckpoint)
    }

    override fun cleanupCompleted(request: DownloadRequest) {
        if (request.state != DownloadState.COMPLETED || request.execution.placement != PlacementState.PLACED) return
        val root = Path.of(request.organization.storageRoot).toAbsolutePath().normalize()
        val temporary = witness(request, root)
        if (!Files.exists(temporary, NOFOLLOW_LINKS)) return
        val target = Path.of(requireNotNull(request.execution.finalPath))
        renderer.validateParents(root, target)
        require(sameFile(target, temporary, request.execution.outputSize)) { "Completed output identity changed; cleanup refused" }
        val source = Path.of(requireNotNull(request.execution.stagingPath)).toAbsolutePath().normalize()
        val stagingRoot = Path.of(request.organization.stagingRoot).toAbsolutePath().normalize()
        require(source.startsWith(stagingRoot.resolve(request.id.toString())))
        renderer.validateParents(stagingRoot, source)
        if (Files.exists(source, NOFOLLOW_LINKS)) {
            require(regular(source) && Files.size(source) == request.execution.outputSize)
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
                if (sameFile(target, verified, request.execution.outputSize)) Files.delete(target)
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

    private fun placed(
        request: DownloadRequest,
        hash: String?,
    ) = request.copy(
        updatedAt = Instant.now(),
        execution = request.execution.copy(placement = PlacementState.PLACED, outputSha256 = hash),
    )

    private fun regular(path: Path) = Files.isRegularFile(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)

    /** The final file is ours when it is a hard link of the witness with the expected size. */
    private fun sameFile(
        target: Path,
        witness: Path,
        size: Long?,
    ) = regular(target) && regular(witness) && Files.isSameFile(target, witness) && Files.size(target) == size

    /**
     * Proves ownership of the final file and, when the final-file check is on, compares its hash with [reference].
     * Returns null when it is not ours, its hash (from the cache when that file was already read), or "" when unchecked.
     */
    private fun placedHash(
        target: Path,
        witness: Path,
        size: Long?,
        checks: FinalizationChecks,
        reference: String?,
        hashes: HashCache,
    ): String? {
        if (!sameFile(target, witness, size)) return null
        if (!checks.verifyPlacedFile) return ""
        val hash = hashes.of(target, FinalizationStep.VERIFYING_PLACED_FILE, requireNotNull(size))
        return hash.takeIf { reference == null || it == reference }
    }

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

    /** Copies [source] into [target], hashing the bytes as they stream through when [hashing] is set. */
    private fun copy(
        source: Path,
        target: Path,
        size: Long,
        hashing: Boolean,
        onProgress: (FinalizationProgress) -> Unit,
    ): String? {
        val digest = if (hashing) MessageDigest.getInstance("SHA-256") else null
        val report = ThrottledProgress(FinalizationStep.COPYING, size, onProgress)
        Files.newInputStream(source).use { input ->
            val options = arrayOf(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
            Files.newOutputStream(target, *options).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest?.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                    report.add(read)
                }
            }
        }
        report.finish()
        return digest?.let(::hex)
    }

    /** SHA-256 per physical file for one finalization: hard links of an already hashed file are never read again. */
    private inner class HashCache(
        private val onProgress: (FinalizationProgress) -> Unit,
    ) {
        private val known = mutableListOf<Pair<Path, String>>()

        fun of(
            path: Path,
            step: FinalizationStep,
            size: Long,
        ): String {
            known.firstOrNull { (file, _) -> Files.isSameFile(file, path) }?.let { return it.second }
            hashReads(path)
            val digest = MessageDigest.getInstance("SHA-256")
            val report = ThrottledProgress(step, size, onProgress)
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                    report.add(read)
                }
            }
            report.finish()
            return hex(digest).also { remember(path, it) }
        }

        fun remember(
            path: Path,
            hash: String,
        ) {
            known += path to hash
        }
    }

    /** Reports byte progress at most every 500 ms, plus the start and the end of the step. */
    private class ThrottledProgress(
        private val step: FinalizationStep,
        private val total: Long,
        private val onProgress: (FinalizationProgress) -> Unit,
    ) {
        private var processed = 0L
        private var reportedAt = System.nanoTime()

        init {
            onProgress(FinalizationProgress(step, 0, total))
        }

        fun add(bytes: Int) {
            processed += bytes
            val now = System.nanoTime()
            if (now - reportedAt >= REPORT_INTERVAL_NANOS) {
                reportedAt = now
                onProgress(FinalizationProgress(step, processed, total))
            }
        }

        fun finish() = onProgress(FinalizationProgress(step, processed, total))
    }

    private fun hex(digest: MessageDigest) = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }

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

    private companion object {
        const val BUFFER_SIZE = 1024 * 1024
        const val REPORT_INTERVAL_NANOS = 500_000_000L
    }
}
