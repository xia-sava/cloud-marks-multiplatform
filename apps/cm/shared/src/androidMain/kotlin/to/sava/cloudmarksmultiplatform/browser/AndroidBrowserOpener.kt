package to.sava.cloudmarksmultiplatform.browser

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import to.sava.cloudmarksmultiplatform.settings.TabLoading

/** 既定のブラウザを引くときの URL。https の URL を開く既定のアプリを引く。 */
private const val BROWSER_LOOKUP_URL = "https://"

/** 既定のアプリが決まっていないときに引ける、選択画面のパッケージ。 */
private const val SYSTEM_PACKAGE = "android"

/** 選択画面の Activity の名前に含まれる語。端末によっては、[SYSTEM_PACKAGE] でないパッケージの選択画面を引く。 */
private const val RESOLVER_ACTIVITY_MARKER = "resolver"

/**
 * Android の [BrowserOpener]。
 *
 * ブラウザを選ぶメニューを出さないので、ブラウザの一覧は空にし、まとめては開かない。
 * 既定のブラウザで開くときは、https の URL を開く既定のアプリを引き、それが選択画面でなければ、そのパッケージに固定して開く
 * （[defaultBrowserPackageOf]）。引けないときや選択画面のときは、パッケージを固定せずに開く。http と https の URL だけを開く。
 *
 * @param context ブラウザを起動する。Activity の外から起動する
 * @param log 開けなかった理由を書く。URL は書かない
 * @param ioDispatcher 既定のブラウザを引くことと、ブラウザの起動に使う
 */
internal class AndroidBrowserOpener(
    private val context: Context,
    private val log: (String) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BrowserOpener {
    override val opensMultipleItems: Boolean = false

    override suspend fun browsers(): List<Browser> = emptyList()

    /**
     * 開けなかったときは、理由をログに書き、詳細の無い [BrowserOpenResult.LaunchFailed] を返す。
     * 例外のメッセージは URL を含みうるので、ログには例外の種類だけを書く。
     */
    override suspend fun openInDefaultBrowser(url: String): BrowserOpenResult {
        val intent = webViewIntentOf(url)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return defaultBrowserFailed(log, NOT_WEB_URL_REASON)
        return withContext(ioDispatcher) {
            defaultBrowserPackage()?.let(intent::setPackage)
            try {
                context.startActivity(intent)
                BrowserOpenResult.Opened
            } catch (e: ActivityNotFoundException) {
                defaultBrowserFailed(log, e::class.simpleName)
            } catch (e: SecurityException) {
                // 引いたブラウザの Activity を、ほかのアプリから起動できない
                defaultBrowserFailed(log, e::class.simpleName)
            }
        }
    }

    /** ブラウザを選ぶメニューを出さないので、呼ばれない。呼ばれたら、ログに書いて詳細の無い [BrowserOpenResult.LaunchFailed] を返す。 */
    override suspend fun openInBrowser(browser: Browser, tabs: List<BrowserTab>, tabLoading: TabLoading): BrowserOpenResult {
        log("ブラウザを選んで開くことには対応していない")
        return BrowserOpenResult.LaunchFailed(detail = null)
    }

    /** 既定のブラウザのパッケージ。既定のアプリを引けないか、選択画面を引いたら null。 */
    private fun defaultBrowserPackage(): String? =
        context.packageManager.resolveDefaultActivity(browsableViewIntentOf(Uri.parse(BROWSER_LOOKUP_URL)))
            ?.activityInfo
            ?.let { defaultBrowserPackageOf(packageName = it.packageName, activityName = it.name) }
}

/** Android のアプリの [BrowserOpener] を作る。[log] には、利用者には見せない失敗を書く。 */
fun androidBrowserOpener(context: Context, log: (String) -> Unit): BrowserOpener =
    AndroidBrowserOpener(context.applicationContext, log)

/**
 * 既定のアプリを引いた結果（パッケージ [packageName] と Activity の名前 [activityName]）から決めた、既定のブラウザのパッケージ。
 * 引けていない（[packageName] が無いか空）か、選択画面（パッケージが [SYSTEM_PACKAGE] か、Activity の名前に
 * [RESOLVER_ACTIVITY_MARKER] を含む。大文字と小文字は区別しない）なら null。
 */
internal fun defaultBrowserPackageOf(packageName: String?, activityName: String?): String? {
    if (packageName.isNullOrEmpty() || packageName == SYSTEM_PACKAGE) return null
    if (activityName?.contains(RESOLVER_ACTIVITY_MARKER, ignoreCase = true) == true) return null
    return packageName
}

/**
 * [url] をブラウザで開く Intent。既定のブラウザで開くときと、ほかのアプリで開くときに使う。http と https の URL（[isWebUrl]）でなければ null。
 * Intent の URL の種類は大文字と小文字を区別して照らし合わせるので、小文字にそろえる。
 */
internal fun webViewIntentOf(url: String): Intent? =
    if (isWebUrl(url)) browsableViewIntentOf(Uri.parse(url).normalizeScheme()) else null

/** [uri] をブラウザで開く Intent。 */
private fun browsableViewIntentOf(uri: Uri): Intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)

/** [intent] を既定で開く Activity。既定が決まっていなければ選択画面の Activity、開ける Activity が無ければ null。 */
internal fun PackageManager.resolveDefaultActivity(intent: Intent): ResolveInfo? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
    } else {
        @Suppress("DEPRECATION")
        resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
    }
