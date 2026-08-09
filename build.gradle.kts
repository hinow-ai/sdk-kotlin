plugins {
    kotlin("jvm") version "1.9.0"
    kotlin("plugin.serialization") version "1.9.0"
    // java-library habilita a configuração `api`, necessária para expor as
    // dependências que aparecem em assinaturas públicas.
    `java-library`
    `maven-publish`
}

group = "ai.hinow"
version = "2.0.2"

repositories {
    mavenCentral()
}

dependencies {
    // `api` e não `implementation`: estes tipos aparecem na API pública —
    // JsonElement em ChatCompletionRequest.toolChoice e Tool.parameters — e
    // com `implementation` o consumidor não compila:
    //   Cannot access class 'kotlinx.serialization.json.JsonElement'
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // O Ktor fica escondido atrás do cliente, então continua interno.
    implementation("io.ktor:ktor-client-core:2.3.5")
    implementation("io.ktor:ktor-client-cio:2.3.5")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.5")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.5")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

// Targets Java 11 bytecode without demanding a JDK 11 toolchain be present:
// jvmToolchain(11) made the build fail on machines that only have a newer JDK.
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "11"
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "com.github.hinow-ai"
            artifactId = "sdk-kotlin"
            version = "2.0.2"
            from(components["java"])
        }
    }
}
