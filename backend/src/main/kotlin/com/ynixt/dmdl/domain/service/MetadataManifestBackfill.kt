package com.ynixt.dmdl.domain.service

import com.ynixt.dmdl.domain.model.AddonConfiguration

interface MetadataManifestBackfill {
    fun current(): AddonConfiguration?
}
