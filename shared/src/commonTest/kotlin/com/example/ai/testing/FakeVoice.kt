package com.example.ai.testing

import com.example.ai.domain.MicPermission
import com.example.ai.domain.SpeechEvent
import com.example.ai.domain.SpeechFailure
import com.example.ai.domain.SpeechRecognitionException
import com.example.ai.domain.SpeechToText
import com.example.ai.domain.TodoDraft
import com.example.ai.domain.TodoDraftParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A speech recognizer the test drives by hand: [partial], [final] and [fail] play what a real one would
 * deliver to the running [listen]. Like the real one, [stop] delivers the final result with what was
 * heard so far.
 */
class FakeSpeechToText(override val isSupported: Boolean = true) : SpeechToText {

    private var channel: Channel<Any>? = null
    private var lastPartial = ""

    var listenCount = 0
        private set
    var stopCount = 0
        private set

    /** How many listening sessions were cancelled by their collector (the user pressed Cancel, or the screen left). */
    var cancelledCount = 0
        private set

    val isListening: Boolean get() = channel != null

    override fun listen(): Flow<SpeechEvent> = flow {
        listenCount++
        val events = Channel<Any>(Channel.UNLIMITED)
        channel = events
        lastPartial = ""
        try {
            for (item in events) {
                if (item is Throwable) throw item
                emit(item as SpeechEvent)
            }
        } catch (e: CancellationException) {
            cancelledCount++
            throw e
        } finally {
            channel = null
        }
    }

    override fun stop() {
        stopCount++
        channel?.let {
            it.trySend(SpeechEvent.Final(lastPartial))
            it.close()
        }
    }

    fun partial(text: String) {
        lastPartial = text
        checkNotNull(channel) { "not listening" }.trySend(SpeechEvent.Partial(text))
    }

    fun final(text: String) {
        val events = checkNotNull(channel) { "not listening" }
        events.trySend(SpeechEvent.Final(text))
        events.close()
    }

    fun fail(reason: SpeechFailure) = failWith(SpeechRecognitionException(reason))

    fun failWith(error: Throwable) {
        val events = checkNotNull(channel) { "not listening" }
        events.trySend(error)
        events.close()
    }
}

class FakeMicPermission(var granted: Boolean = true, private val userGrants: Boolean = true) : MicPermission {
    var requests = 0
        private set

    override fun isGranted(): Boolean = granted

    override suspend fun request(): Boolean {
        requests++
        if (userGrants) granted = true
        return granted
    }
}

/** What the AI answers is decided per test: a draft, a failure, or a wait on a gate. */
class FakeTodoDraftParser(var onParse: suspend (String) -> TodoDraft = { TodoDraft(it, "") }) : TodoDraftParser {
    val parsed = mutableListOf<String>()

    override suspend fun parse(text: String): TodoDraft {
        parsed += text
        return onParse(text)
    }
}
