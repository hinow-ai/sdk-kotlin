package ai.hinow

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

// ------------------------------------------------------------------ chat

/** Chat completions. */
class ChatService(client: Hinow) {
    /** The /v1/chat/completions endpoint. */
    val completions = ChatCompletions(client)
}

/** The /v1/chat/completions endpoint. */
class ChatCompletions(private val client: Hinow) {
    /** Ask a model for an answer. */
    suspend fun create(request: ChatCompletionRequest): ChatCompletion {
        validate(request)
        return client.post("/v1/chat/completions", request.copy(stream = null))
    }

    /**
     * The same call, delivered piece by piece as the model writes.
     *
     * ```
     * client.chat.completions.createStream(request) { chunk ->
     *     print(chunk.choices.firstOrNull()?.delta?.content ?: "")
     * }
     * ```
     *
     * Note `delta` instead of `message`: each chunk carries the new fragment,
     * not the answer so far. The call returns when the model is done.
     */
    suspend fun createStream(
        request: ChatCompletionRequest,
        onChunk: (ChatCompletionChunk) -> Unit,
    ) {
        validate(request)
        val body = client.json.encodeToString(
            ChatCompletionRequest.serializer(),
            request.copy(stream = true),
        )

        client.stream<ChatCompletionChunk>("/v1/chat/completions", body, onChunk)
    }

    private fun validate(request: ChatCompletionRequest) {
        if (request.model.isEmpty()) {
            throw HinowException("A chat completion needs a model, e.g. \"hinow/himax\".")
        }
        if (request.messages.isEmpty()) {
            throw HinowException("A chat completion needs at least one message.")
        }
    }
}

// ----------------------------------------------------------------- tools

/**
 * Web search and website contact extraction.
 *
 * Two execution models live here, and the difference matters:
 *
 * - [search] answers on the spot. One call, one result.
 * - [websiteContacts] starts a crawl and hands back a job. The result arrives
 *   later, so either poll [ToolJobs.retrieve] yourself or call
 *   [websiteContactsAndWait].
 */
class ToolsService(private val client: Hinow) {
    private val finished = setOf("succeeded", "failed", "cancelled")

    /** State of an asynchronous tool run. */
    val jobs = ToolJobs(client)

    /**
     * Search the web. Answers immediately — there is no job to follow.
     *
     * What comes back depends on the type: `search`, `scholar` and `patents`
     * fill position, title, url and snippet; `news` adds source and date;
     * `autocomplete` fills suggestions and leaves results empty.
     */
    suspend fun search(
        query: String,
        type: String = SearchType.SEARCH,
        country: String? = null,
        lang: String? = null,
    ): SearchResponse {
        if (type !in SearchType.ALL) {
            throw HinowException(
                "Unknown search type '$type'. Valid: ${SearchType.ALL.joinToString(", ")}"
            )
        }

        val body = buildJsonObject {
            put("query", query)
            put("type", type)
            country?.let { put("country", it) }
            lang?.let { put("lang", it) }
        }

        return client.postUnwrapped("/v1/tools/search", body)
    }

    /**
     * Crawl sites for e-mails, phone numbers and social profiles.
     *
     * Returns as soon as the crawl is accepted, carrying a job id.
     */
    suspend fun websiteContacts(
        websites: List<String>,
        maxDepth: Int? = null,
        maxLinksPerPage: Int? = null,
    ): ToolJob {
        if (websites.isEmpty()) {
            throw HinowException("A crawl needs at least one website.")
        }

        val body = buildJsonObject {
            put("websites", client.json.encodeToJsonElement(websites))
            maxDepth?.let { put("maxDepth", it) }
            maxLinksPerPage?.let { put("maxLinksPerPage", it) }
        }

        return client.postUnwrapped("/v1/tools/website-contacts", body)
    }

    /**
     * Crawl and wait for the contacts. Throws when the job fails, so what you
     * get back always carries a result.
     */
    suspend fun websiteContactsAndWait(
        websites: List<String>,
        maxDepth: Int? = null,
        maxLinksPerPage: Int? = null,
    ): ToolJob = waitForJob(websiteContacts(websites, maxDepth, maxLinksPerPage))

