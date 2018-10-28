package xyz.usbpc.uDownloader.main

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.Channel
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

class CheckerManager(parent: Job, val num: Int = 1,
                     val ingress: ReceiveChannel<ReceiveChannel<OneFichierFile>>,
                     val egress: SendChannel<OneFichierFile>
) : CoroutineScope {
    private val job = Job(parent)

    override val coroutineContext: CoroutineContext
        get() = job + Dispatchers.Default

    private val counter = createCounterActor()
    lateinit var checkerQueueManager : Job
    lateinit var returns : Channel<WithCount<OneFichierFile>>
    lateinit var checkerChannel: Channel<WithCount<OneFichierFile>>
    lateinit var needsDownload: Channel<OneFichierFile>
    lateinit var checkers : List<Job>
    lateinit var aggregateSender : Job

    fun start() {
        println("INFO: Starting up checkers")
        returns = Channel(1)
        checkerChannel = Channel()
        needsDownload = Channel()

        checkerQueueManager = checkerQueueManager(counter, checkerChannel, unpackChannel(ingress), returns)
        checkers = List(num) {
            checker(counter, checkerChannel, returns, needsDownload)
        }
        aggregateSender  = aggregateSender(needsDownload, egress)
        println("INFO: Checkers have been started")
    }

    suspend fun join() {
        counter.wait()
        counter.close()
        println("INFO: All files have been checked")

        checkers.forEach { it.join() }
        println("INFO: All checkers are shut down")

        needsDownload.close()
        println("INFO: Closed needsDownload channel")

        returns.close()
        println("INFO: Returns channel for checkers is shut down")

        checkerQueueManager.join()
        println("INFO: Checker Queue Manager is shut down")

        aggregateSender.join()
        println("INFO: All files from the checkers have been send to the downloaders")
    }

    fun cancel() = job.cancel()

    /**
     * Terminates if `channels` and `returns` are closed
     */
    private fun checkerQueueManager(
            counter: CounterActor,
            toCheckers: SendChannel<WithCount<OneFichierFile>>,
            ingress: ReceiveChannel<OneFichierFile>, /* <-- this will close at some point*/
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
                    toCheckers.send(current)
                } else {
                    counter.dec()
                    println("DROPPED: Checking \"${current.item}\" failed, too many retries!")
                }
            }
        }
        toCheckers.close()
    }

    /**
     * Does the actual checking work and terminates when `input` channel is closed
     */
    private fun checker(
            counter: CounterActor,
            input: ReceiveChannel<WithCount<OneFichierFile>>,
            returns: SendChannel<WithCount<OneFichierFile>>,
            needsDownload: SendChannel<OneFichierFile>) = launch {
        for (element in input) {
            when(checkFile(element)) {
                CheckerManager.FileStatus.RETRY -> returns.send(element)
                CheckerManager.FileStatus.DOWNLOAD -> needsDownload.send(element.item)
                CheckerManager.FileStatus.DONE -> counter.dec()
            }
        }
    }

    /**
     * Checking logic implemented here
     */
    private suspend fun checkFile(file: WithCount<OneFichierFile>) : FileStatus {
        println("CHECKING: \"${file.item.name}\" (Try: ${file.count})")
        try {
            if (file.item.getFilesize() != file.item.file.length()) {
                println("DELETING: \"${file.item.name}\" (size didn't match remote)")
                file.item.file.delete()
                return FileStatus.DOWNLOAD
            }
        } catch (e: Exception) {
            println("ERROR: Checking \"${file.item.name}\" failed:")
            e.printStackTrace(System.out)
            return FileStatus.RETRY
        }
        println("DONE: Checking of \"${file.item.name}\" finished successfully")
        return FileStatus.DONE
    }

    private enum class FileStatus {
        DONE, RETRY, DOWNLOAD
    }
}