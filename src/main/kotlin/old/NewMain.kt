package old

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.*

fun main(args: Array<String>) = runBlocking {
    val retryChannel = Channel<Unit>()
    val ticker = ticker(1000L*10, 0, mode = TickerMode.FIXED_DELAY)

}

data class WithRetryCounter<T>(val item: T, var counter: Int)

fun CoroutineScope.downloadManager(
        files: ReceiveChannel<FichierFile>,
        toDownloaders: SendChannel<WithRetryCounter<FichierFile>>,
        fromDownloaders: ReceiveChannel<WithRetryCounter<FichierFile>>
) = launch {
    var catchReturns = async {
        fromDownloaders.receive()
    }
    var toRetry : WithRetryCounter<FichierFile>? = null
    while (!files.isClosedForReceive || toRetry != null) {
        toDownloaders.send(toRetry ?: WithRetryCounter(files.receive(), 1))
        toRetry = null
        if (catchReturns.isCompleted) {
            toRetry = catchReturns.getCompleted()
            toRetry.counter++
            catchReturns = async {
                fromDownloaders.receive()
            }
        }
    }
    catchReturns.cancel()
}

fun CoroutineScope.downloader(
        fromManager: ReceiveChannel<WithRetryCounter<FichierFile>>,
        toManager: SendChannel<WithRetryCounter<FichierFile>>
) = launch {
    for (file in fromManager) {
        println("Try: ${file.counter} downloading \"${file.item.name}\"")
        
    }
}