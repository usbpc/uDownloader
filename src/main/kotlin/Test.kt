import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.ReceiveChannel
import kotlinx.coroutines.experimental.channels.SendChannel
import okhttp3.OkHttpClient
import okhttp3.Request
import java.lang.Exception
import java.lang.IllegalStateException
import kotlin.coroutines.experimental.CoroutineContext

fun CoroutineScope.rateLimiter(tokens: SendChannel<Unit>, rate: Int) = launch {
    var lastToken = System.currentTimeMillis()
    while (isActive) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastToken < rate) {
            delay(currentTime - lastToken)
        }
        tokens.send(Unit)
    }
}

fun CoroutineScope.request(tokens: ReceiveChannel<Unit>) = launch {
    for (token in tokens) {
        //Do Web request
    }
}

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


inline fun <E> retry(int: Int, block: () -> E) : E {
    var ex: Exception? = null
    repeat(int) {
        try {
            return block()
        } catch (e: Exception) {
            ex = e
            println(e.localizedMessage)
        }
    }
    throw IllegalStateException("Too many retries", ex!!)
}