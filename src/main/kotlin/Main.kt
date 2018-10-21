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

    val manager = OneFichierManager(client)
    manager.login(parsedArgs.username, parsedArgs.password)

    val rawFiles = manager.getFilesFromFolder(parsedArgs.url) ?: return@runBlocking

    val exists = ArrayDeque<FichierFile>()
    val files = ArrayDeque<FichierFile>()
    for (file in rawFiles) {
        if (file.name in existingFileNames) {
            exists.add(file)
        } else {
            files.add(file)
        }
    }

    val currentlyDownloading = mutableListOf<FichierFile>()
    var lastRequest = 0L
    while (true) {
        //TODO maybe make is async or something to not delay downloads any further then neccecary
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastRequest > 10L * 1000L) {
            if (currentlyDownloading.size < parsedArgs.threads && (files.isNotEmpty() || exists.isNotEmpty())) {
                lastRequest = currentTime
                val toAdd : FichierFile =
                        if (files.isNotEmpty()) {
                            files.remove()
                        } else {
                            exists.remove()
                        }
                println("Starting download of ${toAdd.name}...")
                toAdd.prepareDownload(parsedArgs.downloadPass)
                val file = File(parsedArgs.folder, toAdd.name)
                val size = toAdd.initDownload()
                if (file.length() == size || size == -1L) {
                    println("${toAdd.name} was already downloaded fully! Skipped!")
                    toAdd.endDownload()
                } else {
                    if (file.exists()) {
                        println("${toAdd.name} already existed but had the wrong size, redownloading...")
                        file.delete()
                    }
                    toAdd.openFile(file)
                    currentlyDownloading.add(toAdd)
                }

            } else if (exists.isNotEmpty()) {
                lastRequest = currentTime
                val toTest = exists.remove()
                println("Checking if ${toTest.name} is fully downloaded already!")
                toTest.prepareDownload(parsedArgs.downloadPass)
                val file = File(parsedArgs.folder, toTest.name)
                if (file.length() != toTest.initDownload()) {
                    println("${toTest.name} existed but with the wrong size, deleting and queuing to download!")
                    file.delete()
                    files.add(toTest)
                } else {
                    println("${toTest.name} was already downloaded fully, skipping!")
                }
                toTest.endDownload()
            }
        }

        val toRemove = mutableListOf<FichierFile>()

        for (file in currentlyDownloading) {
            val size = file.downloadChunck()
            limiter.useTokens(size.toLong())
            if (size == -1) {
                toRemove.add(file)
            }
        }

        for (file in toRemove) {
            println("Done downloading ${file.name}!")
            currentlyDownloading.remove(file)
        }
        if (exists.isEmpty() && files.isEmpty() && currentlyDownloading.isEmpty()) {
            break
        }
    }
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

fun lunchDownloads(channel: Channel<FichierFile>, threads: Int, limiter: TokenBucket ,context: CoroutineContext) = launch(context) {
    val currentFiles = mutableListOf<FichierFile>()
    val toDelete = mutableListOf<FichierFile>()
    var next : Deferred<FichierFile>? = null
    while (true) {
        if (next?.isCompleted == true) {
            currentFiles.add(next.getCompleted())
            next = null
        }
        while (currentFiles.size == threads) {
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
            }
            toDelete.clear()
            yield()
        }

        //Start the download of one new file
        next = async {
            val file = channel.receive()
            file.initDownload()
            file
        }
    }

}

class TokenBucket(val capacity: Long, val rate: Long) {
    var current = capacity
    var lastFill = System.currentTimeMillis()

    fun fillBucket() {
        val currentTime = System.currentTimeMillis()
        current += (currentTime - lastFill) * rate
        if (current > capacity) {
            current = capacity
        }
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