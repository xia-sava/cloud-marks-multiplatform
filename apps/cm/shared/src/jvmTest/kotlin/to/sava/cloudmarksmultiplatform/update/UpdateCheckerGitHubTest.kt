package to.sava.cloudmarksmultiplatform.update

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

/** あれば、実際の GitHub に問い合わせるテストを走らせる環境変数。 */
private const val ENVIRONMENT_VARIABLE = "CMM_UPDATE_IT"

/**
 * 実際の GitHub Releases で、作ったクライアント（CIO）が TLS でつながり、配布中の更新情報の署名が埋め込みの公開鍵で通り、
 * デスクトップと Android の項目を読めることを確かめる。
 *
 * 環境変数 `CMM_UPDATE_IT` があるときだけ走る。無ければ飛ばす。最初のリリースより前は、更新情報が無いので失敗する。
 * 結果とログは標準出力へ出す。
 */
class UpdateCheckerGitHubTest {
    private fun assumeEnvironment() {
        assumeTrue("実際の GitHub に問い合わせる環境変数が無い", !System.getenv(ENVIRONMENT_VARIABLE).isNullOrEmpty())
    }

    // どちらのプラットフォームも、更新情報を取って署名を確かめ、読める（バージョンのコードが 1 なので、新しいバージョンがあると分かる）。
    // 打ち切りの時間を実際の時間で数えるため、runTest ではなく runBlocking で走らせる
    @Test
    fun readsDistributedUpdateInfo() = runBlocking {
        assumeEnvironment()

        UpdatePlatform.entries.forEach { platform ->
            val result = createUpdateHttpClient().use { client ->
                UpdateChecker(client, platform, currentVersionCode = 1, log = { println("$platform: $it") }).check()
            }
            println("$platform: $result")

            assertTrue(result is UpdateCheck.Available, "$platform: $result")
        }
    }
}
