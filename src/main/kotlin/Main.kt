import com.xenomachina.argparser.ArgParser
import com.xenomachina.argparser.ShowHelpException
import com.xenomachina.argparser.default
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.Channel
import okhttp3.*
import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter
import java.lang.Exception
import java.text.DecimalFormat
import java.util.*
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.coroutines.experimental.CoroutineContext
import kotlin.coroutines.experimental.coroutineContext
import kotlin.coroutines.experimental.suspendCoroutine

class MyArgs(parser: ArgParser) {
    val username by parser.storing(
            "-u", "--username",
            help="username for 1fichier")

    val password by parser.storing(
            "-p", "--password",
            help = "1fichier Password"
    )

    val folder by parser.storing(
            "-o", "--output",
            help = "Base folder where files will be stored"
    ) {File(this)}.default{File(".")}

    val threads by parser.storing(
            "-t", "--threads",
            help = "How many downloads should be run in Parallel"
    ){this.toInt()}.default(4)

    val sleedlimit by parser.storing(
            "-l", "--limit",
            help = "Sleedlimit for all downloads combined (excludes some checking and initial connections) how many bytes per ms (8680 for google)"
    ){this.toLong()}.default(-1)

    val downloadPass by parser.storing(
            "--dlPw",
            help = "Password to download files in this folder"
    ).default<String?>(null)

    val url by parser.positional(
            "URL",
            help = "1fichier folder to download")
}

fun main(args: Array<String>) = runBlocking {
    val parsedArgs : MyArgs
    try {
        parsedArgs = ArgParser(args).parseInto(::MyArgs)
    } catch (e: ShowHelpException) {
        val writer = OutputStreamWriter(System.out)
        e.printUserMessage(writer, "uDownloader", 100)
        writer.flush()
        System.exit(e.returnCode)
        delay(1000L)
        return@runBlocking
    }

    Logger.getLogger(OkHttpClient::javaClass.name).level = Level.FINE
    val client = OkHttpClient().newBuilder().dns(DnsSelector(DnsSelector.Mode.IPV4_ONLY)).build()

    parsedArgs.folder.mkdirs()

    val existingFileNames = parsedArgs.folder.listFiles { thing -> thing.exists() && !thing.isHidden && thing.isFile }.map { it.name }

    val manager = OneFichierManager(client, parsedArgs.username, parsedArgs.password)
    manager.login()

    val exists = mutableListOf<FichierFile>()
    val files = mutableListOf<FichierFile>()
    manager.getFilesFromFolder(parsedArgs.url, parsedArgs.downloadPass)?.let { rawFiles ->
        for (file in rawFiles) {
            if (file.name in existingFileNames) {
                exists.add(file)
            } else {
                files.add(file)
            }
        }
    } ?: return@runBlocking

    val channel = Channel<FichierFile>()
    val sender = launch {
        files.intoChannel(channel)
    }

    val requestLimiter = RequestLimiter()
    val blockingContext = newFixedThreadPoolContext(parsedArgs.threads, "Just some throwaway threads")
    val downloaders = List(parsedArgs.threads) {
        launch {
            lunchDownloads(channel, requestLimiter, parsedArgs.folder, blockingContext, parsedArgs.downloadPass)
        }
    }
    val checker = launch {
        lunchChecker(parsedArgs.folder, requestLimiter, exists, channel)
    }
    checker.join()
    println("All preexisting files have been checked!")
    sender.join()
    println("All files have been send for downloading!")
    channel.close()

    for (downloader in downloaders) {
        downloader.join()
    }
    println("Done downloading everything!")
    blockingContext.close()
}

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

suspend fun lunchChecker(folder: File, requestLimiter: RequestLimiter, files: List<FichierFile>, channel: Channel<FichierFile>)  {
    val buffer = mutableListOf<FichierFile>()
    for (file in files) {
        if (buffer.isNotEmpty() && channel.offer(buffer[0])) {
            buffer.removeAt(0)
        }
        requestLimiter.blah()
        println("Checking if filesize of ${file.name} on disk matches 1fichier")
        val diskFile = File(folder, file.name)
        val size = file.getFilesize()
        if (size == diskFile.length()) {
            println("It does, skipping download...")
        } else {
            println("It dosen't, enqueueing for download...")
            diskFile.delete()
            if (!channel.offer(file)) {
                buffer.add(file)
            }
        }
    }
    coroutineScope {
        launch {
            buffer.intoChannel(channel)
        }.join()
    }
}



suspend fun <E> List<E>.intoChannel(channel: Channel<E>) {
    for (item in this) {
        channel.send(item)
    }
}



suspend fun lunchDownloads(channel: Channel<FichierFile>, requestLimiter: RequestLimiter, folder: File, blockingContext: CoroutineContext, dlPwd: String?) {
    for (file in channel) {
        requestLimiter.blah()
        println("Starting download of ${file.name}")
        file.download(File(folder, file.name), blockingContext)
        println("Done downloading ${file.name}")
    }
}

class RequestLimiter {
    var lastRequest = 0L
    suspend fun blah() {
        val current = System.currentTimeMillis()
        val toWait = 1000L * 10 - (current - lastRequest)
        if (toWait > 0) {
            lastRequest = current + toWait
            delay(toWait)
        } else {
            lastRequest = current
        }
    }
}

class TokenBucket(val capacity: Long, val rate: Long) {
    var current = capacity
    var lastFill = System.currentTimeMillis()
    var lastMessage = 0L

    private fun fillBucket() {
        val currentTime = System.currentTimeMillis()
        current += (currentTime - lastFill) * rate
        if (current > capacity) {
            current = capacity
        }
        lastFill = currentTime
    }

    suspend fun useTokens(chunkSize: Long) {
        if (chunkSize < 0 || rate < 0) return
        fillBucket()
        while (current < chunkSize) {
            if (lastFill - lastMessage < 1000L * 60) {
                lastMessage = lastFill
                println("Speedlimit was reached, slowing download...")
            }
            delay((chunkSize - current) / rate)
            fillBucket()
        }
        current -= chunkSize
    }
}