plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.androidxRoom) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.ktor) apply false
    // Applied here (not `apply false`): the root project produces the one aggregated coverage report.
    alias(libs.plugins.kover)
}

dependencies {
    kover(project(":shared"))
    kover(project(":api"))
    kover(project(":server"))
}

kover {
    reports {
        filters {
            excludes {
                // Generated code only; hand-written code is never excluded to raise the number.
                classes(
                    // Room
                    "*_Impl",
                    "*_Impl\$*",
                    "*AppDatabaseConstructor*",
                    // Compose compiler
                    "*ComposableSingletons*",
                    // Compose Resources accessors (`Res`, string/resource collectors)
                    "*.generated.resources.*",
                    // kotlinx.serialization
                    "*\$\$serializer",
                )
                annotatedBy(
                    "androidx.compose.ui.tooling.preview.Preview",
                    "org.jetbrains.compose.ui.tooling.preview.Preview",
                )
            }
        }
    }
}
