package com.example.ai.testing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.example.ai.data.NoMicPermissionNeeded
import com.example.ai.data.NoSpeechToText
import com.example.ai.di.viewModelModule
import com.example.ai.domain.MicPermission
import com.example.ai.domain.SpeechToText
import com.example.ai.domain.SyncStatusRepository
import com.example.ai.domain.TodoDraft
import com.example.ai.domain.TodoDraftParser
import com.example.ai.domain.TodoRepository
import com.example.ai.ui.theme.AppTheme
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.time.Clock

/**
 * The app's real UI (theme, `AppNavHost`, the real ViewModels from [viewModelModule]) over fakes.
 *
 * Koin is *isolated* (not the global one) so tests cannot leak into each other. Only what sits below the
 * ViewModels is replaced: the repositories, the clock, and the voice-input pieces (speech recognizer,
 * microphone permission, draft parser). By default there is no speech (typed dialog) and an AI that is
 * unreachable, so tests that do not care about voice are unaffected.
 */
@Composable
fun FakeAppEnvironment(
    repository: TodoRepository,
    syncStatus: SyncStatusRepository = FakeSyncStatusRepository(),
    clock: Clock = FakeClock(),
    speechToText: SpeechToText = NoSpeechToText,
    micPermission: MicPermission = NoMicPermissionNeeded,
    draftParser: TodoDraftParser = UnreachableDraftParser,
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    val koin = remember {
        koinApplication {
            modules(
                viewModelModule,
                module {
                    single<TodoRepository> { repository }
                    single<SyncStatusRepository> { syncStatus }
                    single<Clock> { clock }
                    single<SpeechToText> { speechToText }
                    single<MicPermission> { micPermission }
                    single<TodoDraftParser> { draftParser }
                },
            )
        }
    }
    KoinIsolatedContext(koin) {
        AppTheme(darkTheme = darkTheme, content = content)
    }
}

/** The default AI of the UI tests: always offline, like a phone without a server. */
private object UnreachableDraftParser : TodoDraftParser {
    override suspend fun parse(text: String): TodoDraft = error("the AI is unreachable")
}
