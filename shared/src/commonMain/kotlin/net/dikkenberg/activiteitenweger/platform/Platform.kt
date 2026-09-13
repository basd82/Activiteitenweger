package net.dikkenberg.activiteitenweger.platform

import io.ktor.client.HttpClient

expect fun createPlatformHttpClient(): HttpClient
expect fun appVersionName(): String
expect fun appBuildNumber(): String
