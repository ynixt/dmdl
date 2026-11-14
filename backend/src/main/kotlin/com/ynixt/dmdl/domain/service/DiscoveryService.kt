package com.ynixt.dmdl.domain.service

import com.ynixt.dmdl.domain.model.ContentIdentity
import com.ynixt.dmdl.domain.model.ContentSnapshot
import com.ynixt.dmdl.domain.model.DiscoveryQuery
import com.ynixt.dmdl.domain.model.MediaKind
import com.ynixt.dmdl.domain.model.MetadataDetails
import com.ynixt.dmdl.domain.model.ReleaseGroup
import com.ynixt.dmdl.domain.model.ReleasePage
import java.util.UUID

interface DiscoveryService {
    fun search(
        query: DiscoveryQuery,
        forceRefresh: Boolean = false,
    ): List<MetadataDetails>

    fun catalog(
        type: String,
        id: String,
        skip: Int = 0,
        forceRefresh: Boolean = false,
        extras: Map<String, String> = emptyMap(),
    ): List<MetadataDetails>

    fun details(
        identity: ContentIdentity,
        forceRefresh: Boolean = false,
    ): MetadataDetails

    /** Loads a title by id from the current metadata addon, finding the addon type that answers for [kind]. */
    fun lookup(
        kind: MediaKind,
        contentId: String,
        forceRefresh: Boolean = false,
    ): MetadataDetails

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
