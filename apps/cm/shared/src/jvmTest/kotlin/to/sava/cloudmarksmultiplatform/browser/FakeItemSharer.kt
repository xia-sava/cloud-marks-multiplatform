package to.sava.cloudmarksmultiplatform.browser

/** 頼まれたことを記録し、決めた結果を返す [ItemSharer]。アプリの一覧を出さない。 */
internal class FakeItemSharer : ItemSharer {
    /** ほかのアプリで開くように頼まれた URL。頼まれた順。 */
    val openedUrls = mutableListOf<String>()

    /** 共有するように頼まれた URL とタイトル。頼まれた順。 */
    val shared = mutableListOf<Pair<String, String>>()

    /** 頼まれたときに返す結果。 */
    var result: ItemShareResult = ItemShareResult.Shown

    override suspend fun openInOtherApp(url: String): ItemShareResult = result.also { openedUrls += url }

    override suspend fun share(url: String, title: String): ItemShareResult = result.also { shared += url to title }
}
