package com.ynixt.dmdl.resource.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.type.NumericBooleanConverter

@Entity
@Table(name = "download_request")
class DownloadRequestEntity(
    @Id var id: String,
    var contentJson: String,
    var releaseJson: String,
    var provider: String?,
    var organizationJson: String,
    var state: String,
    @Column(columnDefinition = "INTEGER") var generation: Long,
    var owner: String?,
    var executionJson: String,
    var progressJson: String,
    var error: String?,
    var createdAt: String,
    var updatedAt: String,
    var completedAt: String?,
)

@Entity
@Table(name = "download_event")
class DownloadEventEntity(
    @Id var id: String,
    var requestId: String,
    @Column(columnDefinition = "INTEGER") var generation: Long,
    var state: String,
    var occurredAt: String,
    var message: String?,
)

@Entity
@Table(name = "jellyfin_notification")
class JellyfinNotificationEntity(
    @Id var requestId: String,
    var state: String,
    var attempts: Int,
    var attemptedAt: String?,
    var error: String?,
)

@Entity
@Table(name = "metadata_addon")
class MetadataAddonEntity(
    @Id var singleton: Int = 1,
    var id: String,
    @Column(columnDefinition = "INTEGER") var revision: Long,
    var name: String,
    var manifestUrl: String,
    var manifestJson: String,
) {
    override fun toString(): String = "MetadataAddonEntity(id=$id, revision=$revision)"
}

@Entity
@Table(name = "search_addon")
class SearchAddonEntity(
    @Id var id: String,
    @Column(columnDefinition = "INTEGER") var revision: Long,
    var name: String,
    var manifestUrl: String,
    var manifestJson: String,
    @Convert(converter = NumericBooleanConverter::class) var enabled: Boolean,
    var displayOrder: Int,
) {
    override fun toString(): String = "SearchAddonEntity(id=$id, revision=$revision)"
}

@Entity
@Table(name = "application_settings")
class ApplicationSettingsEntity(
    @Id var id: Int = 1,
    var username: String,
    var passwordHash: String,
    var locale: String,
    var maxConcurrentDownloads: Int,
    @Column(columnDefinition = "INTEGER") var organizationRevision: Long,
    var organizationJson: String,
    var transferOptionsJson: String,
    @Convert(converter = NumericBooleanConverter::class) var onboardingCompleted: Boolean,
    @Convert(converter = NumericBooleanConverter::class) var jellyfinEnabled: Boolean,
    var jellyfinUrl: String?,
    var jellyfinToken: String?,
    var createdAt: String,
    var updatedAt: String,
    @Column(columnDefinition = "INTEGER") var configurationRevision: Long = 0,
    var setupSubmissionId: String? = null,
    var setupFingerprint: String? = null,
    var streamBadgeJson: String? = null,
) {
    override fun toString(): String = "ApplicationSettingsEntity(id=$id)"
}
