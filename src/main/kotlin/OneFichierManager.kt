import org.jsoup.Jsoup
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.lang.IllegalStateException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class OneFichierManager(val client : HttpClient, val usr: String, val pwd: String)  {
    suspend fun login() {

        val request = HttpRequest.newBuilder()
                .url("https://1fichier.com/login.pl")
                .POST(mapOf(
                        "lt" to "on",
                        "mail" to usr,
                        "pass" to pwd,
                        "purge" to "on",
                        "valider" to "OK"
                        ))
                .build()

        client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).await()

        //TODO check if login really worked
    }

    suspend fun getFilesFromFolder(url: String) : List<FichierFile>? {
        val request = HttpRequest.newBuilder().url(url).build()
        val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        val body = response.body()

        val entries = Jsoup.parse(body).body().select("table.premium").select("tr")

        val files = List(entries.size - 1)
        { elem ->
            val current = entries[elem + 1]
            FichierFile(
                    client,
                    current.selectFirst("a").attr("href"),
                    current.selectFirst("a").text(),
                    current.select("td").last().text(), this)
        }

        return files
    }
}

class FichierFile(val client: HttpClient, val baseUrl : String, val name : String, val size : String, val manager: OneFichierManager) {
    lateinit var fileURL : String

    suspend fun prepareDownload(pwd: String? = null) : Boolean {
        var shouldRetry : Boolean
        do {
            shouldRetry = false
            val request: HttpRequest
            val response: HttpResponse<String>
            if (pwd == null) {
                request = HttpRequest.newBuilder()
                        .url(baseUrl)
                        .build()

            } else {
                request = HttpRequest.newBuilder()
                        .url(baseUrl)
                        .POST(mapOf(
                                "did" to "0",
                                "pass" to pwd))
                        .build()
            }

            response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
            if (response.statusCode() == 302) {
                //Success
                fileURL = response.headers().get("Location") ?: return false
            } else if (response.statusCode() == 200) {
                val soup = Jsoup.parse(response.body())
                val thing = soup.select("a[title='Login'].ui-button.ui-corner-all").firstOrNull()
                if (thing != null) {
                    println("Login session seems to have run out, logging in again...")
                    manager.login()
                    shouldRetry = true
                } else {
                    println("I got a ${response.statusCode()} while trying to get the real download URL! ${response.body()}")
                }
            }
        } while(shouldRetry)
        return this::fileURL.isInitialized
    }

    suspend fun getSize() : Long {
        val request = HttpRequest.newBuilder()
                .url(fileURL)
                .HEAD()
                .build()
        val response = client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).await()

        return response.headers().get("content-length")?.toLong() ?: throw IllegalStateException("No Content-Length header! ${response.headers()}")
    }

    suspend fun download(path: Path) {
        val request = HttpRequest.newBuilder()
                .url(fileURL)
                .build()

        val response =
                client.send(request) { responseInfo ->
            println("Got headers back ${responseInfo.headers()}, Status code ${responseInfo.statusCode()}")
            val handler = HttpResponse.BodyHandlers.ofFile(path)
            MySubscriber(handler.apply(responseInfo))
        }

        response.statusCode()
    }
}