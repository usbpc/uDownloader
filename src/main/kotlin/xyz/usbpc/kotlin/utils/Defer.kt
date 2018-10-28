package xyz.usbpc.kotlin.utils

import kotlinx.coroutines.experimental.CoroutineScope
import kotlinx.coroutines.experimental.launch
import java.lang.Exception

class DeferHandler {
    private val actions = mutableListOf<() -> Unit>()

    fun defer(f: () -> Unit) {
        actions.add(f)
    }

    fun done() {
        for (action in actions.reversed()) {
            action()
        }
    }
}

fun <T> withDefer(block: DeferHandler.() -> T) : T {
    val handler = DeferHandler()
    var ex : Exception? = null
    var ret : T? = null
    try {
        ret = handler.block()
    } catch (e: Exception) {
        ex = e
    }
    handler.done()
    if (ex != null) {
        throw ex
    } else {
        return ret!!
    }
}

fun CoroutineScope.launchWithDefer(block: DeferHandler.() -> Unit) = launch {
    withDefer(block)
}