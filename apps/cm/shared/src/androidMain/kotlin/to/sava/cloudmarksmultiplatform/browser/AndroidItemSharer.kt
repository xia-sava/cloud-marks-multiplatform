package to.sava.cloudmarksmultiplatform.browser

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 共有するときに渡すものの種類。 */
private const val SHARED_TEXT_TYPE = "text/plain"

/** ほかのアプリで開けなかったときの、ログの文の頭。 */
private const val OPEN_FAILED = "ほかのアプリで開けなかった"

/** 共有できなかったときの、ログの文の頭。 */
private const val SHARE_FAILED = "共有できなかった"

/** 開けるアプリを引けなかった理由。 */
private const val NO_APP_REASON = "開けるアプリが無い"

/**
 * 共有するときに渡す文。
 *
 * @property text 本文（`EXTRA_TEXT`）。項目の URL
 * @property subject 件名（`EXTRA_SUBJECT`）。項目のタイトル。タイトルが空なら null で、件名を付けない
 */
internal data class SharedText(val text: String, val subject: String?)

/** URL が [url]、タイトルが [title] の項目を共有するときに渡す文。 */
internal fun sharedTextOf(url: String, title: String): SharedText = SharedText(text = url, subject = title.ifEmpty { null })

/**
 * Android の [ItemSharer]。受け取るアプリは、Android のアプリの一覧（[Intent.createChooser]。見出しは Android の既定のまま）から選ぶ。
 *
 * ほかのアプリで開くときは、http と https の URL だけを、既定のブラウザで開くときと同じ Intent（[webViewIntentOf]）で開く。
 * 共有するときは、URL を本文に、タイトルを件名にした文（[sharedTextOf]）を `ACTION_SEND` で渡す。
 * 出せなかったときは、理由をログに書く。例外のメッセージは URL を含みうるので、ログには例外の種類だけを書く。
 *
 * @param context アプリの一覧を出す。Activity の外から出す
 * @param log 出せなかった理由を書く。URL とタイトルは書かない
 * @param ioDispatcher 開けるアプリを引くことと、アプリの一覧を出すことに使う
 */
internal class AndroidItemSharer(
    private val context: Context,
    private val log: (String) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ItemSharer {
    /**
     * 開けるアプリを引けないか、アプリの一覧を出そうとして [ActivityNotFoundException] になれば、[ItemShareResult.NoApp] を返す。
     * http と https でない URL は開かず、[ItemShareResult.Failed] を返す。
     */
    override suspend fun openInOtherApp(url: String): ItemShareResult {
        val view = webViewIntentOf(url) ?: return failed(OPEN_FAILED, NOT_WEB_URL_REASON)
        return withContext(ioDispatcher) {
            if (context.packageManager.resolveDefaultActivity(view) == null) {
                log("$OPEN_FAILED: $NO_APP_REASON")
                return@withContext ItemShareResult.NoApp
            }
            startChooser(view, OPEN_FAILED)
        }
    }

    override suspend fun share(url: String, title: String): ItemShareResult =
        withContext(ioDispatcher) {
            val text = sharedTextOf(url, title)
            val send = Intent(Intent.ACTION_SEND).setType(SHARED_TEXT_TYPE).putExtra(Intent.EXTRA_TEXT, text.text)
            text.subject?.let { send.putExtra(Intent.EXTRA_SUBJECT, it) }
            startChooser(send, SHARE_FAILED)
        }

    /** [target] を受け取るアプリの一覧を出す。出せなければ、[failure] に理由を添えてログに書く。 */
    private fun startChooser(target: Intent, failure: String): ItemShareResult =
        try {
            context.startActivity(Intent.createChooser(target, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ItemShareResult.Shown
        } catch (e: ActivityNotFoundException) {
            log("$failure: ${e::class.simpleName}")
            ItemShareResult.NoApp
        } catch (e: SecurityException) {
            failed(failure, e::class.simpleName)
        }

    /** [failure] に理由 [reason] を添えてログに書き、[ItemShareResult.Failed] を返す。[reason] には URL を入れない。 */
    private fun failed(failure: String, reason: String?): ItemShareResult {
        log("$failure: $reason")
        return ItemShareResult.Failed
    }
}

/** Android のアプリの [ItemSharer] を作る。[log] には、利用者には見せない失敗を書く。 */
fun androidItemSharer(context: Context, log: (String) -> Unit): ItemSharer = AndroidItemSharer(context.applicationContext, log)
