package com.ynixt.dmdl.domain.service

import com.ynixt.dmdl.domain.model.ContentIdentity
import com.ynixt.dmdl.domain.model.ContentSnapshot
import com.ynixt.dmdl.domain.model.DiscoveryQuery
import com.ynixt.dmdl.domain.model.MediaCategory
import com.ynixt.dmdl.domain.model.MetadataDetails
import com.ynixt.dmdl.domain.model.ReleaseGroup
import com.ynixt.dmdl.domain.model.ReleasePage
import java.util.UUID

interface DiscoveryService {
    fun search(query: DiscoveryQuery): List<MetadataDetails>

    fun details(identity: ContentIdentity): MetadataDetails

    fun content(
        resourceType: String,
        contentId: String,
        season: Int?,
        episode: Int?,
        category: MediaCategory?,
    ): ContentSnapshot

    fun releasePage(
        content: ContentSnapshot,
        addonId: UUID,
        page: Int,
        forceRefresh: Boolean,
    ): ReleasePage

    fun releases(content: ContentSnapshot): List<ReleaseGroup> = releases(content, false)

    fun releases(
        content: ContentSnapshot,
        forceRefresh: Boolean,
    ): List<ReleaseGroup>
}
