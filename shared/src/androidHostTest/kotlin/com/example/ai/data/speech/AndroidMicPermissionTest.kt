package com.example.ai.data.speech

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The permission flow with a plain counter standing in for the activity's launcher. */
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidMicPermissionTest {

    private var granted = false
    private var dialogsOpened = 0

    private val permission = AndroidMicPermission { granted }

    private fun attachActivity() = permission.attach { dialogsOpened++ }

    @Test
    fun whatTheSystemSaysIsWhatIsGranted() {
        assertFalse(permission.isGranted())
        granted = true
        assertTrue(permission.isGranted())
    }

    @Test
    fun anAlreadyGrantedPermissionOpensNoDialog() = runTest {
        granted = true
        attachActivity()

        assertTrue(permission.request())
        assertEquals(0, dialogsOpened)
    }

    @Test
    fun withoutAnActivityARequestIsARefusal() = runTest {
        assertFalse(permission.request())
        assertEquals(0, dialogsOpened)
    }

    @Test
    fun theUsersYesIsTheAnswer() = runTest {
        attachActivity()

        val answer = async { permission.request() }
        runCurrent()
        assertEquals(1, dialogsOpened)
        permission.onResult(true)

        assertTrue(answer.await())
    }

    @Test
    fun theUsersNoIsTheAnswer() = runTest {
        attachActivity()

        val answer = async { permission.request() }
        runCurrent()
        permission.onResult(false)

        assertFalse(answer.await())
    }

    @Test
    fun aSecondRequestWhileTheDialogIsOpenSharesItsAnswer() = runTest {
        attachActivity()

        val first = async { permission.request() }
        runCurrent()
        val second = async { permission.request() }
        runCurrent()
        permission.onResult(true)

        assertTrue(first.await())
        assertTrue(second.await())
        assertEquals(1, dialogsOpened, "one dialog for both")
    }

    @Test
    fun anAnswerThatNobodyIsWaitingForIsIgnored() {
        permission.onResult(true) // must not throw
    }

    @Test
    fun aCancelledRequestDoesNotBlockTheNextOne() = runTest {
        attachActivity()
        val cancelled = async { permission.request() }
        runCurrent()
        cancelled.cancel()
        runCurrent()
        permission.onResult(true) // the dialog's late answer to the cancelled request

        val next = async { permission.request() }
        runCurrent()
        assertEquals(2, dialogsOpened, "the next tap asks again")
        permission.onResult(false)

        assertFalse(next.await())
    }

    @Test
    fun aRotationWhileTheDialogIsOpenStillDeliversTheAnswer() = runTest {
        attachActivity()
        val answer = async { permission.request() }
        runCurrent()

        permission.detach() // the old activity goes away...
        attachActivity() // ...the new one comes up
        permission.onResult(true) // and receives the answer through its own launcher

        assertTrue(answer.await())
        assertEquals(1, dialogsOpened)
    }
}
