// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Bas van den Dikkenberg

package net.dikkenberg.activiteitenweger.platform

expect suspend fun pickExcelFileBytes(): ByteArray?

expect suspend fun saveExcelFile(
    suggestedName: String,
    bytes: ByteArray,
): Boolean
