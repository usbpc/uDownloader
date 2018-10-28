package old

import kotlinx.coroutines.experimental.withContext
import okhttp3.*
import org.jsoup.Jsoup
import xyz.usbpc.kotlin.utils.await
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.coroutines.experimental.CoroutineContext

class OneFichierManager(client : OkHttpClient, val usr: String, val pwd: String) : CookieJar {
    val client = client.newBuilder()
            .cookieJar(this)
            .followRedirects(false)
            .connectTimeout(1, TimeUnit.MINUTES)
            .readTimeout(1, TimeUnit.MINUTES)
            .build()

    suspend fun login() {
        val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("lt", "on")
                .addFormDataPart("mail", usr)
                .addFormDataPart("pass", pwd)
                .addFormDataPart("purge", "on")
                .addFormDataPart("valider", "OK")
                .build()

        val request = Request.Builder()
                .url("https://1fichier.com/login.pl")
                .post(requestBody)
                .build()

        retry(3) { client.newCall(request).await() }.close()
    }

    suspend fun getFilesFromFolder(url: String, pwd: String?) : List<FichierFile>? {
        val request = Request.Builder().url(url).build()
        retry(3) { client.newCall(request).await() }.use { response ->
            val body = response.body()?.string() ?: return null
            val entries = Jsoup.parse(body).body().select("table.premium").select("tr")
            return List(entries.size - 1)
            { elem ->
                val current = entries[elem + 1]
                FichierFile(
                        client,
                        current.selectFirst("a").attr("href"),
                        current.selectFirst("a").text(),
                        current.select("td").last().text(),
                        this,
                        pwd
                )
            }
        }
    }

    var loginCookie : Cookie? = null
    override fun saveFromResponse(url: HttpUrl, cookies: MutableList<Cookie>) {
        for (cookie in cookies) {
            if (cookie.domain() == "1fichier.com" && cookie.name() == "SID") {
                loginCookie = cookie
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): MutableList<Cookie> {
        return if (loginCookie != null) {
            mutableListOf(loginCookie!!)
        } else {
            mutableListOf()
        }
    }
}

class FichierFile(val client: OkHttpClient, val baseUrl : String, val name : String, val size : String, val manager: OneFichierManager, val pwd: String?) {
    private suspend fun getUrl() : String? {
        var shouldRetry : Boolean
        do {
            shouldRetry = false
            val request: Request
            if (pwd == null) {
                request = Request.Builder()
                        .url(baseUrl)
                        .build()

            } else {
                val requestBody = MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("did", "0")
                        .addFormDataPart("pass", pwd)
                        .build()
                request = Request.Builder()
                        .url(baseUrl)
                        .post(requestBody)
                        .build()
            }
            retry(3) { client.newCall(request).await() }.use { response ->
                val body = response.body()
                if (response.code() == 302) {
                    return response.header("Location")!!
                } else if (response.code() == 200) {
                    val bodyString = body?.string()
                    val soup = Jsoup.parse(bodyString)
                    val thing = soup.select("a[title='Login'].ui-button.ui-corner-all").firstOrNull()
                    if (thing != null) {
                        println("Login session seems to have run out, logging in again...")
                        manager.login()
                        shouldRetry = true
                    } else {
                        println("I got a ${response.code()} while trying to get the real download URL! ${bodyString}")
                    }
                }
            }
        } while(shouldRetry)
        return null
    }

    suspend fun getFilesize() : Long {
        return getUrl()?.let {fileURL ->
            val request = Request.Builder().url(fileURL).method("HEAD", null).build()
            retry(3) { client.newCall(request).await() }.use { response ->
                response.header("Content-Length")?.toLong()
            }
        } ?: -1
    }

    suspend fun download(file: File, blockingContext: CoroutineContext) {
        getUrl()?.let { fileURL ->
            val request = Request.Builder().url(fileURL).build()
            retry(3) { client.newCall(request).await() }.use { response ->
                val inputStream = response.body()!!.byteStream()
                withContext(blockingContext) {
                    Files.copy(inputStream, file.toPath())
                }
            }
        }
    }
}