package com.ynixt.dmdl.domain.gateway

import com.ynixt.dmdl.domain.model.AddonCatalog
import com.ynixt.dmdl.domain.model.AddonConfiguration
import com.ynixt.dmdl.domain.model.AddonManifest
import com.ynixt.dmdl.domain.model.ContentIdentity
import com.ynixt.dmdl.domain.model.ContentSnapshot
import com.ynixt.dmdl.domain.model.DiscoveryQuery
import com.ynixt.dmdl.domain.model.MetadataDetails
import com.ynixt.dmdl.domain.model.ReleaseGroup

interface AddonGateway {
    fun manifest(url: String): AddonManifest

    fun catalog(
        source: AddonConfiguration,
        catalog: AddonCatalog,
        skip: Int,
        extras: Map<String, String> = emptyMap(),
    ): List<MetadataDetails>

    fun search(
        source: AddonConfiguration,
        query: DiscoveryQuery,
    ): List<MetadataDetails>

    fun details(
        source: AddonConfiguration,
        identity: ContentIdentity,
    ): MetadataDetails

    fun releases(
        source: AddonConfiguration,
        content: ContentSnapshot,
    ): ReleaseGroup
}
