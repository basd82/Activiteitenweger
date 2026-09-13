// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Bas van den Dikkenberg

package net.dikkenberg.activiteitenweger.platform

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeVideo
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun CameraPermissionGate(
    content: @Composable () -> Unit,
) {
    var granted by remember {
        mutableStateOf(
            AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo) ==
                AVAuthorizationStatusAuthorized
        )
    }

    fun request() {
        val status = AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)
        if (status == AVAuthorizationStatusNotDetermined) {
            AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { allowed ->
                dispatch_async(dispatch_get_main_queue()) {
                    granted = allowed
                }
            }
        }
    }

    if (granted) {
        content()
    } else {
        Button(onClick = { request() }) {
            Text("Camera toestaan")
        }
    }
}
