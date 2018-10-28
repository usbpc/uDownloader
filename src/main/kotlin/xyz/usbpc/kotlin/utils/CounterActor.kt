package xyz.usbpc.kotlin.utils

import kotlinx.coroutines.experimental.CompletableDeferred
import kotlinx.coroutines.experimental.CoroutineScope
import kotlinx.coroutines.experimental.channels.SendChannel
import kotlinx.coroutines.experimental.channels.actor

fun CoroutineScope.createCounterActor() = CounterActor.create(this)

class CounterActor private constructor(private val channel: SendChannel<CounterMessage>) {
    companion object {
        fun create(scope: CoroutineScope) = CounterActor(scope.startCounter())

        private fun CoroutineScope.startCounter() = actor<CounterMessage> {
            for (msg in channel) {
                var counter = 0
                val waiting = mutableListOf<CompletableDeferred<Unit>>()
                when(msg) {
                    is IncCounter -> counter++
                    is DecCounter -> {
                        if (--counter == 0) {
                            waiting.forEach {
                                it.complete(Unit)
                            }
                            waiting.clear()
                        }
                    }
                    is WaitForZero -> {
                        waiting.add(msg.response)
                    }
                }
            }
        }
    }

    suspend fun inc() = channel.send(IncCounter)
    suspend fun dec() = channel.send(DecCounter)
    suspend fun wait() {
        val ret = CompletableDeferred<Unit>()
        channel.send(WaitForZero(ret))
        ret.await()
    }
    fun close() = channel.close()

    private open class CounterMessage
    private object IncCounter : CounterMessage()
    private object DecCounter : CounterMessage()
    private class WaitForZero(val response: CompletableDeferred<Unit>) : CounterMessage()
}