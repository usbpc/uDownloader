import okhttp3.*
import org.jsoup.Jsoup
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.lang.IllegalStateException
import java.util.concurrent.TimeUnit

class OneFichierManager(client : OkHttpClient, val usr: String, val pwd: String) : CookieJar {
    val client = client.newBuilder()
            .cookieJar(this)
            .followRedirects(false)
            .connectTimeout(1, TimeUnit.MINUTES)
            .readTimeout(1, TimeUnit.MINUTES)
            .build()

    fun login() {

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

        val response = client.newCall(request).execute()
        response.body()!!.close()
    }

    fun getFilesFromFolder(url: String) : List<FichierFile>? {
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        val body = response.body()?.string() ?: return null
        response.body()!!.close()
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

class FichierFile( val client: OkHttpClient, val baseUrl : String, val name : String, val size : String, val manager: OneFichierManager) {
    lateinit var fileURL : String

    suspend fun prepareDownload(pwd: String? = null) {
        var shouldRetry : Boolean
        do {
            shouldRetry = false
            val request: Request
            val response: Response
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

            response = client.newCall(request).await()
            val body = response.body()
            if (response.code() == 302) {
                //Success
                fileURL = response.header("Location")!!
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
            body?.close()
        } while(shouldRetry)
    }

    var total = 0L
    val data = ByteArray(1024)
    lateinit var input : BufferedInputStream
    lateinit var output : FileOutputStream

    suspend fun initDownload() : Long {
        if (!this::fileURL.isInitialized) return -1
        val request = Request.Builder().url(fileURL).build()
        val response = client.newCall(request).await()
        input = BufferedInputStream(response.body()!!.byteStream())

        return response.header("Content-Length")!!.toLong()
    }

    fun openFile(file : File) {
        output = FileOutputStream(file)
    }

    fun endDownload() {
        input.close()
        if (::output.isInitialized) {
            output.flush()
            output.close()
        }
    }

    fun downloadChunck() : Int {
        val count = input.read(data)
        if (count != -1) {
            total += count
            output.write(data, 0, count)
        } else {
            endDownload()
        }
        return count
    }
}