package ai.hinow

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.*

/**
 * HINOW AI Client
 *
 * Official Kotlin SDK for the HINOW AI Inference API.
 */
class Hinow(
    private val apiKey: String = System.getenv("HINOW_API_KEY") ?: "",
    private val baseUrl: String = "https://api.hinow.ai",
    private val timeout: Long = 120_000
) {
    init {
        require(apiKey.isNotEmpty()) {
            "API key is required. Set HINOW_API_KEY environment variable or pass apiKey parameter."
        }
    }

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = timeout
            connectTimeoutMillis = timeout
        }
        defaultRequest {
            header("Authorization", "Bearer $apiKey")
            header("User-Agent", "hinow-kotlin/1.0.0")
            contentType(ContentType.Application.Json)
        }
    }

    val chat = Chat(this)
    val images = Images(this)
    val audio = Audio(this)
    val video = Video(this)
    val embeddings = Embeddings(this)
    val models = Models(this)

    suspend fun getBalance(): JsonObject {
        return get("/v1/balance")
    }

    internal suspend fun get(path: String): JsonObject {
        val response = httpClient.get("$baseUrl$path")
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    internal suspend fun post(path: String, body: JsonObject): JsonObject {
        val response = httpClient.post("$baseUrl$path") {
            setBody(body)
        }
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    fun close() {
        httpClient.close()
    }
}

class Chat(private val client: Hinow) {
    fun completions() = ChatCompletions(client)
}

class ChatCompletions(private val client: Hinow) {
    suspend fun create(
        model: String,
        messages: List<Map<String, Any>>,
        temperature: Double? = null,
        maxTokens: Int? = null,
        topP: Double? = null,
        tools: List<Map<String, Any>>? = null,
        toolChoice: Any? = null
    ): JsonObject {
        val body = buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                messages.forEach { msg ->
                    addJsonObject {
                        msg.forEach { (k, v) ->
                            when (v) {
                                is String -> put(k, v)
                                is Number -> put(k, v)
                                else -> put(k, v.toString())
                            }
                        }
                    }
                }
            }

            val params = buildJsonObject {
                temperature?.let { put("temperature", it.toString()) }
                maxTokens?.let { put("max_tokens", it.toString()) }
                topP?.let { put("top_p", it.toString()) }
            }
            if (params.isNotEmpty()) put("parameters", params)

            tools?.let {
                putJsonArray("tools") {
                    it.forEach { tool -> add(Json.encodeToJsonElement(tool)) }
                }
            }
            toolChoice?.let { put("tool_choice", Json.encodeToJsonElement(it)) }
        }

        return client.post("/v1/chat/completions", body)
    }
}

class Images(private val client: Hinow) {
    suspend fun generate(
        model: String,
        prompt: String,
        n: Int? = null,
        size: String? = null,
        quality: String? = null,
        style: String? = null
    ): JsonObject {
        val body = buildJsonObject {
            put("model", model)
            put("prompt", prompt)

            val params = buildJsonObject {
                n?.let { put("n", it.toString()) }
                size?.let { put("size", it) }
                quality?.let { put("quality", it) }
                style?.let { put("style", it) }
            }
            if (params.isNotEmpty()) put("parameters", params)
        }

        return client.post("/v1/images", body)
    }
}

class Audio(private val client: Hinow) {
    suspend fun speech(
        model: String,
        input: String,
        voice: String? = null,
        speed: Double? = null
    ): JsonObject {
        val body = buildJsonObject {
            put("model", model)
            put("prompt", input) // API uses "prompt" not "input"

            val params = buildJsonObject {
                voice?.let { put("voice", it) }
                speed?.let { put("speed", it.toString()) }
            }
            if (params.isNotEmpty()) put("parameters", params)
        }

        return client.post("/v1/audio/speech", body)
    }
}

class Video(private val client: Hinow) {
    suspend fun generate(
        model: String,
        prompt: String,
        duration: Int? = null,
        resolution: String? = null,
        fps: Int? = null
    ): JsonObject {
        val body = buildJsonObject {
            put("model", model)
            put("prompt", prompt)

            val params = buildJsonObject {
                duration?.let { put("duration", it.toString()) }
                resolution?.let { put("resolution", it) }
                fps?.let { put("fps", it.toString()) }
            }
            if (params.isNotEmpty()) put("parameters", params)
        }

        return client.post("/v1/videos", body)
    }
}

class Embeddings(private val client: Hinow) {
    suspend fun create(model: String, input: Any): JsonObject {
        val body = buildJsonObject {
            put("model", model)
            when (input) {
                is String -> put("input", input)
                is List<*> -> putJsonArray("input") {
                    input.filterIsInstance<String>().forEach { add(it) }
                }
            }
        }

        return client.post("/v1/embeddings", body)
    }
}

class Models(private val client: Hinow) {
    suspend fun list(): JsonObject {
        return client.get("/v1/models")
    }
}
