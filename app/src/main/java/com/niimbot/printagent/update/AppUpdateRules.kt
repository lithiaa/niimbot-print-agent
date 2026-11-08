package com.niimbot.printagent.update

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object AppUpdateRules {
    const val UPDATE_ORIGIN = "https://update.lithiaproject.site"
    const val MANIFEST_URL = "$UPDATE_ORIGIN/latest.json"
    const val UPDATE_HOST = "update.lithiaproject.site"

    private val sha256Pattern = Regex("^[0-9a-fA-F]{64}$")

    fun isUpdateAvailable(currentVersionCode: Long, manifest: AppUpdateManifest): Boolean =
        manifest.versionCode > currentVersionCode

    fun isUpdateRequired(currentVersionCode: Long, manifest: AppUpdateManifest): Boolean =
        manifest.required || currentVersionCode < manifest.minimumVersionCode

    fun validate(manifest: AppUpdateManifest): Boolean {
        if (manifest.versionCode <= 0L) return false
        if (manifest.versionName.isBlank()) return false
        if (manifest.minimumVersionCode <= 0L) return false
        if (manifest.minimumVersionCode > manifest.versionCode) return false
        if (manifest.sizeBytes <= 0L) return false
        if (!sha256Pattern.matches(manifest.sha256)) return false
        return isExpectedApkUrl(manifest.apkUrl, manifest.versionCode)
    }

    fun isExpectedApkUrl(apkUrl: String, versionCode: Long): Boolean {
        val parsed = apkUrl.toHttpUrlOrNull() ?: return false
        return parsed.isHttps &&
            parsed.host == UPDATE_HOST &&
            parsed.port == 443 &&
            parsed.query == null &&
            parsed.fragment == null &&
            parsed.encodedPath == "/releases/$versionCode/app-release.apk"
    }
}
