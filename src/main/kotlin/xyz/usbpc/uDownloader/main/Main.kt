package xyz.usbpc.uDownloader.main

import com.xenomachina.argparser.ArgParser
import com.xenomachina.argparser.ShowHelpException
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.channels.ReceiveChannel
import okhttp3.OkHttpClient
import xyz.usbpc.uDownloader.hosts.OneFichierFile
import xyz.usbpc.uDownloader.hosts.OneFichierManager
import java.io.OutputStreamWriter
import kotlin.coroutines.experimental.CoroutineContext

fun main(args: Array<String>) = runBlocking {
    uDownloader().run(args)
}

class uDownloader() : CoroutineScope {
    private val job = Job()
    private val okHttpClient = OkHttpClient()
    override val coroutineContext: CoroutineContext = job + Dispatchers.Default

    fun run(args: Array<String>) {
        val parsedArgs : ArgumentParser
        try {
            parsedArgs = ArgParser(args).parseInto(::ArgumentParser)
        } catch (e: ShowHelpException) {
            val writer = OutputStreamWriter(System.out)
            e.printUserMessage(writer, "uDownloader", 100)
            writer.flush()
            System.exit(e.returnCode)
            return
        }

        val fileChannel = Channel<ConfigLine>()
        configWorker(parsedArgs.config, fileChannel)
        val checkersChannel = Channel<ReceiveChannel<OneFichierFile>>()
        val fileDownloadersCh = Channel<ReceiveChannel<OneFichierFile>>()
        val checkersDownloadersCh = Channel<OneFichierFile>()

        val oneFichierManager = OneFichierManager(okHttpClient, parsedArgs.username, parsedArgs.password)

        val folderWorker = folderWorker(oneFichierManager, fileChannel, checkersChannel, fileDownloadersCh)

        val checkerManager = CheckerManager(job, 1, checkersChannel, checkersDownloadersCh)
        checkerManager.start()

        val downloaderManager = DownloadManager(job, parsedArgs.threads, fileDownloadersCh, checkersDownloadersCh)
        downloaderManager.start()

        runBlocking {
            folderWorker.join()
            println("INFO: Folder worker done")
            checkerManager.join()
            println("INTO: Checker manager done")
            checkerManager.cancel()


            downloaderManager.join()
            downloaderManager.cancel()
            println("INFO: Downloader manager done")
        }
    }
}