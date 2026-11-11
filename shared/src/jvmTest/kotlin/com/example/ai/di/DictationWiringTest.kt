package com.example.ai.di

import com.example.ai.data.local.AppDatabase
import com.example.ai.domain.TodoDraft
import com.example.ai.domain.TodoDraftParser
import com.example.ai.testing.FakeTodoDraftParser
import com.example.ai.testing.MainDispatcherTest
import com.example.ai.testing.fakePlatformModule
import com.example.ai.ui.detail.Dictation
import com.example.ai.ui.detail.TodoDetailViewModel
import com.example.ai.ui.list.VoiceInputViewModel
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The voice pieces of the real [appModule] graph. Like `KoinGraphTest`, but the AI is replaced: a
 * dictated draft asks it for a draft the moment the ViewModel exists, and a test must not reach a server.
 */
class DictationWiringTest : MainDispatcherTest() {

    private val parser = FakeTodoDraftParser { TodoDraft("Buy milk", "2 l") }

    private val app = koinApplication {
        modules(fakePlatformModule(), appModule, module { single<TodoDraftParser> { parser } })
    }
    private val koin get() = app.koin

    @AfterTest
    fun tearDown() {
        koin.getOrNull<AppDatabase>()?.close()
        koin.getOrNull<HttpClient>()?.close()
        koin.getOrNull<CoroutineScope>(AppScope)?.cancel()
        app.close()
    }

    /**
     * Koin matches parameters by type: `parametersOf(null, Dictation(..))` must give a *new todo* (the null
     * id) that is a *draft* (the dictation). Read as two strings it would have opened an existing todo.
     */
    @Test
    fun aDictationParameterOpensANewTodoAsADraft() {
        val viewModel = koin.get<TodoDetailViewModel> { parametersOf(null, Dictation("uh, buy milk")) }

        val state = viewModel.uiState.value
        assertTrue(state.isNew)
        assertTrue(state.isDraft)
        assertFalse(state.canDelete)
        assertEquals(listOf("uh, buy milk"), parser.parsed)
        assertEquals("Buy milk", viewModel.titleState.text.toString())
    }

    @Test
    fun withoutADictationTheViewModelIsNotADraftAndNeverAsksTheAi() {
        val viewModel = koin.get<TodoDetailViewModel> { parametersOf(null) }

        assertFalse(viewModel.uiState.value.isDraft)
        assertEquals(emptyList(), parser.parsed)
    }

    @Test
    fun theVoiceInputViewModelResolvesAndStartsIdle() {
        val viewModel = koin.get<VoiceInputViewModel>()

        assertTrue(viewModel.uiState.value.micEnabled)
    }

    @Test
    fun theDefaultParserIsTheOneBackedByTheServerApi() {
        val real = koinApplication { modules(fakePlatformModule(), appModule) }
        try {
            assertEquals("ApiTodoDraftParser", real.koin.get<TodoDraftParser>()::class.simpleName)
        } finally {
            real.koin.getOrNull<AppDatabase>()?.close()
            real.koin.getOrNull<HttpClient>()?.close()
            real.koin.getOrNull<CoroutineScope>(AppScope)?.cancel()
            real.close()
        }
    }
}
