// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Bas van den Dikkenberg

package net.dikkenberg.activiteitenweger.platform

import androidx.compose.runtime.Composable

@Composable
expect fun CameraPermissionGate(
    content: @Composable () -> Unit,
)
