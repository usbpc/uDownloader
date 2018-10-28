package xyz.usbpc.uDownloader.main

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.*
import kotlinx.coroutines.experimental.selects.select
import xyz.usbpc.kotlin.utils.*
import xyz.usbpc.uDownloader.hosts.OneFichierFile
import xyz.usbpc.uDownloader.hosts.OneFichierManager
import java.io.File
import java.lang.Exception
import kotlin.coroutines.experimental.CoroutineContext

/**
 * Terminates once receiveChannel is closed
 */
fun CoroutineScope.folderWorker(
        oneFichierManager: OneFichierManager,
        receiveChannel: ReceiveChannel<ConfigLine>,
        toCheckers: SendChannel<ReceiveChannel<OneFichierFile>>,
        toDownloaders: SendChannel<ReceiveChannel<OneFichierFile>>)
        = launch {
    for (line in receiveChannel) {
        oneFichierManager.getFilesFromFolder(line.url, line.directory, line.password)?.let { files ->
            val exist = mutableListOf<OneFichierFile>()
            val new = mutableListOf<OneFichierFile>()
            files.forEach { file ->
                if (file.file.exists()) exist.add(file) else new.add(file)
            }
            val checkerChannel = Channel<OneFichierFile>()
            val downloaderChannel = Channel<OneFichierFile>()
            sendListThenClose(exist, checkerChannel)
            sendListThenClose(new, downloaderChannel)
            toCheckers.send(checkerChannel)
            toDownloaders.send(downloaderChannel)
        }
    }
}

data class ConfigLine(val url: String, val directory: File, val password: String?)

fun CoroutineScope.configWorker(config: File, sendChannel: SendChannel<ConfigLine>) = launch {
    for (line in config.readLines()) {
        line.split("\t").let { parts ->
            if (parts.size < 2) {
                println("$line is not valid")
                null
            } else {
                val url = parts[0]
                val folder = File(parts[1])
                val pwd : String? = if (parts.size == 3) { parts[3] } else {null}
                ConfigLine(url, folder, pwd)
            }
        }?.let {configLine ->
            sendChannel.send(configLine)
        }
    }
}

fun <E> CoroutineScope.aggregateSender(ingress: ReceiveChannel<E>, egress: SendChannel<E>) = launch {
    val items = mutableListOf<E>()

    //While I can get more items both listen for more and try to send the ones you have.
    while (!ingress.isClosedForReceive) {
        if (items.isEmpty()) {
            items.add(ingress.receive())
        } else {
            val toSend = items.first()
            try {
                select<Unit> {
                    egress.onSend(toSend) {
                        //Item was send so remove it
                        items.removeAt(0)
                    }
                    ingress.onReceive { item ->
                        items.add(item)
                    }
                }
            } catch (_: ClosedReceiveChannelException) { }
        }
    }
    sendListThenClose(items, egress)
}
