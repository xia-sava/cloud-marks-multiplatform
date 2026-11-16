package to.sava.cloudmarksmultiplatform.storage

/**
 * 形式に合わない箇所。
 *
 * @property line 1 から数える行番号。ファイル全体に係る問題では null
 * @property message 何が合わないか
 */
data class StorageProblem(
    val line: Int?,
    val message: String,
) {
    override fun toString(): String = line?.let { "$it 行目: $message" } ?: message
}

/** 目録を読んだ結果。 */
sealed interface ManifestDecodeResult {
    /** 読めた。読み書きできる（`version` が知っている版以下）。 */
    data class Readable(val manifest: Manifest) : ManifestDecodeResult

    /** 読めたが、`version` が知っている版より新しいので読むだけにする。書かず、古いファイルの削除もしない。 */
    data class ReadOnly(val manifest: Manifest) : ManifestDecodeResult

    /**
     * 読めない。アプリの更新が要る。
     *
     * `readableBy` が知っている版より新しいとき、または `version` が新しく、ほかの検証にも失敗したときに返す。
     *
     * @property cause 後者のとき、失敗した検証の内容。前者では null
     */
    data class UpdateRequired(
        val version: Int,
        val readableBy: Int,
        val cause: StorageProblem?,
    ) : ManifestDecodeResult

    /** 壊れている。 */
    data class Corrupt(val problem: StorageProblem) : ManifestDecodeResult
}

/** フォルダのファイルを読んだ結果。 */
sealed interface ItemsFileDecodeResult {
    /** 読めた。 */
    data class Readable(val file: ItemsFile) : ItemsFileDecodeResult

    /** 目録の `version` が知っている版より新しく、検証に失敗した。アプリの更新が要る。 */
    data class UpdateRequired(val cause: StorageProblem) : ItemsFileDecodeResult

    /** 壊れている。 */
    data class Corrupt(val problem: StorageProblem) : ItemsFileDecodeResult
}

/** 失敗を、目録の版に応じて「更新が要る」か「壊れている」に振り分ける。 */
internal fun itemsFileFailure(
    problem: StorageProblem,
    manifestVersion: Int,
    knownVersion: Int,
): ItemsFileDecodeResult =
    if (manifestVersion > knownVersion) {
        ItemsFileDecodeResult.UpdateRequired(problem)
    } else {
        ItemsFileDecodeResult.Corrupt(problem)
    }
