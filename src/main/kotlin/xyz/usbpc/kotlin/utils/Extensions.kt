package xyz.usbpc.kotlin.utils

import kotlinx.coroutines.experimental.CoroutineScope
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.channels.ReceiveChannel
import kotlinx.coroutines.experimental.channels.SendChannel
import kotlinx.coroutines.experimental.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import java.lang.Exception
import kotlin.coroutines.experimental.suspendCoroutine

suspend fun Call.await() = suspendCoroutine<Response> { cont ->
    val callback = object: Callback {
        override fun onFailure(call: Call, e: IOException) {
            cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }
    }
    this.enqueue(callback)
}

fun <E> CoroutineScope.sendListThenClose(list: List<E>, sendChannel: SendChannel<E>) = launch {
    for (item in list) {
        try {
            sendChannel.send(item)
        } catch (e: Exception) {
            println("COUGHT THE FUCKING EXCEPTION")
            throw e
        }
    }
    sendChannel.close()
}

data class WithCount<T>(val item: T, var count: Int = 1)

fun <E> CoroutineScope.getOneFrom(channel: ReceiveChannel<E>) = async {
    channel.receive()
}