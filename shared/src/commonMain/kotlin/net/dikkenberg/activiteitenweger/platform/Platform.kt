// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Bas van den Dikkenberg

package net.dikkenberg.activiteitenweger.platform

import io.ktor.client.HttpClient

expect fun createPlatformHttpClient(): HttpClient
expect fun appVersionName(): String
expect fun appBuildNumber(): String
