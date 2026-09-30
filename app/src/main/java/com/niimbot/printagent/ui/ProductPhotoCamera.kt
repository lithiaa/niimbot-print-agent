package com.niimbot.printagent.ui

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

internal object ProductPhotoCamera {
    fun createOutputUri(context: Context): Uri {
        val directory = File(context.cacheDir, "product-photo-capture").apply { mkdirs() }
        val output = File.createTempFile("product-photo-", ".jpg", directory)
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            output
        )
    }

    fun deleteOutput(context: Context, uri: Uri?) {
        if (uri == null) return
        val deleted = runCatching {
            context.contentResolver.delete(uri, null, null) > 0
        }.getOrDefault(false)
        if (deleted) return
        runCatching {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: return@runCatching
            File(File(context.cacheDir, "product-photo-capture"), name).delete()
        }
    }
}
