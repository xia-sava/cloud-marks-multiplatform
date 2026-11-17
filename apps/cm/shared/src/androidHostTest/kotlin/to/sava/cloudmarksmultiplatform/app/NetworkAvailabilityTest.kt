package to.sava.cloudmarksmultiplatform.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** [callbackNotifications] に渡す、登録の様子を記録する登録の関数。 */
private class FakeRegistration {
    /** 登録しているコールバックの、知らせる関数。登録していなければ null。 */
    var notify: (() -> Unit)? = null
        private set

    var registrations = 0
        private set

    var unregistrations = 0
        private set

    val register: (() -> Unit) -> () -> Unit = { notify ->
        registrations++
        this.notify = notify
        this::unregister
    }

    private fun unregister() {
        unregistrations++
        notify = null
    }
}

// runCurrent が ExperimentalCoroutinesApi であるため。
@OptIn(ExperimentalCoroutinesApi::class)
class NetworkAvailabilityTest {
    // 集め始めると登録し、コールバックが呼ばれるたびに知らせる。集めるのをやめると登録を外す
    @Test
    fun registersWhileCollectingAndNotifiesOnEachCall() = runTest {
        val registration = FakeRegistration()
        val notifications = callbackNotifications(registration.register)
        assertEquals(0, registration.registrations)
        var received = 0

        val collecting = backgroundScope.launch { notifications.collect { received++ } }
        runCurrent()
        val notify = assertNotNull(registration.notify)
        assertEquals(1, registration.registrations)

        notify()
        runCurrent()
        notify()
        runCurrent()
        assertEquals(2, received)

        collecting.cancel()
        runCurrent()
        assertEquals(1, registration.unregistrations)
        assertNull(registration.notify)
    }

    // 集めている側が前の知らせを扱っているあいだに来た知らせは、1 つにまとめる
    @Test
    fun notificationsWhileHandlingAreMerged() = runTest {
        val registration = FakeRegistration()
        val handled = CompletableDeferred<Unit>()
        var received = 0
        backgroundScope.launch {
            callbackNotifications(registration.register).collect {
                received++
                handled.await()
            }
        }
        runCurrent()
        val notify = assertNotNull(registration.notify)
        notify()
        runCurrent()
        assertEquals(1, received)

        notify()
        notify()
        notify()
        handled.complete(Unit)
        runCurrent()

        assertEquals(2, received)
    }
}
