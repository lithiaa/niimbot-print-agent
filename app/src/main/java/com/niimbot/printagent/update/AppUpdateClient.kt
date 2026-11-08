package com.niimbot.printagent.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

@Singleton
class AppUpdateClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient,
    private val json: Json
) {
    suspend fun checkForUpdate(currentVersionCode: Long): UpdateCheckResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(AppUpdateRules.MANIFEST_URL)
            .get()
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (response.code == 404) return@withContext UpdateCheckResult.NoPublishedRelease
                if (!response.isSuccessful) {
                    return@withContext UpdateCheckResult.Failure(UpdateFailureReason.SERVER)
                }
                val body = response.body
                    ?: return@withContext UpdateCheckResult.Failure(UpdateFailureReason.INVALID_METADATA)
                if (body.contentLength() > MAX_METADATA_BYTES) {
                    return@withContext UpdateCheckResult.Failure(UpdateFailureReason.INVALID_METADATA)
                }
                val bytes = body.bytes()
                if (bytes.size > MAX_METADATA_BYTES) {
                    return@withContext UpdateCheckResult.Failure(UpdateFailureReason.INVALID_METADATA)
                }
                val manifest = json.decodeFromString<AppUpdateManifest>(bytes.decodeToString())
                if (!AppUpdateRules.validate(manifest)) {
                    return@withContext UpdateCheckResult.Failure(UpdateFailureReason.INVALID_METADATA)
                }
                if (!AppUpdateRules.isUpdateAvailable(currentVersionCode, manifest)) {
                    return@withContext UpdateCheckResult.UpToDate
                }
                UpdateCheckResult.Available(
                    manifest = manifest,
                    required = AppUpdateRules.isUpdateRequired(currentVersionCode, manifest)
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SerializationException) {
            UpdateCheckResult.Failure(UpdateFailureReason.INVALID_METADATA)
        } catch (_: Exception) {
            UpdateCheckResult.Failure(UpdateFailureReason.NETWORK)
        }
    }

    suspend fun downloadAndVerify(manifest: AppUpdateManifest): ApkDownloadResult =
        withContext(Dispatchers.IO) {
            if (!AppUpdateRules.validate(manifest)) {
                return@withContext ApkDownloadResult.Failure(UpdateFailureReason.INVALID_METADATA)
            }

            val updateDirectory = File(context.cacheDir, UPDATE_CACHE_DIRECTORY).apply { mkdirs() }
            val target = File(updateDirectory, "app-release-${manifest.versionCode}.apk")
            val partial = File(updateDirectory, "app-release-${manifest.versionCode}.apk.part")
            target.delete()
            partial.delete()

            val request = Request.Builder()
                .url(manifest.apkUrl)
                .get()
                .header("Accept", APK_MIME_TYPE)
                .build()

            try {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful ||
                        !AppUpdateRules.isExpectedApkUrl(
                            response.request.url.toString(),
                            manifest.versionCode
                        )
                    ) {
                        return@withContext ApkDownloadResult.Failure(UpdateFailureReason.DOWNLOAD)
                    }
                    val body = response.body
                        ?: return@withContext ApkDownloadResult.Failure(UpdateFailureReason.DOWNLOAD)
                    val contentLength = body.contentLength()
                    if (contentLength >= 0L && contentLength != manifest.sizeBytes) {
                        return@withContext ApkDownloadResult.Failure(UpdateFailureReason.INTEGRITY)
                    }

                    val digest = MessageDigest.getInstance("SHA-256")
                    var totalBytes = 0L
                    body.byteStream().use { input ->
                        FileOutputStream(partial).use { output ->
                            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                totalBytes += read
                                if (totalBytes > manifest.sizeBytes) {
                                    partial.delete()
                                    return@withContext ApkDownloadResult.Failure(
                                        UpdateFailureReason.INTEGRITY
                                    )
                                }
                                digest.update(buffer, 0, read)
                                output.write(buffer, 0, read)
                            }
                        }
                    }

                    val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                    if (totalBytes != manifest.sizeBytes ||
                        !actualHash.equals(manifest.sha256, ignoreCase = true)
                    ) {
                        partial.delete()
                        return@withContext ApkDownloadResult.Failure(UpdateFailureReason.INTEGRITY)
                    }
                }

                if (!partial.renameTo(target)) {
                    partial.delete()
                    return@withContext ApkDownloadResult.Failure(UpdateFailureReason.DOWNLOAD)
                }
                val packageFailure = verifyPackage(target, manifest)
                when (packageFailure) {
                    null -> ApkDownloadResult.Success(target)
                    else -> {
                        target.delete()
                        ApkDownloadResult.Failure(packageFailure)
                    }
                }
            } catch (cancelled: CancellationException) {
                partial.delete()
                throw cancelled
            } catch (_: Exception) {
                partial.delete()
                target.delete()
                ApkDownloadResult.Failure(UpdateFailureReason.NETWORK)
            }
        }

    fun canRequestPackageInstalls(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    @RequiresApi(Build.VERSION_CODES.O)
    fun createUnknownSourcesIntent(): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        )

    fun createInstallerIntent(apkFile: File): Intent {
        val contentUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyPackage(
        apkFile: File,
        manifest: AppUpdateManifest
    ): UpdateFailureReason? {
        val packageManager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val archiveInfo = packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags)
            ?: return UpdateFailureReason.INVALID_PACKAGE
        if (archiveInfo.packageName != context.packageName) {
            return UpdateFailureReason.INVALID_PACKAGE
        }
        val archiveVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archiveInfo.longVersionCode
        } else {
            archiveInfo.versionCode.toLong()
        }
        if (archiveVersionCode != manifest.versionCode) {
            return UpdateFailureReason.INVALID_PACKAGE
        }

        val installedInfo = packageManager.getPackageInfo(context.packageName, flags)
        val installedSigners = signerDigests(installedInfo)
        val archiveSigners = signerDigests(archiveInfo)
        if (installedSigners.isEmpty() || archiveSigners.isEmpty() ||
            installedSigners.intersect(archiveSigners).isEmpty()
        ) {
            return UpdateFailureReason.SIGNER_MISMATCH
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun signerDigests(packageInfo: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = packageInfo.signingInfo ?: return emptySet()
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
        } else {
            packageInfo.signatures.orEmpty()
        }
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    companion object {
        private const val MAX_METADATA_BYTES = 256 * 1024L
        private const val DOWNLOAD_BUFFER_BYTES = 32 * 1024
        private const val UPDATE_CACHE_DIRECTORY = "app-updates"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