    /** Poll a job until it stops moving. Shared by every asynchronous tool. */
    suspend fun waitForJob(
        job: ToolJob,
        timeoutMs: Long = 300_000,
        pollIntervalMs: Long = 3_000,
    ): ToolJob {
        val deadline = System.currentTimeMillis() + timeoutMs
        var current = job

        while (current.status !in finished) {
            if (System.currentTimeMillis() >= deadline) {
                throw TimeoutException(
                    "Tool job ${current.jobId} did not finish within ${timeoutMs / 1000}s " +
                        "(status: ${current.status})"
                )
            }

            kotlinx.coroutines.delay(pollIntervalMs)
            // Mind the field name: the API returns "job_id", not "id".
            current = jobs.retrieve(current.jobId)
        }

        if (current.status != "succeeded") {
            val detail = current.error?.let { ": $it" } ?: ""
            throw HinowException("Tool job ${current.jobId} ${current.status}$detail")
        }

        return current
    }
}

/** Reads where an asynchronous tool run stands. */
class ToolJobs(private val client: Hinow) {
    /** Where a job stands. Carries a result once its status is `succeeded`. */
    suspend fun retrieve(jobId: String): ToolJob =
        client.getUnwrapped("/v1/tools/jobs/${encodePath(jobId)}")
}

// ------------------------------------------------------------- knowledge

/** Upload documents so assistants and vector stores can read them. */
class FilesService(private val client: Hinow) {
    /**
     * Upload a file from disk.
     *
     * @param purpose assistants, vision, batch or fine-tune
     */
    suspend fun create(path: String, purpose: String = "assistants"): FileObject =
        upload(readFileOrThrow(path), File(path).name, purpose)

    /** Upload content you already have in memory. */
    suspend fun upload(
        content: ByteArray,
        fileName: String,
        purpose: String = "assistants",
    ): FileObject = client.postMultipart(
        "/v1/files",
        mapOf("purpose" to purpose),
        "file",
        fileName,
        content,
    )

    /** The uploads on the account. */
    suspend fun list(): FileList = client.get("/v1/files")

    /** Read one upload. */
    suspend fun retrieve(fileId: String): FileObject =
        client.get("/v1/files/${encodePath(fileId)}")

    /** The raw bytes of a file you uploaded. */
    suspend fun content(fileId: String): ByteArray =
        client.getBytes("/v1/files/${encodePath(fileId)}/content")

    /** Remove an upload. */
    suspend fun delete(fileId: String): DeletionStatus =
        client.delete("/v1/files/${encodePath(fileId)}")
}

/**
 * Searchable knowledge bases, in the OpenAI shape.
 *
 * The same storage is exposed twice by the API: here, so code written against
 * OpenAI ports over unchanged, and under [RagService], which adds the search
 * endpoint that has no OpenAI equivalent.
 */
class VectorStoresService(private val client: Hinow) {
    /** The files inside one store. */
    val files = VectorStoreFiles(client)

    /** Several files attached at once. */
    val fileBatches = VectorStoreFileBatches(client)

    /** Make a new store. */
    suspend fun create(name: String? = null): VectorStore {
        val body = buildJsonObject { name?.let { put("name", it) } }
        return client.post("/v1/vector_stores", body)
    }

    /** The stores on the account. */
    suspend fun list(): VectorStoreList = client.get("/v1/vector_stores")

    /** Read one store. */
    suspend fun retrieve(id: String): VectorStore =
        client.get("/v1/vector_stores/${encodePath(id)}")

    /** Remove a store. */
    suspend fun delete(id: String): DeletionStatus =
        client.delete("/v1/vector_stores/${encodePath(id)}")
}

/** The files attached to one store. */
class VectorStoreFiles(private val client: Hinow) {
    /**
     * Attach a file you already uploaded. Indexing then runs on the server, so
     * the status starts at `in_progress`.
     */
    suspend fun create(vectorStoreId: String, fileId: String): VectorStoreFile {
        val body = buildJsonObject { put("file_id", fileId) }
        return client.post("/v1/vector_stores/${encodePath(vectorStoreId)}/files", body)
    }

    /** The files attached to a store. */
    suspend fun list(vectorStoreId: String): VectorStoreFileList =
        client.get("/v1/vector_stores/${encodePath(vectorStoreId)}/files")

    /** Read one attached file, including its indexing status. */
    suspend fun retrieve(vectorStoreId: String, fileId: String): VectorStoreFile =
        client.get(
            "/v1/vector_stores/${encodePath(vectorStoreId)}/files/${encodePath(fileId)}"
        )

    /**
     * Wait until the server finishes indexing. Searching before it does returns
     * nothing, with no error, so this is worth calling.
     */
    suspend fun poll(
        vectorStoreId: String,
        fileId: String,
        timeoutMs: Long = 300_000,
        pollIntervalMs: Long = 2_000,
    ): VectorStoreFile {
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            val file = retrieve(vectorStoreId, fileId)
            if (file.status != "in_progress") return file

            kotlinx.coroutines.delay(pollIntervalMs)
        }

