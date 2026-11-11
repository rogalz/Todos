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

        verify {
            rule("Minimum line coverage") {
                // The floor is the total at the time it was set, rounded DOWN to a multiple of 5
                // (94.4 % -> 90), so normal churn does not trip it but a real drop does.
                // It is raised by hand when coverage has grown (it was 65 until the stage 5 UI tests took
                // the total from 67.4 % to 94.4 %); it is never lowered to make a build pass.
                minBound(90)
            }
        }
    }
}
