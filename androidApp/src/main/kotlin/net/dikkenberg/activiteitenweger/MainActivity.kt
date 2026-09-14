// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Bas van den Dikkenberg

package net.dikkenberg.activiteitenweger

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import net.dikkenberg.activiteitenweger.platform.initAndroidBiometricActivity
import net.dikkenberg.activiteitenweger.platform.initAndroidFileDialogs
import net.dikkenberg.activiteitenweger.platform.initAndroidPlatform
import net.dikkenberg.activiteitenweger.ui.ActiviteitenwegerApp

class MainActivity : FragmentActivity() {
    private lateinit var controller: AppController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initAndroidFileDialogs(this)
        initAndroidBiometricActivity(this)
        initAndroidPlatform(applicationContext)
        controller = AppController()
        setContent {
            ActiviteitenwegerApp(controller)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::controller.isInitialized) {
            controller.setAppForeground(true)
        }
    }

    override fun onPause() {
        if (::controller.isInitialized) {
            controller.setAppForeground(false)
        }
        super.onPause()
    }
}
