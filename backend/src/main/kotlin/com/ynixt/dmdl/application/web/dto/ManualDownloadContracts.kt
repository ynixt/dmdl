package com.ynixt.dmdl.application.web.dto

import com.fasterxml.jackson.annotation.JsonAnySetter
import com.ynixt.dmdl.domain.model.ContentSnapshot
import com.ynixt.dmdl.domain.model.ReleaseReferenceType
import com.ynixt.dmdl.domain.model.ReleaseSnapshot
import java.util.UUID

/** Reject obsolete or misspelled fields even when the application's mapper ignores unknown properties. */
abstract class StrictRequest {
    @JsonAnySetter
    fun rejectUnknown(
        name: String,
        value: Any?,
    ): Nothing = throw IllegalArgumentException("Unsupported request field: $name")
}

data class CreateDownloadRequest(
    val content: ContentSnapshot,
    val release: ReleaseSnapshot,
    val destinationId: UUID,
) : StrictRequest() {
    init {
        require(release.reference.type == ReleaseReferenceType.PROVIDER_HTTP) { "KZ.ADDON_HTTP_REQUIRED" }
    }
}
