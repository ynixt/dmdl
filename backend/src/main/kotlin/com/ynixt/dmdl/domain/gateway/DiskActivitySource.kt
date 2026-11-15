package com.ynixt.dmdl.domain.gateway

import com.ynixt.dmdl.domain.model.DiskActivity
import java.nio.file.Path

interface DiskActivitySource {
    fun read(stagingRoot: Path): DiskActivity
}
