package com.ynixt.dmdl.resource.persistence.repository

import com.ynixt.dmdl.resource.persistence.entity.ApplicationSettingsEntity
import com.ynixt.dmdl.resource.persistence.entity.DownloadEventEntity
import com.ynixt.dmdl.resource.persistence.entity.DownloadRequestEntity
import com.ynixt.dmdl.resource.persistence.entity.JellyfinNotificationEntity
import com.ynixt.dmdl.resource.persistence.entity.MetadataAddonEntity
import com.ynixt.dmdl.resource.persistence.entity.SearchAddonEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface DownloadRequestRepository : JpaRepository<DownloadRequestEntity, String> {
    fun findAllByOrderByCreatedAtDesc(): List<DownloadRequestEntity>

    fun existsByProviderAndStateNotIn(
        provider: String,
        states: Collection<String>,
    ): Boolean
}

interface DownloadEventRepository : JpaRepository<DownloadEventEntity, String> {
    fun findAllByRequestIdOrderByOccurredAtAsc(requestId: String): List<DownloadEventEntity>
}

interface JellyfinNotificationRepository : JpaRepository<JellyfinNotificationEntity, String> {
    fun findAllByState(state: String): List<JellyfinNotificationEntity>
}

interface MetadataAddonRepository : JpaRepository<MetadataAddonEntity, Int> {
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MetadataAddonEntity m set m.manifestJson = :manifestJson where m.id = :id and m.revision = :revision")
    fun replaceManifest(
        id: String,
        revision: Long,
        manifestJson: String,
    ): Int
}

interface SearchAddonRepository : JpaRepository<SearchAddonEntity, String> {
    fun findAllByOrderByDisplayOrderAscIdAsc(): List<SearchAddonEntity>
}

interface ApplicationSettingsRepository : JpaRepository<ApplicationSettingsEntity, Int>
