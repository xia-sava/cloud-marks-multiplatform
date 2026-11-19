package to.sava.cloudmarksmultiplatform.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.nio.file.Path

/** APK の MIME タイプ。端末のインストーラは、この種類の URI を開く。 */
private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

/**
 * ダウンロードした APK を端末のインストーラへ渡す FileProvider の authority の、アプリの ID に続く部分。
 * マニフェストの provider の authority（`${applicationId}.updates`）と合わせる。食い違いは `androidApp` の `verifyFileProviders` が見る。
 */
internal const val UPDATE_PROVIDER_SUFFIX = ".updates"

/**
 * ダウンロードして SHA-256 を確かめた APK を、さらに確かめて端末のインストーラへ渡す手順。[AndroidUpdater] が端末に触るのはここだけで、
 * 差し替えると、端末のインストーラへ渡さずに段階の進み方を確かめられる。
 */
internal interface ApkInstaller {
    /** [apk] のパッケージ名が、このアプリのものか。違うか、APK として読めなければ、理由をログに書いて偽を返す。 */
    fun isOwnPackage(apk: Path): Boolean

    /**
     * [apk] を端末のインストーラへ渡す。渡せなければ投げる。
     * 画面が裏にあるあいだに渡したときは、投げずに戻っても、端末がインストーラの起動を止めて開かないことがある。
     */
    fun install(apk: Path)
}

/**
 * Android の [ApkInstaller]。
 *
 * パッケージ名を見るのは、SHA-256 が更新情報と合っても、このアプリでないものをインストールさせないため。
 * 端末のインストーラは、署名が今のアプリと違う APK を、更新として受け付けない。
 *
 * @param context アプリの Context。端末のインストーラを、Activity の外から起動する
 * @param log 確かめに通らなかった理由を書く
 */
internal class AndroidUpdateInstaller(
    private val context: Context,
    private val log: (String) -> Unit,
) : ApkInstaller {
    override fun isOwnPackage(apk: Path): Boolean {
        @Suppress("DEPRECATION")
        val archivePackageName = context.packageManager.getPackageArchiveInfo(apk.toString(), 0)?.packageName
        return isOwnPackageName(archivePackageName, context.packageName, log)
    }

    /**
     * [apk] を FileProvider の URI（authority は [UPDATE_PROVIDER_SUFFIX]）にして読む権限を添え、端末のインストーラを開く。
     * インストールするかどうかは端末が利用者に尋ね、結果はこちらへ返らない。分かるのは、起動を頼めたかどうかまで。
     * 画面が裏にあるあいだは、頼めても端末が起動を止め、インストーラが開かないことがある。
     * Activity の外から起動するので、新しいタスクで開く。
     *
     * @throws IllegalArgumentException [apk] が、FileProvider の場所（`update_paths`）の外にある
     * @throws android.content.ActivityNotFoundException APK を開ける Activity が無い
     */
    override fun install(apk: Path) {
        val uri = FileProvider.getUriForFile(context, context.packageName + UPDATE_PROVIDER_SUFFIX, apk.toFile())
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

/**
 * APK から読んだパッケージ名 [archivePackageName]（読めなければ null）が、このアプリのパッケージ名 [ownPackageName] と同じか。
 * 違うか読めなければ、理由を [log] に書いて偽を返す。
 */
internal fun isOwnPackageName(archivePackageName: String?, ownPackageName: String, log: (String) -> Unit): Boolean =
    when (archivePackageName) {
        ownPackageName -> true
        null -> false.also { log("ダウンロードした更新を、APK として読めなかった") }
        else -> false.also { log("ダウンロードした更新のパッケージ名 $archivePackageName が、このアプリのもの（$ownPackageName）でない") }
    }
