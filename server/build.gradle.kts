plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktor)
}

kotlin {
    // Matches the JRE in the Dockerfile.
    jvmToolchain(21)
}

application {
    mainClass.set("com.example.ai.server.MainKt")
}

ktor {
    fatJar {
        // Stable name so the Dockerfile does not depend on the project version.
        archiveFileName.set("server.jar")
    }
}

dependencies {
    implementation(project(":api"))

    implementation(platform(libs.ktor.bom))
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    implementation(libs.ktor.serverContentNegotiation)
    implementation(libs.ktor.serverCallLogging)
    implementation(libs.ktor.serializationKotlinxJson)
    // Declared explicitly so Gradle resolves the catalog version and not the older one Ktor asks for.
    implementation(libs.kotlinx.serializationJson)
    implementation(libs.kotlinx.coroutinesCore)
    runtimeOnly(libs.logback.classic)

    testImplementation(platform(libs.ktor.bom))
    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.kotlin.testJunit)
}
