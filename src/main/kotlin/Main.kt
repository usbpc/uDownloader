import com.xenomachina.argparser.ArgParser
import com.xenomachina.argparser.default
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.Channel
import okhttp3.*
import java.io.File
import java.io.IOException
import java.lang.Exception
import java.util.*
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.coroutines.experimental.CoroutineContext
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
    val parsedArgs = ArgParser(args).parseInto(::MyArgs)
    Logger.getLogger(OkHttpClient::javaClass.name).level = Level.FINE
    val client = OkHttpClient()

    parsedArgs.folder.mkdirs()

    val limiter = TokenBucket(parsedArgs.sleedlimit*1000*60, parsedArgs.sleedlimit)

    val existingFileNames = parsedArgs.folder.listFiles { thing -> thing.exists() && !thing.isHidden && thing.isFile }.map { it.name }

    val manager = OneFichierManager(client, parsedArgs.username, parsedArgs.password)
    manager.login()

    val rawFiles = manager.getFilesFromFolder(parsedArgs.url) ?: return@runBlocking

    val exists = mutableListOf<FichierFile>()
    val files = mutableListOf<FichierFile>()
    for (file in rawFiles) {
        if (file.name in existingFileNames) {
            exists.add(file)
        } else {
            files.add(file)
        }
    }

    val channel = Channel<FichierFile>()
    val sender = files.intoChannel(channel, coroutineContext)

    val requestLimiter = RequestLimiter()
    val downloader = lunchDownloads(channel, parsedArgs.threads, limiter, requestLimiter, parsedArgs.folder, coroutineContext)
    val checker = lunchChecker(parsedArgs.folder, requestLimiter, coroutineContext, exists, channel)

    checker.join()
    println("All preexisting files have been checked!")
    sender.join()
    println("All files have been send for downloading!")
    channel.close()

    downloader.join()
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

fun lunchChecker(folder: File, requestLimiter: RequestLimiter, context: CoroutineContext, files: List<FichierFile>, channel: Channel<FichierFile>) = launch(context) {
    val buffer = mutableListOf<FichierFile>()
    for (file in files) {
        if (buffer.isNotEmpty() && channel.offer(buffer[0])) {
            buffer.removeAt(0)
        }
        requestLimiter.blah()
        println("Checking if filesize of ${file.name} on disk matches 1fichier")
        val diskFile = File(folder, file.name)
        file.prepareDownload()
        val size = file.initDownload()
        if (size == diskFile.length()) {
            println("It does, skipping download...")
        } else {
            println("It dosen't, enqueueing for download...")
            diskFile.delete()
            if (!channel.offer(file)) {
                buffer.add(file)
            }
        }
        file.endDownload()
    }
    buffer.intoChannel(channel, context).join()
}

fun <E> List<E>.intoChannel(channel: Channel<E>, context: CoroutineContext) : Job {
    val thing = this
    return launch(context) {
        for (item in thing) {
            channel.send(item)
        }
    }
}

fun lunchDownloads(channel: Channel<FichierFile>, threads: Int, limiter: TokenBucket, requestLimiter: RequestLimiter, folder: File, context: CoroutineContext) = launch(context) {
    val currentFiles = mutableListOf<FichierFile>()
    val toDelete = mutableListOf<FichierFile>()
    var next : Deferred<FichierFile>? = null
    while (true) {
        if (next?.isCompleted == true) {
            currentFiles.add(next.getCompleted())
            next = null
        }
        do {
            for (file in currentFiles) {
                val size = file.downloadChunck()
                limiter.useTokens(size.toLong())
                if (size == -1) {
                    toDelete.add(file)
                }
            }
            for (file in toDelete) {
                currentFiles.remove(file)
                file.endDownload()
                println("Done downloading ${file.name}")
            }
            toDelete.clear()
            yield()
        } while (currentFiles.size == threads)

        //Start the download of one new file
        if (next == null && !channel.isClosedForReceive) {
            next = async {
                requestLimiter.blah()
                val toAdd = channel.receive()
                println("Starting download of ${toAdd.name}")
                toAdd.prepareDownload()
                toAdd.initDownload()
                toAdd.openFile(File(folder, toAdd.name))
                toAdd
            }
        }
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
            delay((chunkSize - current) / rate)
            fillBucket()
        }
        current -= chunkSize
    }
}