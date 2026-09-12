package net.dikkenberg.activiteitenweger.storage

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.dikkenberg.activiteitenweger.model.VaultSession

class SessionStore(
    private val secureStore: SecureStore,
    private val json: Json,
) {
    private val indexKey = "vault.index"

    fun list(): List<VaultSession> {
        val ids = secureStore.getString(indexKey)
            ?.let { runCatching { json.decodeFromString<List<String>>(it) }.getOrNull() }
            .orEmpty()
        return ids.mapNotNull { id ->
            secureStore.getString("vault.$id")
                ?.let { runCatching { json.decodeFromString<VaultSession>(it) }.getOrNull() }
        }
    }

    fun save(session: VaultSession) {
        secureStore.putString("vault.${session.vaultId}", json.encodeToString(session))
        val ids = list().map { it.vaultId }.toMutableSet()
        ids += session.vaultId
        secureStore.putString(indexKey, json.encodeToString(ids.toList()))
    }

    fun remove(vaultId: String) {
        secureStore.remove("vault.$vaultId")
        val ids = list().map { it.vaultId }.filterNot { it == vaultId }
        secureStore.putString(indexKey, json.encodeToString(ids))
    }

    fun clear() = secureStore.clear()
}
