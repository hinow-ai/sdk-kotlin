package ai.hinow

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.net.URLEncoder
import kotlin.math.min
import kotlin.math.pow

/**
 * HINOW AI client.
 *
 * The API speaks the OpenAI protocol, so the shape of these calls is the one
 * you already know:
 *
 * ```
 * val client = Hinow()  // reads HINOW_API_KEY
 *
 * val answer = client.chat.completions.create(
 *     ChatCompletionRequest(
 *         model = "hinow/himax",
 *         messages = listOf(Message.user("Olá!")),
 *     )
 * )
 *
 * println(answer.choices[0].message.text)
 * ```
 *
 * Close the client when you are done, or use it inside `use { }`.
 */
class Hinow(
    private val apiKey: String = System.getenv("HINOW_API_KEY") ?: "",
    private val baseUrl: String = System.getenv("HINOW_BASE_URL") ?: DEFAULT_BASE_URL,
    private val timeout: Long = DEFAULT_TIMEOUT_MS,
    private val maxRetries: Int = DEFAULT_MAX_RETRIES,
) : AutoCloseable {

    companion object {
        const val VERSION = "2.0.2"
        const val DEFAULT_BASE_URL = "https://api.hinow.ai"
        const val DEFAULT_TIMEOUT_MS = 120_000L
        const val DEFAULT_MAX_RETRIES = 2
    }

    init {
        require(apiKey.isNotEmpty()) {
            "API key is required. Set the HINOW_API_KEY environment variable, " +
                "or pass apiKey to the constructor."
        }
    }

    internal val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
        explicitNulls = false
    }

    private val host = baseUrl.trimEnd('/')

    private val httpClient = HttpClient(CIO) {
        // Ktor would throw its own exception on a non-2xx; the SDK reads the
        // body first so it can raise a typed error with the API's own message.
        expectSuccess = false

        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = timeout
            connectTimeoutMillis = timeout
            socketTimeoutMillis = timeout
        }
        defaultRequest {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            header(HttpHeaders.Accept, "application/json")
            header(HttpHeaders.UserAgent, "hinow-kotlin/$VERSION")
        }
    }

    /** Conversation, streaming, function calling and JSON mode. */
    val chat = ChatService(this)

    /** Image generation. */
    val images = ImagesService(this)

    /** Text to speech and speech to text. */
    val audio = AudioService(this)

    /** Video generation. */
    val video = VideoService(this)

    /** Vectors for semantic search. */
    val embeddings = EmbeddingsService(this)

    /** The model catalogue. */
    val models = ModelsService(this)

    /** Web search and website contact extraction. */
    val tools = ToolsService(this)

    /** Document upload. */
    val files = FilesService(this)

    /** Searchable knowledge bases, in the OpenAI shape. */
    val vectorStores = VectorStoresService(this)

    /** Semantic search over your own documents. */
    val rag = RagService(this)

    /** Agents that run on the server: assistants, threads, runs. */
    val beta = BetaService(this)

    /** Credit left on the account, in US dollars. */
    suspend fun getBalance(): Balance {
        // The endpoint answers {"data": {...}, "success": true}.
        val body = requestText(HttpMethod.Get, "/v1/balance")
        val data = Json.parseToJsonElement(body).jsonObject["data"]
            ?: throw HinowException("The balance response carried no data.")

        return json.decodeFromJsonElement(Balance.serializer(), data)
    }

    // ---------------------------------------------------------------- HTTP

    internal suspend inline fun <reified T> get(path: String): T =
        json.decodeFromString(requestText(HttpMethod.Get, path))

    internal suspend inline fun <reified T, reified B> post(path: String, body: B): T =
        json.decodeFromString(requestText(HttpMethod.Post, path, json.encodeToString(body)))

    internal suspend inline fun <reified T> postEmpty(path: String): T =
        json.decodeFromString(requestText(HttpMethod.Post, path, "{}"))

    internal suspend inline fun <reified T> delete(path: String): T =
        json.decodeFromString(requestText(HttpMethod.Delete, path))

    /** Unwrap the `{"data": ..., "success": true}` envelope. */
    internal suspend inline fun <reified T> getUnwrapped(path: String): T =
        unwrap(requestText(HttpMethod.Get, path))

    internal suspend inline fun <reified T, reified B> postUnwrapped(path: String, body: B): T =
        unwrap(requestText(HttpMethod.Post, path, json.encodeToString(body)))

    internal inline fun <reified T> unwrap(body: String): T {
        val root = Json.parseToJsonElement(body).jsonObject
        val data = root["data"] ?: return json.decodeFromString(body)
        return json.decodeFromJsonElement(data)
    }

    /**
     * Send a request, retrying rate limits and 5xx, and hand back the body.
     */
    internal suspend fun requestText(
        method: HttpMethod,
        path: String,
        jsonBody: String? = null,
    ): String {
        var attempt = 0

        while (true) {
            val response: HttpResponse = try {
                httpClient.request("$host$path") {
                    this.method = method
                    if (jsonBody != null) {
                        contentType(ContentType.Application.Json)
                        setBody(jsonBody)
                    }
                }
            } catch (e: Exception) {
                if (attempt < maxRetries) {
                    backOff(++attempt, null)
                    continue
                }
                throw ConnectionException("Could not reach the HINOW API: ${e.message}", e)
            }

            val status = response.status.value

            if ((status == 429 || status >= 500) && attempt < maxRetries) {
                backOff(++attempt, response.headers[HttpHeaders.RetryAfter])
                continue
            }

            val text = response.bodyAsText()

            // Any 2xx is a success: creating answers 201 and starting a tool
            // job answers 202. The old client checked nothing at all, so an
            // error body came back looking like a result.
            if (status !in 200..299) {
                throw errorFor(status, text)
            }

            return text
        }
    }

    /** Upload a form. Rebuilt per attempt, since a body cannot be replayed. */
    internal suspend inline fun <reified T> postMultipart(
        path: String,
        fields: Map<String, String>,
        fileField: String?,
        fileName: String?,
        content: ByteArray?,
    ): T {
        var attempt = 0

        while (true) {
            val response: HttpResponse = try {
                httpClient.request("$host$path") {
                    method = HttpMethod.Post
                    setBody(
                        MultiPartFormDataContent(
                            formData {
                                fields.forEach { (name, value) -> append(name, value) }
                                if (fileField != null && content != null) {
                                    append(
                                        fileField,
                                        content,
                                        Headers.build {
                                            append(
                                                HttpHeaders.ContentDisposition,
                                                "filename=\"${fileName ?: "upload.bin"}\"",
                                            )
                                        },
                                    )
                                }
                            }
                        )
                    )
                }
            } catch (e: Exception) {
                if (attempt < maxRetries) {
                    backOff(++attempt, null)
                    continue
                }
                throw ConnectionException("Could not reach the HINOW API: ${e.message}", e)
            }

            val status = response.status.value

            if ((status == 429 || status >= 500) && attempt < maxRetries) {
                backOff(++attempt, response.headers[HttpHeaders.RetryAfter])
                continue
            }

            val text = response.bodyAsText()
            if (status !in 200..299) {
                throw errorFor(status, text)
            }

            return json.decodeFromString(text)
        }
    }

    /** Raw bytes instead of decoded JSON — file downloads use this. */
    internal suspend fun getBytes(path: String): ByteArray {
        val response = httpClient.request("$host$path") { method = HttpMethod.Get }
        val status = response.status.value

        if (status !in 200..299) {
            throw errorFor(status, response.bodyAsText())
        }

        return response.bodyAsChannel().let { channel ->
            val buffer = mutableListOf<Byte>()
            while (!channel.isClosedForRead) {
                val chunk = ByteArray(8192)
                val read = channel.readAvailable(chunk, 0, chunk.size)
                if (read <= 0) break
                buffer.addAll(chunk.take(read))
            }
            buffer.toByteArray()
        }
    }

    /**
     * Server-sent events, handed to the callback one decoded event at a time.
     */
    internal suspend inline fun <reified T> stream(
        path: String,
        jsonBody: String,
        crossinline onEvent: (T) -> Unit,
    ) {
        try {
            httpClient.preparePost("$host$path") {
                header(HttpHeaders.Accept, "text/event-stream")
                contentType(ContentType.Application.Json)
                setBody(jsonBody)
            }.execute { response ->
                val status = response.status.value
                if (status !in 200..299) {
                    throw errorFor(status, response.bodyAsText())
                }

                val channel = response.bodyAsChannel()
                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break
                    val trimmed = line.trim()

                    if (trimmed.isEmpty() || trimmed.startsWith(":")) continue
                    if (!trimmed.startsWith("data:")) continue

                    val payload = trimmed.removePrefix("data:").trim()
                    if (payload.isEmpty() || payload == "[DONE]") continue

                    // A malformed event is skipped rather than killing the stream.
                    val event = try {
                        json.decodeFromString<T>(payload)
                    } catch (ignored: Exception) {
                        null
                    }

                    if (event != null) onEvent(event)
                }
            }
        } catch (e: HinowException) {
            throw e
        } catch (e: Exception) {
            throw ConnectionException("Could not reach the HINOW API: ${e.message}", e)
        }
    }

    /** Back off, honouring Retry-After when the API sends one. */
    internal suspend fun backOff(attempt: Int, retryAfter: String?) {
        val seconds = retryAfter?.toDoubleOrNull() ?: (0.5 * 2.0.pow(attempt - 1))
        delay((min(seconds, 20.0) * 1000).toLong())
    }

    override fun close() {
        httpClient.close()
    }

    internal fun buildQuery(params: Map<String, Any?>): String {
        val pieces = params.entries
            .filter { it.value != null }
            .joinToString("&") { "${it.key}=${encodeSegment(it.value.toString())}" }

        return if (pieces.isEmpty()) "" else "?$pieces"
    }
}

/**
 * Escape each path segment on its own. Model ids carry a namespace —
 * `hinow/himax` — and escaping the slash makes the API answer 404.
 */
internal fun encodePath(value: String): String =
    value.split("/").joinToString("/") { encodeSegment(it) }

internal fun encodeSegment(value: String): String =
    URLEncoder.encode(value, "UTF-8").replace("+", "%20")

/** Read a file into memory, with a message that names the path when it fails. */
internal fun readFileOrThrow(path: String): ByteArray {
    val file = File(path)
    if (!file.canRead()) {
        throw HinowException("Cannot read file: $path")
    }
    return file.readBytes()
}
