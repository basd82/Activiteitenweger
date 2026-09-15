// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Bas van den Dikkenberg

package net.dikkenberg.activiteitenweger.platform

expect fun biometricDisplayName(): String?
expect suspend fun authenticateBiometric(reason: String): Boolean
