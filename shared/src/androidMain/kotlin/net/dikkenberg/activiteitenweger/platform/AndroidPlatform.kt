package net.dikkenberg.activiteitenweger.platform

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

private lateinit var applicationContext: Context

fun initAndroidPlatform(context: Context) {
    applicationContext = context.applicationContext
}

internal fun requireAndroidContext(): Context {
    check(::applicationContext.isInitialized) { "initAndroidPlatform() must be called first" }
    return applicationContext
}

actual fun createPlatformHttpClient(): HttpClient = HttpClient(OkHttp)

actual fun appVersionName(): String {
    val context = requireAndroidContext()
    val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(0),
        )
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    return packageInfo.versionName ?: "Onbekend"
}

actual fun appBuildNumber(): String {
    val context = requireAndroidContext()
    val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(0),
        )
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    val build = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageInfo.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        packageInfo.versionCode.toLong()
    }
    return build.toString()
}
