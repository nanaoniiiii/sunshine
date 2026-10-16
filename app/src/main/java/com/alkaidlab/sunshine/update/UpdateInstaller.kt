package com.alkaidlab.sunshine.update

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import java.io.File

internal object UpdateInstaller {
    @Suppress("DEPRECATION")
    fun intent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val system = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .filter { it.activityInfo.exported && (it.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 }
        val preferred = listOf("com.google.android.packageinstaller", "com.android.packageinstaller", "com.samsung.android.packageinstaller")
        val selected = preferred.firstNotNullOfOrNull { pkg -> system.firstOrNull { it.activityInfo.packageName == pkg } }
            ?: system.firstOrNull() ?: error("没有可用的系统安装器")
        intent.component = ComponentName(selected.activityInfo.packageName, selected.activityInfo.name)
        intent.clipData = ClipData.newRawUri("Sunshine 更新", uri)
        return intent
    }
}