        throw TimeoutException("File $fileId was still indexing after ${timeoutMs / 1000}s.")
    }

    /** Detach a file from a store. */
    suspend fun delete(vectorStoreId: String, fileId: String): DeletionStatus =
        client.delete(
            "/v1/vector_stores/${encodePath(vectorStoreId)}/files/${encodePath(fileId)}"
        )
}

/** Attach several files to a store in one call. */
class VectorStoreFileBatches(private val client: Hinow) {
    /** Attach a batch. */
    suspend fun create(vectorStoreId: String, fileIds: List<String>): VectorStoreFileBatch {
        val body = buildJsonObject {
            put("file_ids", client.json.encodeToJsonElement(fileIds))
        }
        return client.post("/v1/vector_stores/${encodePath(vectorStoreId)}/file_batches", body)
    }

    /** Read a batch. */
    suspend fun retrieve(vectorStoreId: String, batchId: String): VectorStoreFileBatch =
        client.get(
            "/v1/vector_stores/${encodePath(vectorStoreId)}/file_batches/${encodePath(batchId)}"
        )
}

/** Semantic search over your own documents. */
class RagService(private val client: Hinow) {
    /** Text indexed directly, without uploading a file first. */
    val documents = RagDocuments(client)

    /**
     * Search indexed documents and get back the passages that match.
     *
     * @param ragId which base to search — the `vs_…` id from vector stores.
     *   Leave it null and the search runs across every document on the account.
     *   Mind the name: the API ignores `vector_store_id` here, so sending that
     *   silently searches everything instead of the base you meant.
     */
    suspend fun search(query: String, ragId: String? = null, topK: Int? = null): RagSearchResponse {
        val body = buildJsonObject {
            put("query", query)
            ragId?.let { put("rag_id", it) }
            topK?.let { put("top_k", it) }
        }

        return client.post("/v1/rag/search", body)
    }

    /** The bases, in the HINOW shape. [VectorStoresService.list] is the OpenAI one. */
    suspend fun list(): RagList = client.get("/v1/rag/rags")

    /** Make a base. */
    suspend fun create(name: String, description: String? = null): RagBase {
        val body = buildJsonObject {
            put("name", name)
            description?.let { put("description", it) }
        }
        return client.post("/v1/rag/rags", body)
    }

    /** Read a base. */
    suspend fun retrieve(ragId: String): RagBase = client.get("/v1/rag/rags/${encodePath(ragId)}")

    /** Remove a base. */
    suspend fun delete(ragId: String): DeletionStatus =
        client.delete("/v1/rag/rags/${encodePath(ragId)}")
}

/** Text indexed directly. */
class RagDocuments(private val client: Hinow) {
    /** Index a piece of text. */
    suspend fun create(content: String, ragId: String? = null): RagDocument {
        val body = buildJsonObject {
            put("content", content)
            ragId?.let { put("rag_id", it) }
        }
        return client.post("/v1/rag/documents", body)
    }

    /** Remove an indexed document. */
    suspend fun delete(documentId: String): DeletionStatus =
        client.delete("/v1/rag/documents/${encodePath(documentId)}")
}

// ------------------------------------------------------------------ beta

/** Agents that run on the server, namespaced to match the OpenAI SDK. */
class BetaService(client: Hinow) {
    /** Saved assistants. */
    val assistants = AssistantsService(client)

    /** Conversations. */
    val threads = ThreadsService(client)
}

/** An assistant is a model plus instructions and tools, saved for reuse. */
class AssistantsService(private val client: Hinow) {
    /** Save a new assistant. */
    suspend fun create(request: AssistantRequest): Assistant {
        if (request.model.isEmpty()) {
            throw HinowException("An assistant needs a model, e.g. \"hinow/himax\".")
        }
        return client.post("/v1/assistants", request)
    }

    /** The saved assistants. */
    suspend fun list(): AssistantList = client.get("/v1/assistants")

    /** Read one assistant. */
    suspend fun retrieve(assistantId: String): Assistant =
        client.get("/v1/assistants/${encodePath(assistantId)}")

    /** Change an assistant. */
    suspend fun update(assistantId: String, request: AssistantRequest): Assistant =
        client.post("/v1/assistants/${encodePath(assistantId)}", request)

    /** Remove an assistant. */
    suspend fun delete(assistantId: String): DeletionStatus =
        client.delete("/v1/assistants/${encodePath(assistantId)}")
}

/** A thread is one conversation. Messages accumulate in it; runs act on it. */
class ThreadsService(private val client: Hinow) {
    /** The messages in a thread. */
    val messages = ThreadMessages(client)

