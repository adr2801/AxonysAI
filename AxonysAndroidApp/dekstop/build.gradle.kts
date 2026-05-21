plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3) // Design moderne identique à ton app mobile
    
    // Coroutines pour ne pas bloquer l'IHM pendant que l'agent Antigravity réfléchit
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
}

compose.desktop {
    application {
        mainClass = "com.axonys.ai.desktop.MainKt"
        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb)
            packageVersion = "1.0.0"
        }
    }
}