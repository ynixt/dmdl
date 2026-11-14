package com.ynixt.dmdl.resource.persistence.mapper

import com.ynixt.dmdl.domain.model.ContentSnapshot
import com.ynixt.dmdl.domain.model.DebridProvider
import com.ynixt.dmdl.domain.model.DownloadExecution
import com.ynixt.dmdl.domain.model.DownloadProgress
import com.ynixt.dmdl.domain.model.DownloadRequest
import com.ynixt.dmdl.domain.model.DownloadState
import com.ynixt.dmdl.domain.model.OrganizationSnapshot
import com.ynixt.dmdl.domain.model.ReleaseSnapshot
import com.ynixt.dmdl.resource.persistence.entity.DownloadRequestEntity
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

@Component
class DownloadRequestMapper(
    private val json: ObjectMapper,
) {
    fun toEntity(request: DownloadRequest): DownloadRequestEntity =
        DownloadRequestEntity(
            id = request.id.toString(),
            contentJson = json.writeValueAsString(request.content),
            releaseJson = json.writeValueAsString(request.release),
            provider = request.provider?.name,
            organizationJson = json.writeValueAsString(request.organization),
            state = request.state.name,
            generation = request.execution.generation,
            owner = request.execution.owner?.toString(),
            executionJson = json.writeValueAsString(request.execution),
            progressJson = json.writeValueAsString(request.progress),
            error = request.error,
            createdAt = request.createdAt.toString(),
            updatedAt = request.updatedAt.toString(),
            completedAt = request.completedAt?.toString(),
            startedAt = request.startedAt?.toString(),
        )

    fun toDomain(entity: DownloadRequestEntity): DownloadRequest =
        DownloadRequest(
            id = UUID.fromString(entity.id),
            content = json.readValue(entity.contentJson, ContentSnapshot::class.java),
            release = json.readValue(entity.releaseJson, ReleaseSnapshot::class.java),
            provider = entity.provider?.let(DebridProvider::valueOf),
            organization = json.readValue(entity.organizationJson, OrganizationSnapshot::class.java),
            state = DownloadState.valueOf(entity.state),
            execution =
                json.readValue(entity.executionJson, DownloadExecution::class.java).copy(
                    generation = entity.generation,
                    owner = entity.owner?.let(UUID::fromString),
                ),
            progress = json.readValue(entity.progressJson, DownloadProgress::class.java),
            error = entity.error,
            createdAt = Instant.parse(entity.createdAt),
            updatedAt = Instant.parse(entity.updatedAt),
            completedAt = entity.completedAt?.let(Instant::parse),
            startedAt = entity.startedAt?.let(Instant::parse),
        )
}
