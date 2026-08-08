package ai.hinow

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The nine kinds of web search. */
object SearchType {
    const val SEARCH = "search"
    const val IMAGES = "images"
    const val NEWS = "news"
    const val VIDEOS = "videos"
    const val PLACES = "places"
    const val SHOPPING = "shopping"
    const val SCHOLAR = "scholar"
    const val PATENTS = "patents"
    const val AUTOCOMPLETE = "autocomplete"

    val ALL = listOf(
        SEARCH, IMAGES, NEWS, VIDEOS, PLACES, SHOPPING, SCHOLAR, PATENTS, AUTOCOMPLETE,
    )
}

/** What a search answered. */
@Serializable
data class SearchResponse(
    val query: String = "",
    val type: String = "",
    /** Empty for `autocomplete`, which fills [suggestions] instead. */
    val results: List<SearchResult> = emptyList(),
    /** Filled only by `autocomplete`. */
    val suggestions: List<String> = emptyList(),
    @SerialName("total_results") val totalResults: Int = 0,
    /** What this call cost, in US dollars. */
    val cost: Double = 0.0,
    @SerialName("response_time_ms") val responseTimeMs: Long = 0,
)

/**
 * One search hit. Which fields are filled depends on the search type:
 * `places` fills address and phone, `news` fills source and date.
 */
@Serializable
data class SearchResult(
    val position: Int = 0,
    val title: String? = null,
    val url: String? = null,
    val link: String? = null,
    val snippet: String? = null,
    val source: String? = null,
    val date: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    val channel: String? = null,
    val duration: String? = null,
    val address: String? = null,
    val category: String? = null,
    val phone: String? = null,
    val website: String? = null,
    val rating: Double? = null,
    val price: String? = null,
    val delivery: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val width: Int? = null,
    val height: Int? = null,
)

/** An asynchronous tool run. */
@Serializable
data class ToolJob(
    /** Note the name: the API returns `job_id`, not `id`. */
    @SerialName("job_id") val jobId: String,
    val tool: String = "",
    /** queued, running, succeeded, failed or cancelled. */
    val status: String,
    @SerialName("poll_url") val pollUrl: String? = null,
    /** What the job actually cost, once it finished. */
    val cost: Double? = null,
    /** What it might cost, quoted when the job is accepted. */
    @SerialName("estimated_max_cost") val estimatedMaxCost: Double? = null,
    /** True when the answer came from cache, and nothing was charged. */
    val cached: Boolean? = null,
    val error: String? = null,
    /** Filled once the status is `succeeded`. */
    val result: ContactsResult? = null,
)

/** What a crawl found. */
@Serializable
data class ContactsResult(
    val items: List<ContactItem> = emptyList(),
    val units: Int = 0,
)

/** One finding. */
@Serializable
data class ContactItem(
    /** email, phone or social. */
    val type: String,
    /** The address, number or handle. */
    val value: String,
    /**
     * The page where it appeared. Spelled out because this one field arrives as
     * camelCase while the rest of the API is snake_case.
     */
    @SerialName("sourceUrl") val sourceUrl: String = "",
    /** For social profiles: GitHub, LinkedIn, and so on. */
    val platform: String? = null,
    /** For social profiles: the full profile URL. */
    val url: String? = null,
)

// ------------------------------------------------------------- knowledge

/** An uploaded document. */
@Serializable
data class FileObject(
    val id: String,
    val `object`: String = "",
    val bytes: Long = 0,
    @SerialName("created_at") val createdAt: Long = 0,
    val filename: String = "",
    val purpose: String = "",
)

@Serializable
data class FileList(val `object`: String = "", val data: List<FileObject> = emptyList())

/** What a delete answers. */
@Serializable
data class DeletionStatus(
    val id: String = "",
    val `object`: String = "",
    val deleted: Boolean = false,
)

/** One knowledge base, in the OpenAI shape. */
@Serializable
data class VectorStore(
    val id: String,
    val `object`: String = "",
    @SerialName("created_at") val createdAt: Long = 0,
    val name: String? = null,
    @SerialName("usage_bytes") val usageBytes: Long? = null,
    val status: String? = null,
)

@Serializable
data class VectorStoreList(val `object`: String = "", val data: List<VectorStore> = emptyList())

/** A file attached to a store. */
@Serializable
data class VectorStoreFile(
    val id: String,
    val `object`: String = "",
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("vector_store_id") val vectorStoreId: String = "",
    /** in_progress, completed, cancelled or failed. */
    val status: String,
    @SerialName("usage_bytes") val usageBytes: Long? = null,
)