    /** The runs on a thread. */
    val runs = RunsService(client)

    /** Open a thread. */
    suspend fun create(): Thread = client.postEmpty("/v1/threads")

    /** Read a thread. */
    suspend fun retrieve(threadId: String): Thread =
        client.get("/v1/threads/${encodePath(threadId)}")

    /** Remove a thread. */
    suspend fun delete(threadId: String): DeletionStatus =
        client.delete("/v1/threads/${encodePath(threadId)}")
}

/** Messages inside a thread. */
class ThreadMessages(private val client: Hinow) {
    /** Add a message. Role is usually `user`. */
    suspend fun create(threadId: String, content: String, role: String = "user"): ThreadMessage {
        val body = buildJsonObject {
            put("role", role)
            put("content", content)
        }
        return client.post("/v1/threads/${encodePath(threadId)}/messages", body)
    }

    /** The messages in a thread. Order is `asc` or `desc`. */
    suspend fun list(
        threadId: String,
        limit: Int? = null,
        order: String? = null,
    ): ThreadMessageList {
        val query = client.buildQuery(mapOf("limit" to limit, "order" to order))
        return client.get("/v1/threads/${encodePath(threadId)}/messages$query")
    }
}

/** A run is one execution of an assistant over a thread. */
class RunsService(private val client: Hinow) {
    /** A run is done when it stops on its own... */
    private val settled = setOf("completed", "failed", "cancelled", "expired", "incomplete")

    /** ...or when it stops to ask you for something. */
    private val waiting = "requires_action"

    /** What a run actually did, step by step. */
    val steps = RunSteps(client)

    /** Start a run. */
    suspend fun create(threadId: String, assistantId: String): Run {
        val body = buildJsonObject { put("assistant_id", assistantId) }
        return client.post("/v1/threads/${encodePath(threadId)}/runs", body)
    }

    /** Read a run. */
    suspend fun retrieve(threadId: String, runId: String): Run =
        client.get("/v1/threads/${encodePath(threadId)}/runs/${encodePath(runId)}")

    /** Stop a run. */
    suspend fun cancel(threadId: String, runId: String): Run =
        client.postEmpty("/v1/threads/${encodePath(threadId)}/runs/${encodePath(runId)}/cancel")

    /** Hand back the results of the functions the run asked you to execute. */
    suspend fun submitToolOutputs(
        threadId: String,
        runId: String,
        outputs: List<ToolOutput>,
    ): Run {
        val body = buildJsonObject {
            put("tool_outputs", client.json.encodeToJsonElement(outputs))
        }

        return client.post(
            "/v1/threads/${encodePath(threadId)}/runs/${encodePath(runId)}/submit_tool_outputs",
            body,
        )
    }

    /**
     * Wait until the run finishes, or until it stops to ask for tool outputs.
     *
     * Returning on `requires_action` is deliberate: that is not a failure, it
     * is the run handing control back to you. Read `requiredAction`, call
     * [submitToolOutputs] and poll again.
     */
    suspend fun poll(
        threadId: String,
        runId: String,
        timeoutMs: Long = 300_000,
        pollIntervalMs: Long = 1_000,
    ): Run {
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            val run = retrieve(threadId, runId)
            if (isDone(run)) return run

            kotlinx.coroutines.delay(pollIntervalMs)
        }

        throw TimeoutException("Run $runId did not settle within ${timeoutMs / 1000}s.")
    }

    /** Start a run and wait for it. */
    suspend fun createAndPoll(threadId: String, assistantId: String): Run {
        val run = create(threadId, assistantId)
        return if (isDone(run)) run else poll(threadId, run.id)
    }

    private fun isDone(run: Run) = run.status == waiting || run.status in settled
}

/** What a run actually did — useful when debugging an agent. */
class RunSteps(private val client: Hinow) {
    /** The steps of a run. */
    suspend fun list(threadId: String, runId: String): RunStepList =
        client.get("/v1/threads/${encodePath(threadId)}/runs/${encodePath(runId)}/steps")
}

// ----------------------------------------------------------------- media

/** The model catalogue: what exists and what it can do. */
class ModelsService(private val client: Hinow) {
    /** Every model available to your key. */
    suspend fun list(): ModelsResponse = client.get("/v1/models")

    /**
     * One model, with its price and the options it accepts.
     *
     * The id carries a namespace, and each segment is escaped on its own —
     * escaping the slash itself makes the API answer 404.
     */
    suspend fun retrieve(modelId: String): ModelInfo =
        client.get("/v1/models/${encodePath(modelId)}")
}

