import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
}

val songifyVersion = "0.6.0"

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.json:json:20240303")
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")
    // Audio: rewrite fragmented MP4 into a regular MP4 (no re-encoding), then decode in pure Java.
    implementation("org.mp4parser:muxer:1.9.56")
    implementation("de.sfuhrm:jaad:0.8.7")
    implementation("com.googlecode.soundlibs:jlayer:1.0.1.4")
}

// Lets the app read its own version at runtime (shown in Settings, used for update checks).
val generateVersion by tasks.registering {
    val out = layout.buildDirectory.dir("generated/version")
    outputs.dir(out)
    doLast {
        val file = out.get().file("com/songify/desktop/Version.kt").asFile
        file.parentFile.mkdirs()
        file.writeText("package com.songify.desktop\n\nconst val APP_VERSION = \"$songifyVersion\"\n")
    }
}
sourceSets.main { kotlin.srcDir(generateVersion) }

tasks.register<JavaExec>("probeAudio") {
    description = "Fetches one song and checks that JavaFX can play it."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.songify.desktop.AudioProbeKt")
}

tasks.register<JavaExec>("renderProbe") {
    description = "Renders the window offscreen to PNGs while a song plays silently."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.songify.desktop.RenderProbeKt")
    args(layout.buildDirectory.dir("render").get().asFile.absolutePath)
}

compose.desktop {
    application {
        mainClass = "com.songify.desktop.MainKt"
        // Shrinking isn't worth it on desktop, and ProGuard trips over the extractor's dependencies.
        buildTypes.release.proguard { isEnabled.set(false) }
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Songify"
            packageVersion = songifyVersion
            description = "Songify music player"
            vendor = "Sammam Mahdi"
            // jdk.crypto.ec: modern HTTPS ciphers; java.scripting/jdk.dynalink: the JavaScript engine used for stream URLs.
            modules("java.instrument", "java.net.http", "jdk.dynalink", "jdk.unsupported", "jdk.crypto.ec", "java.naming", "java.scripting", "java.sql")
            windows {
                menuGroup = "Songify"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                // Keeps the same id across versions so new installers upgrade the old one.
                upgradeUuid = "6b3f4a6e-6c1f-4d7e-9c84-5b2f0f6a9e21"
                iconFile.set(project.file("icon.ico"))
            }
        }
    }
}
