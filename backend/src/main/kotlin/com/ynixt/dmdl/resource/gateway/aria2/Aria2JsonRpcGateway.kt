package com.ynixt.dmdl.resource.gateway.aria2

import com.ynixt.dmdl.application.config.Aria2Properties
import com.ynixt.dmdl.domain.gateway.Aria2Gateway
import com.ynixt.dmdl.domain.model.Aria2TransferFile
import com.ynixt.dmdl.domain.model.Aria2TransferOptions
import com.ynixt.dmdl.domain.model.Aria2TransferState
import com.ynixt.dmdl.domain.model.Aria2TransferStatus
import com.ynixt.dmdl.domain.model.Aria2Version
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

@Component
class Aria2JsonRpcGateway(
    private val properties: Aria2Properties,
    private val objectMapper: ObjectMapper,
) : Aria2Gateway {
    private val client =
        HttpClient
            .newBuilder()
            .connectTimeout(properties.connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

    override fun addUri(
        uri: String,
        gid: String,
        directory: String,
        options: Aria2TransferOptions,
    ): String {
        val rpcOptions =
            mapOf(
                "gid" to gid,
                "dir" to directory,
                "continue" to "true",
                "auto-file-renaming" to "false",
                "allow-overwrite" to "false",
            ) + options.toRpcOptions()
        return rpc("aria2.addUri", listOf(listOf(uri), rpcOptions)).asString()
    }

    override fun findStatus(gid: String): Aria2TransferStatus? =
        try {
            val node =
                rpc(
                    "aria2.tellStatus",
                    listOf(
                        gid,
                        listOf(
                            "gid",
                            "status",
                            "totalLength",
                            "completedLength",
                            "downloadSpeed",
                            "connections",
                            "errorCode",
                            "errorMessage",
                            "dir",
                            "files",
                        ),
                    ),
                )
            node.toStatus()
        } catch (error: Aria2RpcException) {
            if (error.message?.contains("not found", ignoreCase = true) == true) null else throw error
        }

    override fun replaceUris(
        gid: String,
        uri: String,
    ) {
        val currentUris = rpc("aria2.getUris", listOf(gid))
        val urisToDelete = currentUris.mapNotNull { item -> item["uri"]?.asString() }
        rpc("aria2.changeUri", listOf(gid, 1, urisToDelete, listOf(uri), 0))
    }

    override fun pause(gid: String) {
        rpc("aria2.pause", listOf(gid))
    }

    override fun unpause(gid: String) {
        rpc("aria2.unpause", listOf(gid))
    }

    override fun changeOptions(
        gid: String,
        options: Aria2TransferOptions,
    ) {
        rpc("aria2.changeOption", listOf(gid, options.toRpcOptions()))
    }

    override fun changeGlobalMaxConcurrentDownloads(
        maxConcurrentDownloads: Int,
        diskCacheMib: Int,
    ) {
        rpc(
            "aria2.changeGlobalOption",
            listOf(
                mapOf(
                    "max-concurrent-downloads" to maxConcurrentDownloads.toString(),
                    "disk-cache" to "${diskCacheMib}M",
                ),
            ),
        )
    }

    override fun sessionId(): String = rpc("aria2.getSessionInfo", emptyList())["sessionId"]?.asString().orEmpty()

    override fun forceRemove(gid: String) {
        rpc("aria2.forceRemove", listOf(gid))
    }

    override fun removeDownloadResult(gid: String) {
        rpc("aria2.removeDownloadResult", listOf(gid))
    }

    override fun version(): Aria2Version {
        val node = rpc("aria2.getVersion", emptyList())
        return Aria2Version(
            version = node["version"]?.asString().orEmpty(),
            enabledFeatures = node["enabledFeatures"]?.toList()?.map(JsonNode::asString).orEmpty(),
        )
    }

    override fun globalOption(name: String): String? = rpc("aria2.getGlobalOption", emptyList())[name]?.asString()

    override fun shutdown() {
        rpc("aria2.shutdown", emptyList())
    }

    override fun forceShutdown() {
        rpc("aria2.forceShutdown", emptyList())
    }

    private fun rpc(
        method: String,
        methodParams: List<Any?>,
        timeout: Duration = properties.requestTimeout,
    ): JsonNode {
        val requestId = UUID.randomUUID().toString()
        val params = listOf("token:${properties.rpcSecret}") + methodParams
        val payload = objectMapper.writeValueAsBytes(Aria2RpcRequest(id = requestId, method = method, params = params))
        val request =
            HttpRequest
                .newBuilder(URI.create(properties.rpcUrl))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build()
        val response =
            try {
                client.send(request, HttpResponse.BodyHandlers.ofByteArray())
            } catch (error: Exception) {
                if (error is InterruptedException) Thread.currentThread().interrupt()
                throw Aria2RpcException(message = "aria2 JSON-RPC request failed for $method", cause = error)
            }
        val root =
            try {
                objectMapper.readTree(response.body())
            } catch (error: Exception) {
                if (response.statusCode() !in 200..299) {
                    throw Aria2RpcException(message = "aria2 JSON-RPC returned HTTP ${response.statusCode()} for $method")
                }
                throw Aria2RpcException(message = "aria2 JSON-RPC returned malformed JSON for $method", cause = error)
            }
        root["error"]?.let { error ->
            val code = error["code"]?.asInt()
            val message = error["message"]?.asString() ?: "unknown JSON-RPC error"
            throw Aria2RpcException(rpcCode = code, message = "aria2 JSON-RPC error for $method: $message")
        }
        if (response.statusCode() !in 200..299) {
            throw Aria2RpcException(message = "aria2 JSON-RPC returned HTTP ${response.statusCode()} for $method")
        }
        return root["result"] ?: throw Aria2RpcException(message = "aria2 JSON-RPC response has no result for $method")
    }

    private fun JsonNode.toStatus(): Aria2TransferStatus =
        Aria2TransferStatus(
            gid = this["gid"]?.asString().orEmpty(),
            state =
                when (this["status"]?.asString()) {
                    "active" -> Aria2TransferState.ACTIVE
                    "waiting" -> Aria2TransferState.WAITING
                    "paused" -> Aria2TransferState.PAUSED
                    "complete" -> Aria2TransferState.COMPLETE
                    "error" -> Aria2TransferState.ERROR
                    "removed" -> Aria2TransferState.REMOVED
                    else -> Aria2TransferState.UNKNOWN
                },
            totalLength = this["totalLength"].strictLong("totalLength"),
            completedLength = this["completedLength"].strictLong("completedLength"),
            downloadSpeed = this["downloadSpeed"].strictLong("downloadSpeed"),
            connections =
                this["connections"]
                    ?.takeIf { it.isString || it.isIntegralNumber }
                    ?.asString()
                    ?.toIntOrNull()
                    ?.takeIf { it >= 0 },
            errorCode = this["errorCode"]?.asString()?.takeIf(String::isNotBlank),
            errorMessage = this["errorMessage"]?.asString()?.takeIf(String::isNotBlank),
            directory = this["dir"]?.asString().orEmpty(),
            files =
                this["files"]
                    ?.toList()
                    ?.map { file ->
                        Aria2TransferFile(
                            path = file["path"]?.asString().orEmpty(),
                            length = file["length"].strictLong("files.length"),
                        )
                    }.orEmpty(),
        )

    private fun JsonNode?.strictLong(field: String): Long {
        val value = this?.asString()?.toLongOrNull()
        return value ?: throw Aria2RpcException(message = "aria2 returned malformed numeric field $field")
    }

    private fun Aria2TransferOptions.toRpcOptions(): Map<String, String> =
        mapOf(
            "max-tries" to maxTries.toString(),
            "retry-wait" to retryWaitSeconds.toString(),
            "file-allocation" to fileAllocation.aria2Value,
            "split" to maxConnectionPerServer.toString(),
            "max-connection-per-server" to maxConnectionPerServer.toString(),
            "min-split-size" to "${minSplitSizeMib}M",
            "disable-ipv6" to (!ipv6Enabled).toString(),
            "lowest-speed-limit" to if (lowestSpeedLimitEnabled) "${lowestSpeedLimitKib}K" else "0",
            "timeout" to timeoutSeconds.toString(),
            "max-file-not-found" to maxFileNotFound.toString(),
        )
}