/** Turn text into vectors for semantic search. */
class EmbeddingsService(private val client: Hinow) {
    /**
     * @param model an embedding model, not a chat model. The catalogue lists
     *   them under the text_to_embedding category, e.g. `BAAI/bge-m3`.
     */
    suspend fun create(model: String, input: List<String>): EmbeddingResponse {
        val body = buildJsonObject {
            put("model", model)
            put("input", client.json.encodeToJsonElement(input))
        }
        return client.post("/v1/embeddings", body)
    }

    /** The same, for a single text. */
    suspend fun create(model: String, input: String): EmbeddingResponse =
        create(model, listOf(input))
}

/** Image generation. */
class ImagesService(private val client: Hinow) {
    /**
     * Draw an image from a description.
     *
     * @param size "1024x1024" — split into width and height for you
     */
    suspend fun generate(
        model: String,
        prompt: String,
        size: String? = null,
        n: Int? = null,
        aspectRatio: String? = null,
        outputFormat: String? = null,
        seed: Int? = null,
    ): MediaResponse {
        val parameters = buildJsonObject {
            n?.let { put("n", it) }
            size?.let {
                val parts = it.split("x")
                if (parts.size == 2) {
                    put("width", parts[0].toInt())
                    put("height", parts[1].toInt())
                }
            }
            aspectRatio?.let { put("aspect_ratio", it) }
            outputFormat?.let { put("output_format", it) }
            seed?.let { put("seed", it) }
        }

        val body = buildJsonObject {
            put("model", model)
            put("prompt", prompt)
            // Unlike chat, this endpoint really does read a "parameters" object.
            if (parameters.isNotEmpty()) put("parameters", parameters)
        }

        return normalizeMedia(client.postUnwrapped<JsonObject, JsonObject>("/v1/images", body))
    }

    private fun normalizeMedia(data: JsonObject): MediaResponse {
        val urls = data["urls"]?.let { client.json.decodeFromJsonElement<List<String>>(it) }
            ?: emptyList()

        return MediaResponse(
            data = urls.map { GeneratedFile(it) },
            model = data["model"]?.let { client.json.decodeFromJsonElement<String?>(it) },
            provider = data["provider"]?.let { client.json.decodeFromJsonElement<String?>(it) },
        )
    }
}

/** Video generation. */
class VideoService(private val client: Hinow) {
    suspend fun generate(
        model: String,
        prompt: String,
        duration: Int? = null,
        resolution: String? = null,
        fps: Int? = null,
    ): MediaResponse {
        val parameters = buildJsonObject {
            duration?.let { put("duration", it) }
            resolution?.let { put("resolution", it) }
            fps?.let { put("fps", it) }
        }

        val body = buildJsonObject {
            put("model", model)
            put("prompt", prompt)
            if (parameters.isNotEmpty()) put("parameters", parameters)
        }

        val data = client.postUnwrapped<JsonObject, JsonObject>("/v1/videos", body)
        val urls = data["urls"]?.let { client.json.decodeFromJsonElement<List<String>>(it) }
            ?: emptyList()

        return MediaResponse(data = urls.map { GeneratedFile(it) })
    }
}

/** Speech: text to audio, and audio to text. */
class AudioService(private val client: Hinow) {
    /** Read a text out loud. */
    suspend fun speech(
        model: String,
        input: String,
        voice: String? = null,
        speed: Double? = null,
        language: String? = null,
    ): SpeechResponse {
        val parameters = buildJsonObject {
            // On the wire the field is voice_id.
            voice?.let { put("voice_id", it) }
            speed?.let { put("speed", it) }
            language?.let { put("language", it) }
        }

        val body = buildJsonObject {
            put("model", model)
            // The endpoint calls the text "prompt", not "input".
            put("prompt", input)
            if (parameters.isNotEmpty()) put("parameters", parameters)
        }

        val data = client.postUnwrapped<JsonObject, JsonObject>("/v1/audio/speech", body)
        val urls = data["urls"]?.let { client.json.decodeFromJsonElement<List<String>>(it) }
            ?: emptyList()

        return SpeechResponse(audioUrl = urls.firstOrNull())
    }

    /** Turn a local audio file into text. */
    suspend fun transcribe(
        model: String,
        path: String,
        language: String? = null,
    ): TranscriptionResponse {
        val fields = mutableMapOf("model" to model)
        language?.let { fields["language"] = it }

        val data: JsonObject = client.postMultipart(
            "/v1/audio/transcriptions",
            fields,
            "file",
            File(path).name,
            readFileOrThrow(path),
        )

        return client.unwrap(data.toString())
    }
}
