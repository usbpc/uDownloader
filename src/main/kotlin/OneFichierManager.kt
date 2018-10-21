import okhttp3.*
import org.jsoup.Jsoup
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class OneFichierManager(client : OkHttpClient) : CookieJar {
    val client = client.newBuilder()
            .cookieJar(this)
            .followRedirects(false)
            .connectTimeout(1, TimeUnit.MINUTES)
            .build()

    fun login(usr : String, pwd : String) {

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
                    current.select("td").last().text())
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

class FichierFile( val client: OkHttpClient, val baseUrl : String, val name : String, val size : String) {
    lateinit var fileURL : String

    suspend fun prepareDownload(pwd: String? = null) {
        val request : Request
        val response : Response
        if (pwd == null) {
            request = Request.Builder()
                    .url(baseUrl)
                    .build()

            response = client.newCall(request).await()

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

            response = client.newCall(request).await()
        }

        if (response.code() == 302) {
            //Success
            fileURL = response.header("Location")!!
        } else {
            //TODO parse failed attempt to see what's wrong!
            println("I got a ${response.code()} while trying to get the real download URL! ${response.body()!!.string()}")
        }
        response.body()!!.close()
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