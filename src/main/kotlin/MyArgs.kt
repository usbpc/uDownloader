import com.xenomachina.argparser.ArgParser
import com.xenomachina.argparser.default
import java.io.File

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
    ) { File(this) }.default{ File(".") }

    val threads by parser.storing(
            "-t", "--threads",
            help = "How many downloads should be run in Parallel"
    ){this.toInt()}.default(4)

    val sleedlimit by parser.storing(
            "-l", "--limit",
            help = "Sleedlimit for all downloads combined (excludes some checking and initial connections) how many bytes per ms (8680 for google)"
    ){this.toLong()}.default(-1)

    val downloadPass by parser.storing(
            "--dlPw",
            help = "Password to download files in this folder"
    ).default<String?>(null)

    val url by parser.positional(
            "URL",
            help = "1fichier folder to download")
}