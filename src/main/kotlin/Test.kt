import java.io.File
import java.net.CookieHandler
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow

fun main(args: Array<String>) {
    val dir = File("C:\\Users\\kjh\\Downloads")
    val file = File(dir, "test.bin")

    file.delete()

    val client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build()

    val request = HttpRequest.newBuilder()
            //.url("https://o-5.1fichier.com/p86046295")
            //.url("https://doc-0k-b0-docs.googleusercontent.com/docs/securesc/ha0ro937gcuc7l7deffksulhg5h7mbp1/cqgfb52e0s1l6j3pm4qmeqas5je95qn3/1540382400000/12882897270351256762/*/1xhGYSODIhIsGrC7qkx4fq1bqghXgr81c?e=download")
            //.url("https://briareus.feralhosting.com/mattpalm/Film/Mandy.2018.1080p.AMZN.WEB-DL.DDP5.1.H.264-NTG.mkv")
            //.url("http://localhost/test.bin")
            .url("https://map.usbcraft.net/file/usbpc-downloads/java_http_client.fail")
            .build()

    println("Starting download...")
    client.send
    client.send(request) {info ->
        println("Version: ${info.version()} Status code ${info.statusCode()} Got headers back ${info.headers()}")
        println("Content-Length parsed to int: ${info.headers().get("content-length")?.toLong()?.toInt()}")
        predictFailure(info)
        val responseHandler = HttpResponse.BodyHandlers.ofFile(file.toPath())
        MySubscriber(responseHandler.apply(info))
    }
    println("Done with download...")

}

fun predictFailure(info: HttpResponse.ResponseInfo) {
    if (info.version() == HttpClient.Version.HTTP_1_1) {
        val length = info.headers().get("content-length")?.toLong()?.toInt() ?: 0
        if (length <= -3) {
            println("Download will fail")
            return
        }
    }
    println("Download will work")
}

class MySubscriber(val subscriber: HttpResponse.BodySubscriber<Path>) : HttpResponse.BodySubscriber<Path> {
    override fun onComplete() {
        println("onComplete")
        subscriber.onComplete()
    }
    override fun onSubscribe(subscription: Flow.Subscription?) {
        println("onSubscribe")
        subscriber.onSubscribe(subscription)
    }
    override fun onNext(item: MutableList<ByteBuffer>) {
        println("onNext")
        subscriber.onNext(item)
    }
    override fun onError(throwable: Throwable) {
        println("I got an error ${throwable.localizedMessage}")
        subscriber.onError(throwable)
    }
    override fun getBody(): CompletionStage<Path> {
        return subscriber.body
    }
}

class MyCookieManager(val handler: CookieHandler) : CookieHandler() {
    override fun put(uri: URI?, responseHeaders: MutableMap<String, MutableList<String>>?) {

        println("Got cookies back from $uri : $responseHeaders")

        handler.put(uri, responseHeaders)
    }

    override fun get(uri: URI?, requestHeaders: MutableMap<String, MutableList<String>>?): MutableMap<String, MutableList<String>> {
        val headers = handler.get(uri, requestHeaders)

        println("Cookies for $uri are $headers")

        return headers
    }
}