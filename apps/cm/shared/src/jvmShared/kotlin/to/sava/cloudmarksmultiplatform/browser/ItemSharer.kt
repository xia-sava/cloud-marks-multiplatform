package to.sava.cloudmarksmultiplatform.browser

/** 項目をほかのアプリで開くか共有しようとした結果。 */
sealed interface ItemShareResult {
    /** 受け取るアプリの一覧を出した。 */
    data object Shown : ItemShareResult

    /** 受け取れるアプリが無い。 */
    data object NoApp : ItemShareResult

    /** ほかの理由で、受け取るアプリの一覧を出せなかった。理由はログにだけ書く。 */
    data object Failed : ItemShareResult
}

/**
 * 項目をほかのアプリで開く仕組みと、共有する仕組み。どちらも、受け取るアプリの一覧を出し、利用者が選ぶ。
 * 持つプラットフォームだけが作り、`createCloudMarksController` に渡す。
 * どの関数も、ブロックする処理は自分で I/O 用のスレッドへ移す。
 */
interface ItemSharer {
    /** [url] を、開けるアプリの一覧を出して開く。http と https の URL だけを開く。 */
    suspend fun openInOtherApp(url: String): ItemShareResult

    /** URL [url] を、タイトル [title] を添えて、共有する先のアプリの一覧を出して渡す。タイトルは空でもよい。 */
    suspend fun share(url: String, title: String): ItemShareResult
}
