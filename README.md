# HINOW Kotlin SDK

Official Kotlin SDK for the [HINOW AI Inference API](https://hinow.ai).

Works with Android and JVM.

## Installation

### Gradle (JitPack)

```kotlin
repositories {
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("com.github.hinow-ai:sdk-kotlin:v1.0.0")
}
```

## Quick Start

```kotlin
import ai.hinow.Hinow
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val client = Hinow("hi_your_api_key")

    val response = client.chat.completions().create(
        model = "deepseek-ai/deepseek-v3.2",
        messages = listOf(
            mapOf("role" to "user", "content" to "Hello!")
        ),
        temperature = 0.7
    )

    println(response["choices"]?.jsonArray?.get(0)?.jsonObject?.get("message")?.jsonObject?.get("content"))

    client.close()
}
```

## Features

### Chat Completions

```kotlin
val response = client.chat.completions().create(
    model = "deepseek-ai/deepseek-v3.2",
    messages = listOf(
        mapOf("role" to "system", "content" to "You are a helpful assistant."),
        mapOf("role" to "user", "content" to "What is the capital of France?")
    ),
    temperature = 0.7,
    maxTokens = 1000
)
```

### Image Generation

```kotlin
val response = client.images.generate(
    model = "dall-e-3",
    prompt = "A beautiful sunset over mountains",
    size = "1024x1024",
    quality = "hd"
)
```

### Text to Speech

```kotlin
val response = client.audio.speech(
    model = "tts-1",
    input = "Hello, how are you today?",
    voice = "alloy",
    speed = 1.0
)
```

### Video Generation

```kotlin
val response = client.video.generate(
    model = "video-model",
    prompt = "A cat playing piano",
    duration = 5,
    resolution = "1080p"
)
```

### Embeddings

```kotlin
val response = client.embeddings.create(
    model = "text-embedding-ada-002",
    input = "Hello world"
)
```

### List Models

```kotlin
val response = client.models.list()
```

### Check Balance

```kotlin
val balance = client.getBalance()
```

## Android Usage

```kotlin
class MainActivity : AppCompatActivity() {
    private val client = Hinow("hi_your_api_key")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            val response = client.chat.completions().create(
                model = "deepseek-ai/deepseek-v3.2",
                messages = listOf(mapOf("role" to "user", "content" to "Hello!"))
            )
            // Handle response
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        client.close()
    }
}
```

## Links

- [HINOW Website](https://hinow.ai)
- [API Platform](https://platform.hinow.ai)
- [GitHub](https://github.com/hinow-ai/sdk-kotlin)

## License

MIT License
