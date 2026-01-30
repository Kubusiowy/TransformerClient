package com.client

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class ClientConfig(
    val backendUrl: String,
    val email: String,
    val password: String,
    val transformerId: String? = null,
    val pollIntervalMs: Long = 1000,
    val configRefreshMs: Long = 10000,
    val reconnectDelayMs: Long = 3000,
    val modbusTimeoutMs: Int = 1000,
    val modbusRetries: Int = 1,
    val modbusDiscardDelayMs: Int = 50,
    val interRegisterDelayMs: Long = 0,
    val rememberCredentials: Boolean = false
)

@Serializable
data class AuthResponse(
    val id: String,
    val accessToken: String,
    val refreshToken: String,
    val role: String
)

@Serializable
data class RefreshResponse(
    val accessToken: String
)

@Serializable
data class LoginRequest(
    val email: String,
    val rawPassword: String
)

@Serializable
data class RefreshRequest(
    val refreshToken: String
)

@Serializable
data class TransformerDto(
    val id: String,
    val name: String,
    val location: String? = null
)

@Serializable
data class MeterDto(
    val id: Long,
    val name: String,
    val deviceCode: String,
    val enabled: Boolean,
    val serialPort: String,
    val baudRate: Int,
    val dataBits: Int = 8,
    val parity: String,
    val stopBits: Int,
    val slaveId: Int,
    val byteOrder: String = "BIG_ENDIAN",
    val pollIntervalMs: Long? = null
)

@Serializable
data class RegisterDto(
    val id: Long,
    val name: String,
    val registerType: String,
    val address: Int,
    val length: Int,
    val dataType: String,
    val scale: Double? = 1.0,
    val unit: String? = null,
    val enabled: Boolean,
    val orderIndex: Int? = null
)

@Serializable
data class MetricPointRequest(
    val key: String,
    val value: Double,
    val timestamp: String,
    val unit: String? = null,
    val label: String? = null
)

@Serializable
data class TransformerErrorRequest(
    val code: String,
    val message: String,
    val status: String
)

data class AppState(
    val status: String,
    val transformer: TransformerDto?,
    val meters: List<MeterState>,
    val isLoggedIn: Boolean,
    val loginError: String?,
    val loginPrefillEmail: String,
    val loginPrefillPassword: String,
    val rememberPrefill: Boolean
) {
    companion object {
        fun initial() = AppState(
            status = "Uruchamianie...",
            transformer = null,
            meters = emptyList(),
            isLoggedIn = false,
            loginError = null,
            loginPrefillEmail = "",
            loginPrefillPassword = "",
            rememberPrefill = false
        )
    }
}

data class MeterState(
    val meter: MeterDto,
    val connectionStatus: String,
    val registers: List<RegisterState>,
    val lastError: String?
)

data class RegisterState(
    val register: RegisterDto,
    val value: Double?,
    val lastUpdate: Instant?
)
