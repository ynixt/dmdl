package com.ynixt.dmdl.domain.model

import java.nio.file.Path
import java.util.UUID

data class OrganizationSettings(
    val revision: Long = 1,
    val categories: Map<MediaCategory, CategoryOrganization> = defaults(),
) {
    init {
        require(revision > 0 && categories.keys == MediaCategory.entries.toSet())
    }

    companion object {
        fun defaults(storageRoot: String = "./media"): Map<MediaCategory, CategoryOrganization> =
            MediaCategory.entries.associateWith { category ->
                val directory =
                    when (category) {
                        MediaCategory.MOVIE -> "movies"
                        MediaCategory.SERIES -> "series"
                        MediaCategory.ANIME -> "anime"
                    }
                val id = UUID.nameUUIDFromBytes("destination-$directory".toByteArray(Charsets.UTF_8))
                CategoryOrganization(
                    "{title}[ ({year})][ {providerId}]" + if (category == MediaCategory.MOVIE) "" else "/Season {season:02}",
                    listOf(
                        StorageDestination(
                            id,
                            directory,
                            Path
                                .of(storageRoot)
                                .toAbsolutePath()
                                .normalize()
                                .resolve(directory)
                                .toString(),
                        ),
                    ),
                    id,
                )
            }
    }
}
