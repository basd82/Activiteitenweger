package net.dikkenberg.activiteitenweger.platform

import io.ktor.client.HttpClient

expect fun createPlatformHttpClient(): HttpClient
