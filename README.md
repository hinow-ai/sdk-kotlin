# HINOW Kotlin SDK

Official Kotlin SDK for the [HINOW AI API](https://hinow.ai).

Built on Ktor and coroutines, with typed responses through
kotlinx.serialization. The API speaks the OpenAI protocol, so the shape of these
calls is the one you already know.

## Requirements

- Java 11 or newer

## Installation

The library is published through JitPack, so the repository has to be declared
alongside the dependency.

```kotlin
repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.hinow-ai:sdk-kotlin:v2.0.0")
}
```

Keep the key in the environment and the client finds it on its own:

```bash
export HINOW_API_KEY="hi_your_key_here"
```

## First call

```kotlin
import ai.hinow.*
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    Hinow().use { client ->
        val answer = client.chat.completions.create(
            ChatCompletionRequest(
                model = "hinow/himax",
                messages = listOf(Message.user("Explain what an API is in one paragraph.")),
            )
        )

        println(answer.choices[0].message.text)
    }
}
```

Every call is a `suspend fun`, so it belongs inside a coroutine. `use { }` closes
the underlying HTTP client when the block ends.

The `hinow/` prefix is part of the model name. Sending `himax` instead of
`hinow/himax` answers `404 model_not_found`.

## Streaming

`createStream` hands each chunk to your lambda as it arrives, and returns when
the model is done. Each chunk carries `delta` — the new fragment — not the
answer so far.

```kotlin
client.chat.completions.createStream(request) { chunk ->
    print(chunk.choices.firstOrNull()?.delta?.content ?: "")
}
```

## Web search

Answers on the spot, US$ 0.005 per call.

```kotlin
val search = client.tools.search(
    query = "best beaches in northeast Brazil",
    type = SearchType.SEARCH,
    country = "br",
    lang = "pt-br",
)

search.results.forEach { println("${it.position}. ${it.title}\n   ${it.url}") }
```

Nine types live in `SearchType`. Which fields are filled depends on the type:

| Type | Each result carries |
| --- | --- |
| `SEARCH`, `SCHOLAR`, `PATENTS` | `position`, `title`, `url`, `snippet` |
| `NEWS` | the above plus `source`, `date`, `imageUrl` |
| `IMAGES` | `link`, `imageUrl`, `thumbnailUrl`, `width`, `height` |
| `VIDEOS` | `channel`, `duration`, `date`, `thumbnailUrl` |
| `PLACES` | `address`, `category`, `phone`, `website`, `rating` |
| `SHOPPING` | `price`, `delivery`, `rating`, `source` |
| `AUTOCOMPLETE` | `suggestions`; `results` stays empty |

## Website contacts

This one crawls, so it runs as a job.

```kotlin
val job = client.tools.websiteContactsAndWait(
    websites = listOf("https://example.com"),
    maxDepth = 2,
    maxLinksPerPage = 10,
)

job.result?.items?.forEach {
    println("${it.type}: ${it.value} (${it.sourceUrl})")
}
```

To drive the loop yourself, use `websiteContacts` and follow it with
`tools.jobs.retrieve(job.jobId)`. Mind the field name: it is `job_id`, not `id`.

## Agents

Assistants, threads and runs, in the OpenAI shape. The model decides when to
call your functions; the run stops, you execute, you hand the result back.

```kotlin
val assistant = client.beta.assistants.create(
    AssistantRequest(
        model = "hinow/himax",
        name = "Support",
        instructions = "Check the order before stating any status.",
        tools = listOf(Tool.function("get_order", "Look up an order by code.", schema)),
    )
)

val thread = client.beta.threads.create()
client.beta.threads.messages.create(thread.id, "Has order A-1002 arrived?")

var run = client.beta.threads.runs.createAndPoll(thread.id, assistant.id)

while (run.status == "requires_action") {
    val outputs = run.requiredAction?.submitToolOutputs?.toolCalls.orEmpty().map { call ->
        ToolOutput(call.id, getOrder(call.function.arguments))
    }

    client.beta.threads.runs.submitToolOutputs(thread.id, run.id, outputs)
    run = client.beta.threads.runs.poll(thread.id, run.id)
}

println(client.beta.threads.messages.list(thread.id, limit = 1, order = "desc").data[0].text)
```

`requires_action` is not an error — it is the run handing control back to you.
That is why `poll` returns in that state instead of spinning.

## Documents and semantic search

```kotlin
val file = client.files.create("returns-policy.txt")
val store = client.vectorStores.create("Support base")
client.vectorStores.files.create(store.id, file.id)

// Indexing is asynchronous. Searching too early returns nothing, with no error.
client.vectorStores.files.poll(store.id, file.id)

val hits = client.rag.search(
    query = "how many days do I have to return an item?",
    ragId = store.id,
    topK = 3,
)

hits.results.forEach { println("%.2f  %s".format(it.score, it.source)) }
```

The filter is `ragId`. The API ignores `vector_store_id` here, so the search
would quietly run across every document on the account instead of the base you
meant.

## Errors

Each failure has its own class, so you can catch by type instead of reading the
message.

```kotlin
try {
    client.chat.completions.create(request)
} catch (e: AuthenticationException) {
    // 401 — check HINOW_API_KEY
} catch (e: NotFoundException) {
    // 404 — usually a model name missing its `hinow/` prefix
} catch (e: RateLimitException) {
    // 429 — already retried; back off further
} catch (e: HinowException) {
    println("${e.statusCode} ${e.message}")
}
```

`ConnectionException` covers the case where the request never reached the API,
so nothing was charged.

## What the client exposes

| Property | For |
| --- | --- |
| `chat.completions` | Conversation, streaming, function calling, JSON mode |
| `embeddings` | Vectors for semantic search |
| `images` · `audio` · `video` | Generation |
| `models` | Catalogue, price and capabilities |
| `tools` | Web search and website contacts |
| `files` | Document upload |
| `vectorStores` | Searchable knowledge bases |
| `rag` | Semantic search over your documents |
| `beta.assistants` · `beta.threads` | Server-side agents |
| `getBalance()` | Account credit |

## Configuration

```kotlin
val client = Hinow(
    apiKey = System.getenv("HINOW_API_KEY"),  // or leave it out and use the variable
    baseUrl = "https://api.hinow.ai",         // or HINOW_BASE_URL
    timeout = 120_000,                        // milliseconds
    maxRetries = 2,                           // rate limits and 5xx
)
```

## Upgrading from 1.x

The 1.0.0 release returned raw `JsonObject` from every call and **never checked
the HTTP status**, so a 401 or a 404 came back looking like a successful
result — the error JSON was simply handed to you as the answer. That is fixed:
responses are typed data classes, and anything outside 2xx raises the matching
exception.

Two more things changed:

- `chat.completions.create` took loose parameters and packed `temperature`,
  `maxTokens` and `topP` into a `parameters` object with the numbers turned into
  strings. The API accepts that object and **ignores** it, so those settings
  never took effect. It now takes a `ChatCompletionRequest` and sends the fields
  at the root.
- `Message` is a data class with a `text` property, instead of a
  `Map<String, Any>`.

## License

MIT
