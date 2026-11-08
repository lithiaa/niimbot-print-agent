package com.niimbot.printagent.update

import java.io.File
import kotlinx.serialization.Serializable

@Serializable
data class AppUpdateManifest(
    val versionCode: Long,
    val versionName: String,
    val minimumVersionCode: Long,
    val required: Boolean,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val releaseNotes: List<String> = emptyList()
)

enum class UpdateFailureReason {
    NETWORK,
    SERVER,
    INVALID_METADATA,
    DOWNLOAD,
    INTEGRITY,
    INVALID_PACKAGE,
    SIGNER_MISMATCH
}

sealed class UpdateCheckResult {
    data class Available(
        val manifest: AppUpdateManifest,
        val required: Boolean
    ) : UpdateCheckResult()

    data object UpToDate : UpdateCheckResult()
    data object NoPublishedRelease : UpdateCheckResult()
    data class Failure(val reason: UpdateFailureReason) : UpdateCheckResult()
}

sealed class ApkDownloadResult {
    data class Success(val file: File) : ApkDownloadResult()
    data class Failure(val reason: UpdateFailureReason) : ApkDownloadResult()
}
