// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Bas van den Dikkenberg

package net.dikkenberg.activiteitenweger.platform

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import platform.Foundation.NSBundle

actual fun createPlatformHttpClient(): HttpClient = HttpClient(Darwin)

actual fun appVersionName(): String =
    NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String
        ?: "Onbekend"

actual fun appBuildNumber(): String =
    NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleVersion") as? String
        ?: "Onbekend"
