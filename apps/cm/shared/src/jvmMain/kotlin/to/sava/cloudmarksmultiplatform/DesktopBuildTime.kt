package to.sava.cloudmarksmultiplatform

import java.io.IOException
import java.io.UncheckedIOException
import java.net.URISyntaxException
import java.net.URL
import java.nio.file.FileSystemNotFoundException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/** クラスファイルの拡張子。 */
private const val CLASS_FILE_SUFFIX = ".class"

/**
 * 開発版のビルドの時刻（エポックからのミリ秒）。[appClass] と `shared` のクラスを読み込んだ場所（jar かディレクトリ）の、
 * 更新時刻の新しいほう（[lastModifiedMillisOf]）。`shared` だけをビルドし直したときも、一部のクラスだけを
 * ビルドし直したときも時刻が進む。どちらの時刻も分からなければ null。
 *
 * 時刻をビルドが書くリソースに入れると、ビルドのたびに中身が変わって Gradle の up-to-date の判定が効かなくなるので、
 * 時刻は実行のときにファイルの更新時刻から取る。
 */
fun desktopBuildEpochMillis(appClass: Class<*>): Long? =
    listOf(appClass, AppPath::class.java)
        .mapNotNull { it.protectionDomain?.codeSource?.location?.let(::lastModifiedMillisOf) }
        .maxOrNull()

/**
 * クラスを読み込んだ場所 [location]（jar かディレクトリの `file:` の URL）の更新時刻（エポックからのミリ秒）。
 * jar ならそのファイルの更新時刻、ディレクトリなら、その下のクラスファイルの更新時刻のうち最も新しいもの。
 * ファイルが無いとき、ディレクトリの下にクラスファイルが無いとき、ローカルのファイルを指さないときは null。
 */
internal fun lastModifiedMillisOf(location: URL): Long? =
    try {
        val path = Path.of(location.toURI())
        if (path.isDirectory()) newestClassFileMillisIn(path) else Files.getLastModifiedTime(path).toMillis()
    } catch (e: IOException) {
        null
    } catch (e: UncheckedIOException) {
        // ディレクトリをたどる途中で読めなかった
        null
    } catch (e: URISyntaxException) {
        null
    } catch (e: IllegalArgumentException) {
        // file: でない URI など
        null
    } catch (e: FileSystemNotFoundException) {
        // ファイルシステムの無い種類の URI
        null
    }

/**
 * [directory] の下（深さを問わない）のクラスファイルの、更新時刻のうち最も新しいもの。クラスファイルが無ければ null。
 *
 * @throws IOException ディレクトリかファイルを読めなかった
 * @throws UncheckedIOException ディレクトリをたどる途中で読めなかった
 */
private fun newestClassFileMillisIn(directory: Path): Long? =
    Files.walk(directory).use { paths ->
        paths.iterator().asSequence()
            .filter { it.name.endsWith(CLASS_FILE_SUFFIX) && it.isRegularFile() }
            .maxOfOrNull { Files.getLastModifiedTime(it).toMillis() }
    }
