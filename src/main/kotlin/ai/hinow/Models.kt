package ai.hinow

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// ------------------------------------------------------------------ chat

/**
 * What you send to the chat endpoint.
 *
 * Every field travels at the root of the request, where the API reads it. Up to
 * 1.0.0 this SDK packed temperature, maxTokens and topP into a `parameters`
 * object with the numbers turned into strings; the API accepts that object and
 * ignores it, so those settings quietly did nothing.
 */
@Serializable
data class ChatCompletionRequest(
    /** With namespace: `hinow/himax`, not `himax`. */
    val model: String,
    val messages: List<Message>,
    val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("top_p") val topP: Double? = null,
    @SerialName("repetition_penalty") val repetitionPenalty: Double? = null,
    val seed: Int? = null,
    val stop: List<String>? = null,
    /** Set to `ResponseFormat("json_object")` for JSON only. */
    @SerialName("response_format") val responseFormat: ResponseFormat? = null,
    /** The functions the model may call. */
    val tools: List<Tool>? = null,
    @SerialName("tool_choice") val toolChoice: JsonElement? = null,
    val stream: Boolean? = null,
)

/** Pins the shape of the answer. */
@Serializable
data class ResponseFormat(val type: String)

/** One turn of a conversation. */
@Serializable
data class Message(
    val role: String,
    /** Plain text. For images, use [contentParts]. */
    val content: String? = null,
    val name: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
) {
    /** The text of the message, or an empty string when there is none. */
    val text: String get() = content ?: ""

    companion object {
        fun system(content: String) = Message(role = "system", content = content)
        fun user(content: String) = Message(role = "user", content = content)
        fun assistant(content: String) = Message(role = "assistant", content = content)

        /** Answer a function the model asked you to run. */
        fun tool(toolCallId: String, content: String) =
            Message(role = "tool", content = content, toolCallId = toolCallId)
    }
}

/** Kept for symmetry with the other SDKs; not used by the request body. */
@Serializable
data class ContentPart(
    val type: String,
    val text: String? = null,
    @SerialName("image_url") val imageUrl: ImageUrl? = null,
)

@Serializable
data class ImageUrl(val url: String, val detail: String? = null)

/** A function the model may decide to call. */
@Serializable
data class Tool(
    // @EncodeDefault because the client leaves defaults out of the request to
    // keep it small — and without it this field went out empty, which the API
    // answers with "invalid tool type".
    @EncodeDefault @OptIn(ExperimentalSerializationApi::class)
    val type: String = "function",
    val function: ToolFunction,
) {
    companion object {
        /**
         * Describe a function. The description is what the model reads when
         * deciding whether to call it, so write it for a reader.
         *
         * @param parameters a JSON Schema object describing the arguments
         */
        fun function(name: String, description: String, parameters: JsonElement) =
            Tool(function = ToolFunction(name, description, parameters))
    }
}

@Serializable
data class ToolFunction(
    val name: String,
    val description: String? = null,
    val parameters: JsonElement? = null,
)

/** The model asking you to run one of your functions. */
@Serializable
data class ToolCall(
    val id: String,
    @EncodeDefault @OptIn(ExperimentalSerializationApi::class)
    val type: String = "function",
    val function: FunctionCall,
)

@Serializable
data class FunctionCall(
    val name: String,
    /** A JSON object, as a string. Parse it before using it. */
    val arguments: String,
)

/** A full answer. */
@Serializable
data class ChatCompletion(
    val id: String = "",
    val `object`: String = "",
    val created: Long = 0,
    val model: String = "",
    val choices: List<Choice> = emptyList(),
    val usage: Usage? = null,
)

@Serializable
data class Choice(
    val index: Int = 0,
    val message: Message,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
)

/** One chunk of a streamed answer. */
@Serializable
data class ChatCompletionChunk(
    val id: String = "",
    val `object`: String = "",
    val created: Long = 0,
    val model: String = "",
    val choices: List<ChunkChoice> = emptyList(),
)

@Serializable
data class ChunkChoice(
    val index: Int = 0,
    /** The new fragment, not the answer so far. */
    val delta: Delta = Delta(),
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class Delta(
    val role: String? = null,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
)

// --------------------------------------------------------------- balance

/** Credit left on the account. */
@Serializable
data class Balance(
    /** In US dollars. */
    val balance: Double = 0.0,
    @SerialName("account_id") val accountId: String? = null,
    @SerialName("project_id") val projectId: String? = null,
)

// -------------------------------------------------------------- catalogue

/** The model catalogue. */
@Serializable
data class ModelsResponse(
    val `object`: String = "",
    val data: List<ModelInfo> = emptyList(),
)

/** One entry in the catalogue. */
@Serializable
data class ModelInfo(
    /** Carries a namespace, e.g. `hinow/himax`. */
    val id: String,
    val `object`: String = "",
    val name: String? = null,
    /** What the model does: text_to_text, text_to_image, and so on. */
    val category: List<String> = emptyList(),
    val created: Long = 0,
    @SerialName("owned_by") val ownedBy: String = "",
    val endpoint: String? = null,
    val cost: ModelCost? = null,
)

/** What a model charges. */
@Serializable
data class ModelCost(
    /** The unit, e.g. `mtoken` for a price per million tokens. */
    val type: String? = null,
    val total: Double? = null,
    val input: Double? = null,
    val output: Double? = null,
)

// ------------------------------------------------------------ embeddings

@Serializable
data class EmbeddingResponse(
    val `object`: String = "",
    val data: List<EmbeddingData> = emptyList(),
    val model: String = "",
    val usage: EmbeddingUsage? = null,
)

@Serializable
data class EmbeddingData(
    val `object`: String = "",
    val index: Int = 0,
    val embedding: List<Double> = emptyList(),
)

@Serializable
data class EmbeddingUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
)

// ----------------------------------------------------------------- media

/** Where a generated file is stored. */
@Serializable
data class GeneratedFile(val url: String)

@Serializable
data class MediaResponse(
    val created: Long = 0,
    val data: List<GeneratedFile> = emptyList(),
    val model: String? = null,
    val provider: String? = null,
)

@Serializable
data class SpeechResponse(
    val audioUrl: String? = null,
    val duration: Double? = null,
    val provider: String? = null,
)

@Serializable
data class TranscriptionResponse(
    val text: String = "",
    val language: String? = null,
    val duration: Double? = null,
)
