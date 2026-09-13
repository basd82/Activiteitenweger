// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Bas van den Dikkenberg

package net.dikkenberg.activiteitenweger.storage

import com.liftric.kvault.KVault

actual fun createSecureStore(): SecureStore {
    val vault = KVault("net.dikkenberg.activiteitenweger.secure")
    return object : SecureStore {
        override fun getString(key: String): String? = vault.string(forKey = key)
        override fun putString(key: String, value: String) {
            check(vault.set(key = key, stringValue = value)) { "Secure storage write failed" }
        }
        override fun remove(key: String) { vault.deleteObject(forKey = key) }
        override fun clear() { vault.clear() }
    }
}
