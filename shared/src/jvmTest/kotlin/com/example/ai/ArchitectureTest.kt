package com.example.ai

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.verify.assertFalse
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Layer rules from CLAUDE.md ("Architecture"), checked on the source files of the `shared` module.
 * They are import-based on purpose: simple, fast, and the failure names the offending file.
 */
class ArchitectureTest {

    // Test source sets are excluded; platform source sets (androidMain, iosMain, ...) are included.
    private val production = Konsist.scopeFromProduction(moduleName = "shared")

    private fun KoFileDeclaration.importsFrom(vararg packages: String) =
        imports.any { import -> packages.any { pkg -> import.name == pkg || import.name.startsWith("$pkg.") } }

    @Test
    fun domainDoesNotDependOnRoomKtorOrCompose() {
        val domainFiles = production.files.filter { file ->
            file.packagee?.name.let { it == DOMAIN_PACKAGE || it?.startsWith("$DOMAIN_PACKAGE.") == true }
        }
        // Guards against the rule passing vacuously after a package rename or a wrong scope.
        assertTrue(domainFiles.isNotEmpty(), "no files found in $DOMAIN_PACKAGE; is the rule still looking at the right place?")

        domainFiles.assertFalse(additionalMessage = "domain must stay free of persistence, network and UI frameworks") {
            it.importsFrom("androidx.room", "io.ktor", "androidx.compose", "org.jetbrains.compose")
        }
    }

    @Test
    fun viewModelsDoNotDependOnTheDataPackage() {
        // Name-based and parent-based, so a ViewModel is caught even when it is misnamed.
        val viewModels = production.classes()
            .filter { it.name.endsWith("ViewModel") || it.hasParentWithName("ViewModel") }
        assertTrue(viewModels.isNotEmpty(), "no ViewModels found; is the rule still looking at the right place?")

        // The check is on the file's imports: the ViewModels live in ui.*, and Kotlin cannot reach
        // another package without importing from it.
        viewModels.assertFalse(additionalMessage = "ViewModels talk to domain interfaces, never to the data package") {
            it.containingFile.importsFrom(DATA_PACKAGE)
        }
    }

    @Test
    fun uiNeverTouchesTheNetworkOrTheDataLayer() {
        // CLAUDE.md: Room is the single source of truth and the UI only observes it; network code
        // never talks to the UI directly. Sync is invisible to the UI by construction.
        val uiFiles = production.files.filter { file ->
            file.packagee?.name.let { it == UI_PACKAGE || it?.startsWith("$UI_PACKAGE.") == true }
        }
        assertTrue(uiFiles.isNotEmpty(), "no files found in $UI_PACKAGE; is the rule still looking at the right place?")

        uiFiles.assertFalse(additionalMessage = "ui must not import the network or the data package") {
            it.importsFrom("io.ktor", DATA_PACKAGE)
        }
    }

    private companion object {
        const val BASE_PACKAGE = "com.example.ai"
        const val UI_PACKAGE = "$BASE_PACKAGE.ui"
        const val DOMAIN_PACKAGE = "$BASE_PACKAGE.domain"
        const val DATA_PACKAGE = "$BASE_PACKAGE.data"
    }
}
