package com.ynixt.dmdl.domain.repository

import com.ynixt.dmdl.domain.model.AddonConfiguration
import com.ynixt.dmdl.domain.model.ApplicationSettings
import com.ynixt.dmdl.domain.model.PreparedSetup
import java.util.UUID

interface ConfigurationStore {
    fun settings(): ApplicationSettings?

    fun createAccount(settings: ApplicationSettings): Boolean

    fun saveSettings(settings: ApplicationSettings)

    fun completeSetup(setup: PreparedSetup)

    fun metadata(): AddonConfiguration?

    fun saveMetadata(configuration: AddonConfiguration)

    fun searchAddons(): List<AddonConfiguration>

    fun saveSearchAddon(configuration: AddonConfiguration)

    fun removeSearchAddon(id: UUID)
}
