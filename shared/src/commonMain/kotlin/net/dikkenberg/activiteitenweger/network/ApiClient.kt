package net.dikkenberg.activiteitenweger.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.dikkenberg.activiteitenweger.crypto.CryptoService
import net.dikkenberg.activiteitenweger.crypto.fromBase64Url
import net.dikkenberg.activiteitenweger.crypto.hexLower
import net.dikkenberg.activiteitenweger.crypto.toBase64Url
import net.dikkenberg.activiteitenweger.model.VaultSession
import net.dikkenberg.activiteitenweger.platform.createPlatformHttpClient
import kotlin.time.Clock

class ApiException(
    val status: Int,
    val code: String,
    val responseBody: String,
) : Exception("API $status: $code")

class ApiClient(
    private val baseUrl: String = "https://app.dikkenberg.net",
    private val crypto: CryptoService,
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    },
) {
    private val client: HttpClient = createPlatformHttpClient().config {
        install(ContentNegotiation) { json(this@ApiClient.json) }
    }

    suspend fun health(): HealthResponse = publicGet("/api/v1/health")

    suspend fun createVault(body: CreateVaultRequest): CreateVaultResponse =
        publicJson(HttpMethod.Post, "/api/v1/vaults", body)

    suspend fun me(session: VaultSession): MeResponse =
        signedJson(HttpMethod.Get, "/api/v1/me", "", session)

    suspend fun devices(session: VaultSession): DevicesResponse =
        signedJson(HttpMethod.Get, "/api/v1/devices", "", session)

    suspend fun upsertRecord(session: VaultSession, request: UpsertRecordRequest): UpsertRecordResponse {
        val raw = json.encodeToString(request)
        return signedJson(HttpMethod.Post, "/api/v1/records", raw, session)
    }

    suspend fun sync(session: VaultSession, since: Long, limit: Int = 500): SyncResponse {
        val path = "/api/v1/sync?since=$since&limit=$limit"
        return signedJson(HttpMethod.Get, path, "", session)
    }

    suspend fun deleteVault(session: VaultSession): DeleteVaultResponse {
        val path = "/api/v1/vaults/${session.vaultId}"
        val raw = json.encodeToString(DeleteVaultRequest())
        return signedJson(HttpMethod.Delete, path, raw, session)
    }

    private suspend inline fun <reified T> publicGet(path: String): T {
        val response = client.request(baseUrl + path) { method = HttpMethod.Get }
        val raw = response.bodyAsText()
        checkResponse(response.status.value, raw)
        return json.decodeFromString(raw)
    }

    private suspend inline fun <reified Req, reified Res> publicJson(
        method: HttpMethod,
        path: String,
        body: Req,
    ): Res {
        val raw = json.encodeToString(body)
        val response = client.request(baseUrl + path) {
            this.method = method
            setBody(TextContent(raw, ContentType.Application.Json))
        }
        val responseRaw = response.bodyAsText()
        checkResponse(response.status.value, responseRaw)
        return json.decodeFromString(responseRaw)
    }

    private suspend inline fun <reified T> signedJson(
        method: HttpMethod,
        path: String,
        rawBody: String,
        session: VaultSession,
    ): T {
        val timestamp = Clock.System.now().epochSeconds.toString()
        val nonce = crypto.randomBytes(16).toBase64Url()
        val bodyHash = crypto.sha256(rawBody.encodeToByteArray()).hexLower()
        val canonical = buildString {
            append("AW-REQUEST-V1\n")
            append(method.value.uppercase()).append('\n')
            append(path).append('\n')
            append(timestamp).append('\n')
            append(nonce).append('\n')
            append(bodyHash)
        }
        val signature = crypto.signEd25519(
            session.authPrivateKey.fromBase64Url(),
            canonical.encodeToByteArray(),
        ).toBase64Url()

        val response = client.request(baseUrl + path) {
            this.method = method
            headers.append("X-AW-Device-Id", session.deviceId)
            headers.append("X-AW-Timestamp", timestamp)
            headers.append("X-AW-Nonce", nonce)
            headers.append("X-AW-Signature", signature)
            headers.append(HttpHeaders.Accept, ContentType.Application.Json.toString())
            if (rawBody.isNotEmpty()) {
                setBody(TextContent(rawBody, ContentType.Application.Json))
            }
        }
        val responseRaw = response.bodyAsText()
        checkResponse(response.status.value, responseRaw)
        return json.decodeFromString(responseRaw)
    }

    private fun checkResponse(status: Int, raw: String) {
        if (status in 200..299) return
        val error = runCatching { json.decodeFromString<ErrorResponse>(raw).error }
            .getOrNull() ?: "http_$status"
        throw ApiException(status, error, raw)
    }
}
