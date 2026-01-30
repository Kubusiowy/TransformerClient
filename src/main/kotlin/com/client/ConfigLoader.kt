package com.client

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

object ConfigLoader {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    fun load(): ClientConfig {
        val localPath = Path.of("client-config.json")
        val configText = when {
            Files.exists(localPath) -> Files.readString(localPath)
            else -> {
                val resource = ConfigLoader::class.java.classLoader.getResource("client-config.json")
                    ?: error("Brak client-config.json (lokalnie lub w resources).")
                resource.readText()
            }
        }
        return json.decodeFromString(ClientConfig.serializer(), configText)
    }

    fun save(config: ClientConfig) {
        val localPath = Path.of("client-config.json")
        val jsonText = json.encodeToString(ClientConfig.serializer(), config)
        Files.writeString(localPath, jsonText)
    }
}
