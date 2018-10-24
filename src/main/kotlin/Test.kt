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
            .connectTimeout(Duration.ofSeconds(10))
            .build()

    val request = HttpRequest.newBuilder()
            .url("https://o-5.1fichier.com/p86019894")
            //.url("https://speed.hetzner.de/10GB.bin")
            .build()

    println("Starting download...")
    client.send(request) {info ->
        println("Got headers back ${info.headers()}, Status code ${info.statusCode()}")
        val responseHandler = HttpResponse.BodyHandlers.ofFile(file.toPath())
        MySubscriber(responseHandler.apply(info))
    }
    println("Done with download...")
}

class MySubscriber(val subscriber: HttpResponse.BodySubscriber<Path>) : HttpResponse.BodySubscriber<Path> {
    private var counter = 0
    private var total = 0L
    override fun onComplete() {
        println("Total bytes: $total")
        subscriber.onComplete()
    }

    override fun onSubscribe(subscription: Flow.Subscription?) {
        println("onSubscribe")
        subscriber.onSubscribe(subscription)
    }

    override fun onNext(item: MutableList<ByteBuffer>) {
        println("Got data!")
        item.forEach { size ->
            println("I got ${size.remaining()} bytes ($counter)")
            total += size.remaining()
        }
        counter++
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