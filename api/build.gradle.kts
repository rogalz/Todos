import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The client/server contract: @Serializable DTOs and route constants only. No Compose, Room or Ktor,
// so both `server` (JVM) and `shared` (Android, iOS, desktop; wired up in a later stage) can use it.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.kover)
}

kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()
    android {
        namespace = "com.example.ai.api"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        // Runs the contract tests on the Android target too, where the DTOs will actually be used.
        withHostTest {}
    }

    sourceSets {
        commonMain.dependencies {
            // `api`, not `implementation`: the DTOs' generated serializers expose KSerializer.
            api(libs.kotlinx.serializationCore)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.serializationJson)
        }
    }
}
