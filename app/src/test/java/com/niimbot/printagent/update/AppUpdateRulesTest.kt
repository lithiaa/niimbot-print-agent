package com.niimbot.printagent.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateRulesTest {
    @Test
    fun `valid manifest uses immutable versioned release URL`() {
        assertTrue(AppUpdateRules.validate(manifest()))
    }

    @Test
    fun `update is available only for a higher version code`() {
        assertTrue(AppUpdateRules.isUpdateAvailable(1, manifest(versionCode = 2)))
        assertFalse(AppUpdateRules.isUpdateAvailable(2, manifest(versionCode = 2)))
        assertFalse(AppUpdateRules.isUpdateAvailable(3, manifest(versionCode = 2)))
    }

    @Test
    fun `required flag or minimum version can require update`() {
        assertTrue(AppUpdateRules.isUpdateRequired(1, manifest(required = true)))
        assertTrue(AppUpdateRules.isUpdateRequired(1, manifest(minimumVersionCode = 2)))
        assertFalse(AppUpdateRules.isUpdateRequired(1, manifest()))
    }

    @Test
    fun `http and foreign hosts are rejected`() {
        assertFalse(
            AppUpdateRules.validate(
                manifest(apkUrl = "http://update.lithiaproject.site/releases/2/app-release.apk")
            )
        )
        assertFalse(
            AppUpdateRules.validate(
                manifest(apkUrl = "https://example.com/releases/2/app-release.apk")
            )
        )
    }

    @Test
    fun `release URL must match manifest version code`() {
        assertFalse(
            AppUpdateRules.validate(
                manifest(apkUrl = "https://update.lithiaproject.site/releases/3/app-release.apk")
            )
        )
    }

    @Test
    fun `release URL rejects query fragments and alternate filenames`() {
        assertFalse(
            AppUpdateRules.validate(
                manifest(
                    apkUrl = "https://update.lithiaproject.site/releases/2/app-release.apk?token=x"
                )
            )
        )
        assertFalse(
            AppUpdateRules.validate(
                manifest(apkUrl = "https://update.lithiaproject.site/releases/2/latest.apk")
            )
        )
    }

    @Test
    fun `manifest requires exact sha256 and positive size`() {
        assertFalse(AppUpdateRules.validate(manifest(sha256 = "abc")))
        assertFalse(AppUpdateRules.validate(manifest(sizeBytes = 0)))
    }

    @Test
    fun `minimum version cannot exceed published version`() {
        assertFalse(AppUpdateRules.validate(manifest(minimumVersionCode = 3)))
    }

    private fun manifest(
        versionCode: Long = 2,
        minimumVersionCode: Long = 1,
        required: Boolean = false,
        apkUrl: String = "https://update.lithiaproject.site/releases/2/app-release.apk",
        sha256: String = "a".repeat(64),
        sizeBytes: Long = 19_000_000
    ) = AppUpdateManifest(
        versionCode = versionCode,
        versionName = "1.1.0",
        minimumVersionCode = minimumVersionCode,
        required = required,
        apkUrl = apkUrl,
        sha256 = sha256,
        sizeBytes = sizeBytes,
        releaseNotes = listOf("Pembaruan contoh")
    )
}
