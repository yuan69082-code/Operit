package com.ai.assistance.operit.data.updates

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class PersonalUpdateManifest(
    val version: String,
    @SerialName("version_code") val versionCode: Int
) {
    // Workflow counters are independent; Android's versionCode is the installation order.
    fun isNewerThan(installedVersionCode: Int): Boolean = versionCode > installedVersionCode

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(content: String): PersonalUpdateManifest {
            val manifest = json.decodeFromString(serializer(), content)
            require(manifest.version.isNotBlank() && manifest.versionCode > 0) {
                "Invalid personal update version"
            }
            return manifest
        }
    }
}
