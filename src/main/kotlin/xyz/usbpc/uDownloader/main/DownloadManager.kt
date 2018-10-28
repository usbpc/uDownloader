package xyz.usbpc.uDownloader.main

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.channels.ClosedReceiveChannelException
import kotlinx.coroutines.experimental.channels.ReceiveChannel
import kotlinx.coroutines.experimental.channels.SendChannel
import kotlinx.coroutines.experimental.selects.select
import xyz.usbpc.kotlin.utils.CounterActor
import xyz.usbpc.kotlin.utils.WithCount
import xyz.usbpc.kotlin.utils.createCounterActor
import xyz.usbpc.kotlin.utils.unpackChannel
import xyz.usbpc.uDownloader.hosts.OneFichierFile
import java.lang.Exception
import kotlin.coroutines.experimental.CoroutineContext

class DownloadManager(parent: Job, val num: Int = 2,
                      val fromFolder: ReceiveChannel<ReceiveChannel<OneFichierFile>>,
                      val fromCheckers: ReceiveChannel<OneFichierFile>
) : CoroutineScope {
    private val job = Job(parent)

    override val coroutineContext: CoroutineContext
        get() = job + Dispatchers.Default

    private lateinit var combinedIngress: Channel<OneFichierFile>
    private lateinit var returns: Channel<WithCount<OneFichierFile>>
    private lateinit var toDownloaders: Channel<WithCount<OneFichierFile>>
    private lateinit var interveaver : Job
    private lateinit var manager: Job
    private lateinit var downloaders : List<Job>
    private val counter = createCounterActor()

    fun start() {
        combinedIngress = Channel()
        returns = Channel(1)
        toDownloaders = Channel()

        interveaver = channelInterveaver(listOf(fromCheckers, unpackChannel(fromFolder)), combinedIngress)

        manager = downloaderManager(counter, combinedIngress, toDownloaders, returns)

        downloaders = List(num) {
            downloader(counter, toDownloaders, returns)
        }
        println("INFO: Downloader manager started up")
    }

    suspend fun join() {
        interveaver.join()
        println("INFO: Download ingress interveaver finished")

        counter.wait()
        counter.close()
        println("INFO: All downloads finished")

        returns.close()

        manager.join()
        println("INFO: Download manager finished")

        downloaders.forEach { it.join() }
        println("INFO: All downloaders stopped")
    }

    fun cancel() = job.cancel()

    private fun channelInterveaver(
            inputs: List<ReceiveChannel<OneFichierFile>>,
            output: SendChannel<OneFichierFile>
    ) = launch {
        var inputs = inputs
        while (isActive && inputs.isNotEmpty()) {
            try {
                select<OneFichierFile> {
                    inputs.forEach { channel ->
                        channel.onReceive { it }
                    }
                }.let { file ->
                    output.send(file)
                }
            } catch (e: ClosedReceiveChannelException) {
                inputs = inputs.filterNot { it.isClosedForReceive }
                println("INFO: A channel was closed!")
            }
        }
        output.close()
    }

    private fun downloaderManager(
            counter: CounterActor,
            ingress: ReceiveChannel<OneFichierFile>,
            toDownloaders: SendChannel<WithCount<OneFichierFile>>,
            returns: ReceiveChannel<WithCount<OneFichierFile>>
    ) = launch {
        while (isActive && (!ingress.isClosedForReceive || !returns.isClosedForReceive)) {
            select<WithCount<OneFichierFile>> {
                if (!returns.isClosedForReceive) {
                    returns.onReceive {
                        it.count++
                        it
                    }
                }
                if (!ingress.isClosedForReceive) {
                    ingress.onReceive {
                        counter.inc()
                        WithCount(it)
                    }
                }
            }.let { current ->
                if (current.count <= 50) {
                    toDownloaders.send(current)
                } else {
                    println("DROPPED: Downloading \"${current.item}\" failed, too many retries!")
                    counter.dec()
                }
            }
        }
    }

    private fun downloader(
            counter: CounterActor,
            toDownload: ReceiveChannel<WithCount<OneFichierFile>>,
            returns: SendChannel<WithCount<OneFichierFile>>
    ) = launch {
        for (file in toDownload) {
            try {
                println("INFO: Starting download of \"${file.item.name}\" (try: ${file.count})")
                file.item.download()
                counter.dec()
                println("DONE: Downloading of \"${file.item.name}\" finished successfully")
            } catch (e: Exception) {
                println("ERROR: Got exception while downloading \"${file.item.name}\":")
                e.printStackTrace(System.out)
                returns.send(file)
            }
        }
    }
}