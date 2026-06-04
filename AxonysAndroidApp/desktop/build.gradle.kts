plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
}

// This module stores its sources under `src/jvmMain/...` (MPP-style layout).
// With the `kotlin-jvm` plugin, we need to map that into the standard `main` source set.
kotlin {
    sourceSets {
        val main by getting {
            kotlin.srcDir("src/jvmMain/kotlin")
            resources.srcDir("src/jvmMain/resources")
        }
        val test by getting {
            kotlin.srcDir("src/jvmTest/kotlin")
            resources.srcDir("src/jvmTest/resources")
        }
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material) // for material2 components if referenced
    implementation(compose.material3)
    implementation(compose.materialIconsExtended) // Icons.Default.Image/Mic/VolumeUp/...
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
    
    // Networking
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.9.3")
    implementation("com.squareup.okhttp3:logging-interceptor:4.9.3")
    
    // Gson pour la sérialisation
    implementation("com.google.code.gson:gson:2.10.1")
}

compose.desktop {
    application {
        mainClass = "com.axonys.ai.desktop.MainKt"
        // JVM options for local runs and packaged distributions.
        // (Compose plugin 1.4.x doesn't support the newer `jpackage { imageOptions ... }` DSL.)
        jvmArgs += listOf(
            "-Xmx512M",
            "-Dfile.encoding=UTF-8"
        )
        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi
            )
            packageVersion = "1.0.0"
            packageName = "AxonysAI"
        }
    }
}
