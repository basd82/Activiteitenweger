// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Bas van den Dikkenberg

package net.dikkenberg.activiteitenweger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.init
import net.dikkenberg.activiteitenweger.platform.initAndroidPlatform
import net.dikkenberg.activiteitenweger.ui.ActiviteitenwegerApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FileKit.init(this)
        initAndroidPlatform(applicationContext)
        setContent {
            ActiviteitenwegerApp()
        }
    }
}