@Serializable
data class VectorStoreFileList(
    val `object`: String = "",
    val data: List<VectorStoreFile> = emptyList(),
)

@Serializable
data class VectorStoreFileBatch(
    val id: String,
    val `object`: String = "",
    @SerialName("vector_store_id") val vectorStoreId: String = "",
    val status: String = "",
)

/** The passages that matched. */
@Serializable
data class RagSearchResponse(
    val query: String = "",
    val results: List<RagHit> = emptyList(),
)

/** One matching passage. */
@Serializable
data class RagHit(
    /** How close the passage is to the query. Higher is closer. */
    val score: Double = 0.0,
    @SerialName("document_id") val documentId: String = "",
    /** The file the passage came from. */
    val source: String? = null,
    @SerialName("chunk_index") val chunkIndex: Int = 0,
    /** The passage itself. */
    val text: String = "",
)

/** One knowledge base, in the HINOW shape. */
@Serializable
data class RagBase(
    val id: String,
    val name: String? = null,
    val description: String? = null,
)

@Serializable
data class RagList(val data: List<RagBase> = emptyList())

/** Text indexed directly, without uploading a file first. */
@Serializable
data class RagDocument(
    val id: String = "",
    @SerialName("rag_id") val ragId: String? = null,
    val chunks: Int? = null,
)

// ------------------------------------------------------------------ beta

/** What to create or change on an assistant. */
@Serializable
data class AssistantRequest(
    /** With namespace: `hinow/himax`, not `himax`. */
    val model: String,
    val name: String? = null,
    val description: String? = null,
    /** What the assistant should do, in its own words. */
    val instructions: String? = null,
    /** The functions it may call. */
    val tools: List<Tool>? = null,
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
)

/** A model plus instructions and tools, saved for reuse. */
@Serializable
data class Assistant(
    val id: String,
    val `object`: String = "",
    @SerialName("created_at") val createdAt: Long = 0,
    val name: String? = null,
    val model: String = "",
    val instructions: String? = null,
    val tools: List<Tool> = emptyList(),
)

@Serializable
data class AssistantList(val `object`: String = "", val data: List<Assistant> = emptyList())

/** One conversation. */
@Serializable
data class Thread(
    val id: String,
    val `object`: String = "",
    @SerialName("created_at") val createdAt: Long = 0,
)

/** One message inside a thread. */
@Serializable
data class ThreadMessage(
    val id: String,
    val `object`: String = "",
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("thread_id") val threadId: String = "",
    val role: String = "",
    val content: List<MessageContent> = emptyList(),
) {
    /**
     * The message as plain text — the first text part, which is what a normal
     * answer carries.
     */
    val text: String get() = content.firstNotNullOfOrNull { it.text?.value } ?: ""
}

@Serializable
data class MessageContent(
    val type: String = "",
    val text: TextContent? = null,
)

@Serializable
data class TextContent(val value: String = "")

@Serializable
data class ThreadMessageList(
    val `object`: String = "",
    val data: List<ThreadMessage> = emptyList(),
)

/** One execution of an assistant over a thread. */
@Serializable
data class Run(
    val id: String,
    val `object`: String = "",
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("thread_id") val threadId: String = "",
    @SerialName("assistant_id") val assistantId: String = "",
    /**
     * queued, in_progress, requires_action, completed, failed, cancelled,
     * expired or incomplete.
     */
    val status: String,
    /** Filled when the status is `requires_action`. */
    @SerialName("required_action") val requiredAction: RequiredAction? = null,
    val model: String = "",
)

/** The run asking you to run functions. */
@Serializable
data class RequiredAction(
    val type: String = "",
    @SerialName("submit_tool_outputs") val submitToolOutputs: SubmitToolOutputs? = null,
)

/** The functions the model wants you to run. */
@Serializable
data class SubmitToolOutputs(
    @SerialName("tool_calls") val toolCalls: List<ToolCall> = emptyList(),
)

/** The result of one function the run asked for. */
@Serializable
data class ToolOutput(
    @SerialName("tool_call_id") val toolCallId: String,
    /** Whatever your function returned, as a string. */
    val output: String,
)

@Serializable
data class RunList(val `object`: String = "", val data: List<Run> = emptyList())

/** One thing a run did. */
@Serializable
data class RunStep(
    val id: String,
    val `object`: String = "",
    val type: String = "",
    val status: String = "",
)

@Serializable
data class RunStepList(val `object`: String = "", val data: List<RunStep> = emptyList())
