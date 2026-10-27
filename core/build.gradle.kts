plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Shared by the Android and desktop apps: search, matching, imports, lyrics, library, updates.
kotlin {
    jvmToolchain(17)
}

dependencies {
    api("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // Android ships org.json built in; the desktop app adds the real library.
    compileOnly("org.json:json:20240303")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
