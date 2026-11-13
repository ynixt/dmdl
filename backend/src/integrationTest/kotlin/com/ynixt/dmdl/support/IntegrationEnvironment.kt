package com.ynixt.dmdl.support

import com.github.dockerjava.api.command.CreateContainerCmd
import com.github.dockerjava.api.model.ExposedPort
import com.github.dockerjava.api.model.PortBinding
import com.github.dockerjava.api.model.Ports
import com.sun.net.httpserver.HttpServer
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.Testcontainers
import org.testcontainers.containers.BindMode
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.lifecycle.Startables
import org.testcontainers.utility.DockerImageName
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

open class IntegrationEnvironment {
    companion object {
        val data: Path = Files.createTempDirectory("dmdl-it-")
        val media: Path = Files.createDirectories(data.resolve("media"))
        val calls = CopyOnWriteArrayList<String>()

        @Volatile var refreshStatus = 204

        @Volatile var slowMedia = false
        val failMedia = AtomicInteger(0)
        val payload =
            ByteArray(128 * 1024).apply {
                this[0] = 0x1a
                this[1] = 0x45
                this[2] = 0xdf.toByte()
                this[3] = 0xa3.toByte()
            }
        private val json =
            tools.jackson.databind.json.JsonMapper
                .builder()
                .build()
        val http: HttpServer =
            HttpServer.create(InetSocketAddress("0.0.0.0", 0), 0).apply {
                executor = Executors.newVirtualThreadPerTaskExecutor()
                createContext("/") { exchange ->
                    val path = exchange.requestURI.path
                    val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
                    calls += "${exchange.requestMethod} $path"
                    if (path == "/file.mkv") {
                        if (failMedia.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) {
                            exchange.sendResponseHeaders(401, -1)
                            exchange.close()
                            return@createContext
                        }
                        val range = exchange.requestHeaders.getFirst("Range")
                        val start = range?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                        exchange.responseHeaders.add("Content-Type", "video/x-matroska")
                        exchange.responseHeaders.add(
                            "Content-Disposition",
                            "attachment; filename=Example.S00E01.2160p.WEB-DL.H265.DV.HDR.DDP.Atmos.5.1-HONE.mkv",
                        )
                        exchange.responseHeaders.add("Accept-Ranges", "bytes")
                        if (range !=
                            null
                        ) {
                            exchange.responseHeaders.add("Content-Range", "bytes $start-${payload.lastIndex}/${payload.size}")
                        }
                        exchange.sendResponseHeaders(if (range == null) 200 else 206, (payload.size - start).toLong())
                        runCatching {
                            exchange.responseBody.use { output ->
                                if (slowMedia) {
                                    var offset = start
                                    while (offset < payload.size) {
                                        val size = minOf(1024, payload.size - offset)
                                        output.write(payload, offset, size)
                                        output.flush()
                                        offset += size
                                        Thread.sleep(20)
                                    }
                                } else {
                                    output.write(payload, start, payload.size - start)
                                }
                            }
                        }
                    } else {
                        var status = 200
                        val response: Any =
                            when {
                                path.endsWith("/manifest.json") -> {
                                    if (path.startsWith("/metadata")) {
                                        mapOf(
                                            "id" to "fixture.meta",
                                            "name" to "Metadata",
                                            "types" to listOf("movie", "series"),
                                            "resources" to
                                                if (path.startsWith("/metadata-replacement")) {
                                                    listOf(
                                                        mapOf("name" to "meta", "types" to listOf("movie", "series")),
                                                        mapOf(
                                                            "name" to "catalog",
                                                        ),
                                                    )
                                                } else {
                                                    listOf("meta", "catalog")
                                                },
                                            "catalogs" to
                                                listOf(
                                                    mapOf(
                                                        "id" to "custom-search",
                                                        "type" to "movie",
                                                        "extra" to listOf(mapOf("name" to "search")),
                                                    ),
                                                    mapOf(
                                                        "id" to "anime-discovery",
                                                        "type" to "series",
                                                        "extra" to listOf(mapOf("name" to "search")),
                                                    ),
                                                ),
                                        )
                                    } else {
                                        mapOf(
                                            "id" to path.substringBeforeLast('/'),
                                            "name" to path.split('/')[1],
                                            "types" to listOf("movie", "series"),
                                            "resources" to
                                                listOf(
                                                    mapOf(
                                                        "name" to "stream",
                                                        "types" to listOf("movie", "series"),
                                                        "idPrefixes" to
                                                            if (path.startsWith("/imdb")) listOf("tt") else listOf("opaque:"),
                                                    ),
                                                ),
                                        )
                                    }
                                }

                                path.contains("/catalog/") -> {
                                    mapOf(
                                        "metas" to listOf(mapOf("id" to "opaque:movie", "type" to "movie", "name" to "Example")),
                                    )
                                }

                                path.contains("/meta/") -> {
                                    val id = path.substringAfterLast('/').removeSuffix(".json")
                                    val type = path.substringAfter("/meta/").substringBefore('/')
                                    val prefix = if (path.startsWith("/metadata-replacement")) "Replacement" else "Example"
                                    mapOf(
                                        "meta" to
                                            mapOf(
                                                "id" to id,
                                                "type" to type,
                                                "name" to "$prefix ${id.substringAfterLast(':')}",
                                                "year" to 2024,
                                                "tmdb_id" to 1396,
                                                "tvdb_id" to 81189,
                                                "videos" to
                                                    if (type ==
                                                        "movie"
                                                    ) {
                                                        emptyList()
                                                    } else {
                                                        listOf(
                                                            mapOf(
                                                                "id" to "$id:special-one",
                                                                "season" to 0,
                                                                "episode" to 1,
                                                                "title" to "Arrival",
                                                            ),
                                                        )
                                                    },
                                            ),
                                    )
                                }

                                path.contains("/stream/") -> {
                                    if (path.startsWith("/slow")) Thread.sleep(100)
                                    if (path.startsWith("/direct")) {
                                        mapOf(
                                            "streams" to
                                                listOf(
                                                    mapOf(
                                                        "title" to "Provider-resolved HTTP",
                                                        "url" to daemonFile(),
                                                        "streamData" to mapOf("type" to "debrid", "service" to mapOf("id" to "torbox")),
                                                    ),
                                                ),
                                        )
                                    } else if (path.startsWith("/error")) {
                                        status = 503
                                        mapOf("error" to "fixture-secret")
                                    } else {
                                        mapOf(
                                            "streams" to
                                                listOf(
                                                    mapOf(
                                                        "title" to "HTTP release",
                                                        "url" to daemonFile(),
                                                        "streamData" to mapOf("service" to mapOf("id" to "torbox")),
                                                    ),
                                                    mapOf(
                                                        "title" to "Ordered release",
                                                        "url" to daemonFile(),
                                                        "infoHash" to "a".repeat(40),
                                                    ),
                                                ),
                                        )
                                    }
                                }

                                path.endsWith("/Library/Refresh") -> {
                                    status = refreshStatus
                                    emptyMap<String, String>()
                                }

                                path.endsWith("/System/Info") -> {
                                    mapOf("ServerName" to "External fixture")
                                }

                                else -> {
                                    status = 404
                                    emptyMap<String, String>()
                                }
                            }
                        val bytes = json.writeValueAsBytes(response)
                        exchange.responseHeaders.add("Content-Type", "application/json")
                        exchange.sendResponseHeaders(status, if (status == 204) -1 else bytes.size.toLong())
                        exchange.responseBody.use { if (status != 204) it.write(bytes) }
                    }
                }
                start()
            }
        val redis: GenericContainer<*> = GenericContainer(DockerImageName.parse("redis:8.10.3-alpine")).withExposedPorts(6379)
        private val aria2HostPort = java.net.ServerSocket(0).use { it.localPort }
        val aria2: GenericContainer<*> =
            GenericContainer(
                ImageFromDockerfile("dmdl-aria2-test", false)
                    .withFileFromPath("Dockerfile", Path.of("../docker/aria2/Dockerfile"))
                    .withFileFromPath("start-aria2.sh", Path.of("../docker/aria2/start-aria2.sh"))
                    .withFileFromPath("allocation-readout.awk", Path.of("../docker/aria2/allocation-readout.awk"))
                    .withFileFromPath("aria2-healthcheck.sh", Path.of("../docker/aria2/aria2-healthcheck.sh")),
            ).withExposedPorts(6800)
                .withEnv("DMDL_ARIA2_RPC_SECRET", "fixture-rpc-secret")
                .withEnv("DMDL_ARIA2_SAVE_SESSION_INTERVAL", "1")
                .withCreateContainerCmdModifier(fixedPortBinding(aria2HostPort))
                .withFileSystemBind(
                    Files.createDirectories(media.resolve(".dmdl")).toString(),
                    "/downloads/.dmdl",
                    BindMode.READ_WRITE,
                ).waitingFor(Wait.forListeningPort())

        init {
            Testcontainers.exposeHostPorts(http.address.port)
            Startables.deepStart(redis, aria2).join()
        }

        private fun fixedPortBinding(port: Int) =
            java.util.function.Consumer<CreateContainerCmd> { command ->
                command.hostConfig!!.withPortBindings(PortBinding(Ports.Binding.bindPort(port), ExposedPort(6800)))
            }

        fun base() = "http://127.0.0.1:${http.address.port}"

        private fun daemonFile() = "http://host.testcontainers.internal:${http.address.port}/file.mkv"

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("application.database.path") { data.resolve("application.db").toString() }
            registry.add("application.storage.root") { media.toString() }
            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { redis.getMappedPort(6379) }
            registry.add("spring.data.redis.password") { "" }
            registry.add("application.aria2.rpc-url") { "http://${aria2.host}:${aria2.getMappedPort(6800)}/jsonrpc" }
            registry.add("application.aria2.rpc-secret") { "fixture-rpc-secret" }
            registry.add("application.aria2.application-staging-root") { media.resolve(".dmdl").toString() }
            registry.add("application.aria2.daemon-staging-root") { "/downloads/.dmdl" }
            registry.add("application.aria2.poll-interval") { "100ms" }
        }
    }
}
