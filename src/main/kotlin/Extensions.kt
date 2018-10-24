import java.lang.StringBuilder
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.experimental.suspendCoroutine

/**
 * This allows me to use a CompletableFuture in Coroutine style
 */
suspend inline fun <T> CompletableFuture<T>.await() : T = suspendCoroutine {
    this.whenComplete { data, err ->
        if (err == null) {
            it.resume(data)
        } else {
            it.resumeWithException(err)
        }
    }
}

fun HttpRequest.Builder.url(url: String) = this.uri(URI.create(url))

fun HttpRequest.Builder.POST(fields: Map<String, String>) = this.apply {
    val body = StringBuilder()
    fields.map { entry ->
        URLEncoder.encode(entry.key, "UTF-8") to URLEncoder.encode(entry.value, "UTF-8")
    }.forEach { (key, value) ->
        body.apply {
            append(key)
            append('=')
            append(value)
            append('&')
        }
    }
    if (body.isNotEmpty()) {
        body.deleteCharAt(body.length - 1)
    }

    header("Content-Type", "application/x-www-form-urlencoded")
    POST(HttpRequest.BodyPublishers.ofString(body.toString()))
}

fun HttpRequest.Builder.HEAD() = this.apply {
    method("HEAD", HttpRequest.BodyPublishers.noBody())
}

fun HttpHeaders.get(name: String) : String? = this.firstValue(name).orElseGet { null }