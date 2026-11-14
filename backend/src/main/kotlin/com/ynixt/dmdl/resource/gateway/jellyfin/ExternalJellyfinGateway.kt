package com.ynixt.dmdl.resource.gateway.jellyfin

import com.ynixt.dmdl.domain.gateway.JellyfinGateway
import com.ynixt.dmdl.domain.model.JellyfinSettings
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Component
class ExternalJellyfinGateway : JellyfinGateway {
    private val client =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

    override fun test(settings: JellyfinSettings) {
        send(settings, "GET", "/System/Info")
    }

    override fun refresh(settings: JellyfinSettings) {
        require(settings.enabled)
        send(settings, "POST", "/Library/Refresh")
    }

    private fun send(
        settings: JellyfinSettings,
        method: String,
        path: String,
    ) {
        val base = runCatching { URI(requireNotNull(settings.url)) }.getOrNull()
        require(
            base != null &&
                base.scheme in setOf("http", "https") &&
                !base.host.isNullOrBlank() &&
                base.userInfo == null &&
                base.query == null &&
                base.fragment == null,
        ) { "Enter a valid external Jellyfin HTTP(S) base URL" }
        require(!settings.token.isNullOrBlank()) { "Enter an existing Jellyfin API token" }
        val uri = URI(base.toString().trimEnd('/') + path)
        // Current Jellyfin (10.11+) only accepts "Authorization: MediaBrowser" by default, which older
        // releases understand too. If a server still rejects it, retry once with the legacy header;
        // a 401 means the request was not executed, so retrying is safe.
        var status = send(uri, method, "Authorization", authorization(settings.token))
        if (status == 401) {
            status = send(uri, method, "X-Emby-Token", settings.token)
        }
        require(status in 200..299) { "Jellyfin returned HTTP $status; check the server and API token" }
    }

    private fun send(
        uri: URI,
        method: String,
        authHeader: String,
        authValue: String,
    ): Int {
        val request =
            HttpRequest
                .newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header(authHeader, authValue)
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build()
        return try {
            client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
        } catch (
            _: Exception,
        ) {
            throw IllegalArgumentException("Jellyfin is unreachable; check the server address and connectivity")
        }
    }

    private fun authorization(token: String): String {
        val version = javaClass.`package`?.implementationVersion ?: "dev"
        return "MediaBrowser Client=\"dmdl\", Device=\"dmdl\", DeviceId=\"dmdl\", " +
            "Version=\"$version\", Token=\"${token.replace("\"", "")}\""
    }
}
