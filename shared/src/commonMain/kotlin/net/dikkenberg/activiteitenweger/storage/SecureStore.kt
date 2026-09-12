package net.dikkenberg.activiteitenweger.storage

interface SecureStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
    fun clear()
}

expect fun createSecureStore(): SecureStore
