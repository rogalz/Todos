package com.example.ai.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The shared-element modifiers a screen applies to the parts that travel between the list and the
 * detail screen. It is a parameter (default: nothing) instead of reading composition locals, so a
 * screen renders and is tested without a NavHost, and it is obvious which parts take part.
 *
 * The functions are `@Composable` because the real implementation has to `remember` a shared content
 * state for the key; they return a plain [Modifier] to chain.
 */
interface SharedElements {
    /** The whole card / FAB on the list, which morphs into the whole detail screen. */
    @Composable
    fun container(key: String): Modifier

    /** The title text, which stays visibly "the same text" while the container morphs around it. */
    @Composable
    fun title(key: String): Modifier
}

/** No shared elements: previews, tests, and the fallback when a target does not animate them well. */
object NoSharedElements : SharedElements {
    @Composable
    override fun container(key: String): Modifier = Modifier

    @Composable
    override fun title(key: String): Modifier = Modifier
}

/** Keys that pair an element on the list with its counterpart on the detail screen. */
object SharedKeys {
    /** `todoId == null` is the "new task" flow: the FAB is the origin, there is no card yet. */
    fun container(todoId: String?): String = "todo-container-${todoId ?: NEW}"

    fun title(todoId: String?): String = "todo-title-${todoId ?: NEW}"

    private const val NEW = "new"
}
