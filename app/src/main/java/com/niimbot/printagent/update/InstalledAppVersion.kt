package com.niimbot.printagent.update

import android.content.Context
import android.os.Build

object InstalledAppVersion {
    @Suppress("DEPRECATION")
    fun code(context: Context): Long {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }
    }

    @Suppress("DEPRECATION")
    fun name(context: Context): String {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return packageInfo.versionName.orEmpty()
    }
}
