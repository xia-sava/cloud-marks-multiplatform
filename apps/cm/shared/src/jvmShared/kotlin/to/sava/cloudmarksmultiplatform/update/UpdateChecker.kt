package to.sava.cloudmarksmultiplatform.update

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import to.sava.cloudmarksmultiplatform.files.describeThrowable

/** 配布物を置く GitHub のリポジトリ。 */
private const val RELEASE_REPOSITORY = "xia-sava/cloud-marks-multiplatform"

/** 最新の配布物を置くリリースのタグ。リリースのたびに配布物を置き換える。タグは最初に作ったときのコミットを指したまま動かさない。 */
private const val RELEASE_TAG = "latest"

/** 更新情報の名前。 */
private const val UPDATE_INFO_NAME = "latest.json"

/** 更新情報の署名の名前の末尾。更新情報と同じ場所に、更新情報の名前にこれを付けた名前で置く。 */
private const val SIGNATURE_SUFFIX = ".sig"

/** GitHub Releases の、最新のリリースに置いた [assetName] の URL。 */
internal fun releaseAssetUrl(assetName: String): String =
    "https://github.com/$RELEASE_REPOSITORY/releases/download/$RELEASE_TAG/$assetName"

/** 更新情報の URL。 */
internal val UPDATE_INFO_URL: String = releaseAssetUrl(UPDATE_INFO_NAME)

/** 更新情報の署名の URL。 */
internal val UPDATE_INFO_SIGNATURE_URL: String = releaseAssetUrl(UPDATE_INFO_NAME + SIGNATURE_SUFFIX)

/**
 * 更新情報を取り、配布しているバージョンと、動いているアプリのバージョン [currentVersionCode] を比べる。
 *
 * 更新情報（[UPDATE_INFO_URL]）とその署名（[UPDATE_INFO_SIGNATURE_URL]）は、どちらも [MAX_UPDATE_INFO_BYTES] まで受け取る。
 * 受け取ったバイト列そのものの署名を確かめてから、更新情報として読む。署名が無い・読めない・合わないときは読まない。
 * 配布物の URL は更新情報から取らず、決まった形で組む（[releaseAssetUrl]）ので、更新情報が決められるのはバージョンと SHA-256 だけになる。
 *
 * どの失敗も [UpdateCheck.Failed] として返し、細かい理由（HTTP の状態、例外）は [log] に書く。取り消しは投げ直す。
 *
 * @param platform 動いているプラットフォーム。更新情報のこの項目を読む
 * @param publicKey 署名を確かめる公開鍵。配布するアプリは [UPDATE_INFO_PUBLIC_KEY] を使う
 */
internal class UpdateChecker(
    private val httpClient: HttpClient,
    private val platform: UpdatePlatform,
    private val currentVersionCode: Int,
    private val log: (String) -> Unit,
    private val publicKey: String = UPDATE_INFO_PUBLIC_KEY,
) {
    /**
     * 更新を確認する。結果は [UpdateCheck.UpToDate]、[UpdateCheck.Available]、[UpdateCheck.Failed] のどれか。
     *
     * 応答の本体を読むところと署名を確かめるところはブロックする。呼び出し側が I/O 用のスレッド（`Dispatchers.IO`）で呼ぶ。
     */
    suspend fun check(): UpdateCheck {
        val updateInfo = download(UPDATE_INFO_URL, "更新情報")
            ?: return UpdateCheck.Failed(UpdateCheckFailure.UNREACHABLE)
        val signature = download(UPDATE_INFO_SIGNATURE_URL, "更新情報の署名")
        if (signature == null || !verifyUpdateInfoSignature(updateInfo, signature.decodeToString(), publicKey, log)) {
            return UpdateCheck.Failed(UpdateCheckFailure.SIGNATURE_UNVERIFIED)
        }
        val release = releaseOf(updateInfo) ?: return UpdateCheck.Failed(UpdateCheckFailure.UNREADABLE)
        return if (release.versionCode > currentVersionCode) {
            UpdateCheck.Available(release.versionName, release.sha256)
        } else {
            UpdateCheck.UpToDate
        }
    }

    /** [url] の中身を受け取る。受け取れなければ、[subject] を取れなかったことをログに書いて null。 */
    private suspend fun download(url: String, subject: String): ByteArray? =
        try {
            httpClient.downloadBytes(url, MAX_UPDATE_INFO_BYTES)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            log("${subject}を取れなかった: ${describeThrowable(e)}")
            null
        }

    /** 署名を確かめた更新情報 [updateInfo] の、[platform] の項目。読めないか項目が無ければ、ログに書いて null。 */
    private fun releaseOf(updateInfo: ByteArray): PlatformRelease? {
        val release = try {
            decodePlatformRelease(updateInfo, platform)
        } catch (e: IllegalArgumentException) {
            log("更新情報を読み取れなかった: ${describeThrowable(e)}")
            return null
        }
        if (release == null) log("更新情報に、このプラットフォームの項目（${platform.key}）が無い")
        return release
    }
}
