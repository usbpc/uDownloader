package xyz.usbpc.uDownloader.main

import kotlinx.coroutines.experimental.CoroutineScope
import kotlinx.coroutines.experimental.Dispatchers
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.channels.ReceiveChannel
import kotlinx.coroutines.experimental.channels.SendChannel
import kotlinx.coroutines.experimental.launch
import xyz.usbpc.kotlin.utils.CounterActor
import xyz.usbpc.kotlin.utils.WithCount
import xyz.usbpc.kotlin.utils.createCounterActor
import xyz.usbpc.kotlin.utils.getOneFrom
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
        returns = Channel()
        checkerChannel = Channel()
        needsDownload = Channel()

        checkerQueueManager = checkerQueueManager(counter, checkerChannel, ingress, returns)
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
            channels: ReceiveChannel<ReceiveChannel<OneFichierFile>>, /* <-- this will close at some point*/
            returns: ReceiveChannel<WithCount<OneFichierFile>>
    ) = launch {
        var currentReturn = getOneFrom(returns)
        for (channel in channels) {
            while (!channel.isClosedForReceive) {
                if (!currentReturn.isCompleted) {
                    toCheckers.send(WithCount(channel.receive()))
                    counter.inc()
                } else {
                    currentReturn.getCompleted().let {item ->
                        currentReturn = getOneFrom(returns)
                        item.count++
                        toCheckers.send(item)
                    }
                }
            }
        }
        currentReturn.await().let { retry ->
            retry.count++
            toCheckers.send(retry)
        }
        for (retry in returns) {
            retry.count++
            toCheckers.send(retry)
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
        return FileStatus.DONE
    }

    private enum class FileStatus {
        DONE, RETRY, DOWNLOAD
    }
}