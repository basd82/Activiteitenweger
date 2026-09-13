// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Bas van den Dikkenberg

package net.dikkenberg.activiteitenweger

import androidx.compose.ui.window.ComposeUIViewController
import net.dikkenberg.activiteitenweger.ui.ActiviteitenwegerApp

private val iosAppController = AppController()

fun MainViewController() = ComposeUIViewController {
    ActiviteitenwegerApp(iosAppController)
}

fun setAppActive(active: Boolean) {
    iosAppController.setAppForeground(active)
}
