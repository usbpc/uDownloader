import com.xenomachina.argparser.ArgParser
import com.xenomachina.argparser.ShowHelpException
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.Channel
import java.io.File
import java.io.OutputStreamWriter
import java.net.CookieManager
import java.net.http.HttpClient
import java.time.Duration

fun main(args: Array<String>) = runBlocking {
    val parsedArgs : MyArgs
    try {
        parsedArgs = ArgParser(args).parseInto(::MyArgs)
    } catch (e: ShowHelpException) {
        val writer = OutputStreamWriter(System.out)
        e.printUserMessage(writer, "uDownloader", 100)
        writer.flush()
        System.exit(e.returnCode)
        return@runBlocking
    }

    val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMinutes(1))
            .cookieHandler(MyCookieManager(CookieManager()))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

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
    val sender = files.intoChannel(channel, this)

    val requestLimiter = RequestLimiter()
    val workers = List(1) {
        println("Starting worker $it")
        launch {
            worker(channel, parsedArgs.folder, parsedArgs.downloadPass)
        }
    }
    //val checker = lunchChecker(parsedArgs.folder, requestLimiter, this, exists, channel)

    //checker.join()
    println("All preexisting files have been checked!")
    sender.join()
    println("All files have been send for downloading!")
    channel.close()

    for (worker in workers) {
        worker.join()
    }
    println("Done downloading everything!")
}

fun lunchChecker(folder: File, requestLimiter: RequestLimiter, scope: CoroutineScope, files: List<FichierFile>, channel: Channel<FichierFile>) = scope.launch {
    val buffer = mutableListOf<FichierFile>()
    for (file in files) {
        if (buffer.isNotEmpty() && channel.offer(buffer[0])) {
            buffer.removeAt(0)
        }
        requestLimiter.blah()
        println("Checking if filesize of ${file.name} on disk matches 1fichier")
        val diskFile = File(folder, file.name)
        file.prepareDownload()
        val size = file.getSize()
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
    buffer.intoChannel(channel, scope).join()
}

fun <E> List<E>.intoChannel(channel: Channel<E>, scope: CoroutineScope) : Job {
    val thing = this
    return scope.launch {
        for (item in thing) {
            channel.send(item)
        }
    }
}

suspend fun worker(channel: Channel<FichierFile>, folder: File, dlPwd: String?) {
    for (file in channel) {
        println("Starting download of ${file.name}")
        if (file.prepareDownload(dlPwd)) {
            val diskFile = File(folder, file.name)
            diskFile.delete()
            file.download(diskFile.toPath())
            println("Done downloading ${file.name}")
        } else {
            println("I couldn't get the download url for ${file.name}")
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