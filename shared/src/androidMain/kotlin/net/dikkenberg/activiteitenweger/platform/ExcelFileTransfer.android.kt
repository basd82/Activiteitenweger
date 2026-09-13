// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Bas van den Dikkenberg

package net.dikkenberg.activiteitenweger.platform

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.write
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.dialogs.openFileSaver

actual suspend fun pickExcelFileBytes(): ByteArray? =
    FileKit.openFilePicker(
        type = FileKitType.File("xlsx"),
    )?.readBytes()

actual suspend fun saveExcelFile(
    suggestedName: String,
    bytes: ByteArray,
): Boolean {
    val file = FileKit.openFileSaver(
        suggestedName = suggestedName,
        defaultExtension = "xlsx",
        allowedExtensions = setOf("xlsx"),
    ) ?: return false

    file.write(bytes)
    return true
}
