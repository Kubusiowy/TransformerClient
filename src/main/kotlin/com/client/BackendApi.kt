package com.client

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.send
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.util.concurrent.atomic.AtomicReference

class BackendApi(private val config: ClientConfig) {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient {
        install(ContentNegotiation) { json(this@BackendApi.json) }
        install(WebSockets)
    }

    private val accessToken = AtomicReference<String?>(null)
    private val refreshToken = AtomicReference<String?>(null)
    private val wsSession = AtomicReference<io.ktor.client.plugins.websocket.DefaultClientWebSocketSession?>(null)
    private val wsMutex = Mutex()

    suspend fun login(email: String, password: String) {
        val response: AuthResponse = client.post("${config.backendUrl}/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(email, password))
        }.body()
        accessToken.set(response.accessToken)
        refreshToken.set(response.refreshToken)
    }

    suspend fun clearSession() {
        accessToken.set(null)
        refreshToken.set(null)
        wsSession.getAndSet(null)?.close()
    }

    suspend fun fetchTransformers(): List<TransformerDto> = authorizedGet("/transformers")

    suspend fun fetchMeters(transformerId: String): List<MeterDto> =
        authorizedGet("/transformers/$transformerId/meters")

    suspend fun fetchRegisters(meterId: Long): List<RegisterDto> =
        authorizedGet("/meters/$meterId/registers")

    suspend fun postMetric(transformerId: String, metric: MetricPointRequest) {
        authorizedPost<Unit>("/transformers/$transformerId/metrics", metric)
    }

    suspend fun sendMetricWs(transformerId: String, metric: MetricPointRequest) {
        val session = ensureWsSession(transformerId)
        val payload = json.encodeToString(metric)
        try {
            session.send(Frame.Text(payload))
        } catch (ex: Exception) {
            wsSession.getAndSet(null)?.close()
            val retry = ensureWsSession(transformerId)
            retry.send(Frame.Text(payload))
        }
    }

    private suspend fun ensureWsSession(transformerId: String): io.ktor.client.plugins.websocket.DefaultClientWebSocketSession {
        return wsMutex.withLock {
            val existing = wsSession.get()
            if (existing != null && !existing.incoming.isClosedForReceive) return@withLock existing
            val token = accessToken.get() ?: error("Brak access tokena. Najpierw login().")
            val wsUrl = buildWsUrl(transformerId, token)
            val session = client.webSocketSession(wsUrl)
            wsSession.set(session)
            session
        }
    }

    private fun buildWsUrl(transformerId: String, token: String): String {
        val uri = URI(config.backendUrl)
        val scheme = if (uri.scheme.equals("https", true)) "wss" else "ws"
        val host = uri.host ?: "localhost"
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return "$scheme://$host$port/ws/transformers/$transformerId/metrics?token=$token"
    }

    suspend fun postTransformerError(transformerId: String, error: TransformerErrorRequest) {
        authorizedPost<Unit>("/transformers/$transformerId/errors", error)
    }

    private suspend inline fun <reified T> authorizedGet(path: String): T {
        return withAuthRetry {
            val token = accessToken.get() ?: error("Brak access tokena. Najpierw login().")
            client.get("${config.backendUrl}$path") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }.body()
        }
    }

    private suspend inline fun <reified T> authorizedPost(path: String, bodyObj: Any): T {
        return withAuthRetry {
            val token = accessToken.get() ?: error("Brak access tokena. Najpierw login().")
            client.post("${config.backendUrl}$path") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(bodyObj)
            }.body()
        }
    }

    private suspend inline fun <reified T> withAuthRetry(block: suspend () -> T): T {
        return try {
            block()
        } catch (ex: ClientRequestException) {
            if (ex.response.status.value == 401) {
                refreshAccessToken()
                block()
            } else {
                throw ex
            }
        }
    }

    private suspend fun refreshAccessToken() {
        val token = refreshToken.get() ?: error("Brak refresh tokena. Najpierw login().")
        val response: RefreshResponse = client.post("${config.backendUrl}/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshRequest(token))
        }.body()
        accessToken.set(response.accessToken)
    }
}
