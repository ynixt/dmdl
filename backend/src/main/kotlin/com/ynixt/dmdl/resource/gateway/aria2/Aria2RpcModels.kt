package com.ynixt.dmdl.resource.gateway.aria2

data class Aria2RpcRequest(
    val jsonrpc: String = "2.0",
    val id: String,
    val method: String,
    val params: List<Any?>,
)

data class Aria2RpcError(
    val code: Int,
    val message: String,
)

data class Aria2RpcResponse<T>(
    val jsonrpc: String,
    val id: String,
    val result: T? = null,
    val error: Aria2RpcError? = null,
)

data class Aria2RpcStatus(
    val gid: String,
    val status: String,
    val totalLength: String,
    val completedLength: String,
    val downloadSpeed: String,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val dir: String,
    val files: List<Aria2RpcFile>,
)

data class Aria2RpcFile(
    val path: String,
    val length: String,
)

data class Aria2RpcVersion(
    val version: String,
    val enabledFeatures: List<String>,
)

class Aria2RpcException(
    val rpcCode: Int? = null,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
