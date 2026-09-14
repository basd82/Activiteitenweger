// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Bas van den Dikkenberg

package net.dikkenberg.activiteitenweger.platform

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import platform.Foundation.NSBundle
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIPasteboard
import platform.UIKit.UIViewController

actual fun createPlatformHttpClient(): HttpClient = HttpClient(Darwin)

actual fun appVersionName(): String =
    NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String
        ?: "Onbekend"

actual fun appBuildNumber(): String =
    NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleVersion") as? String
        ?: "Onbekend"

actual fun copyTextToClipboard(label: String, text: String) {
    UIPasteboard.generalPasteboard.string = text
}

actual fun shareText(text: String, chooserTitle: String) {
    val activityController = UIActivityViewController(
        activityItems = listOf(text),
        applicationActivities = null,
    )
    topViewController()?.presentViewController(
        viewControllerToPresent = activityController,
        animated = true,
        completion = null,
    )
}

private fun topViewController(): UIViewController? {
    val root = UIApplication.sharedApplication.keyWindow?.rootViewController ?: return null
    var current = root
    while (current.presentedViewController != null) {
        current = current.presentedViewController!!
    }
    return current
}
