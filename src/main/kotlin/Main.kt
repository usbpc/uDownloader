import com.xenomachina.argparser.ArgParser
import com.xenomachina.argparser.default
import kotlinx.coroutines.experimental.runBlocking
import okhttp3.*
import java.io.File
import java.lang.Exception
import java.util.*

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
            help = "Sleedlimit for all downloads combined (excludes some checking and initial connections) Examples: 8Mb/1s etc."
    ).default<String?>(null)

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
    val client = OkHttpClient()

    parsedArgs.folder.mkdirs()

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
            if (currentlyDownloading.size < 2 && (files.isNotEmpty() || exists.isNotEmpty())) {
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
                if (file.length() == toAdd.initDownload()) {
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

class TokenBuket(var capacity: Long, val rate: Long) {
    
}