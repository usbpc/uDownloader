package xyz.usbpc.kotlin.utils

import kotlinx.coroutines.experimental.CoroutineScope
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.channels.ReceiveChannel
import kotlinx.coroutines.experimental.channels.SendChannel
import kotlinx.coroutines.experimental.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import xyz.usbpc.uDownloader.hosts.OneFichierFile
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
        sendChannel.send(item)
    }
    sendChannel.close()
}

data class WithCount<T>(val item: T, var count: Int = 1)

fun <T> CoroutineScope.unpackChannel(
        input: ReceiveChannel<ReceiveChannel<T>>
) : ReceiveChannel<T> {
    val ret = Channel<T>()
    launch {
        for (channel in input) {
            for (file in channel) {
                ret.send(file)
            }
        }
        ret.close()
    }
    return ret
}