package net.dikkenberg.activiteitenweger.platform

import android.content.Context
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
