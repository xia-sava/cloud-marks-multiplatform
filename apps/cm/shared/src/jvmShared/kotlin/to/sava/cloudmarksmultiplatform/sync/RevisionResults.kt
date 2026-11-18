package to.sava.cloudmarksmultiplatform.sync

import to.sava.cloudmarksmultiplatform.storage.Manifest
import to.sava.cloudmarksmultiplatform.storage.StorageProblem
import kotlin.time.Instant

/**
 * 戻せる時点。マニフェストの古いバージョンの 1 つ。
 *
 * @property versionId マニフェストのバージョンの ID
 * @property lastModified そのバージョンの `LastModified`（S3 の時刻）
 */
data class Revision(val versionId: String, val lastModified: Instant)

/** 読んで検証した、戻せる時点のマニフェスト。 */
data class LoadedRevision(val revision: Revision, val manifest: Manifest)

/**
 * マニフェストが表すフォルダと項目の数。
 *
 * @property folders フォルダの数。ルートフォルダを除く
 * @property items 項目の数。各フォルダの `count` の合計
 */
data class ManifestCounts(val folders: Int, val items: Int)

/** このマニフェストのフォルダと項目の数。 */
fun Manifest.counts(): ManifestCounts = ManifestCounts(folders = folders.size - 1, items = folders.sumOf { it.count })

/** 戻せる時点を読めなかった、またはその時点へ戻せなかった失敗の種類。 */
sealed interface RevisionFailure {
    /** S3 への読み書きの失敗。戻すときに、いまのマニフェストが読み書きできるものでなかったときを含む。 */
    data class Sync(val failure: SyncFailure) : RevisionFailure

    /** その時点のマニフェストか、それが指す項目ファイルの、どのバージョンも残っていない。 */
    data object Incomplete : RevisionFailure

    /** その時点のマニフェストか、それが指す項目ファイルが壊れている。 */
    data class Corrupt(val problem: StorageProblem) : RevisionFailure

    /** その時点のマニフェストの `readableBy` か `version` が、知っている版より新しい。アプリの更新が要る。 */
    data object UpdateRequired : RevisionFailure
}

/** 戻せる時点の一覧を読んだ結果。 */
sealed interface RevisionListResult {
    /** 読めた。[revisions] は `LastModified` の新しい順。 */
    data class Listed(val revisions: List<Revision>) : RevisionListResult

    /** 読めなかった。 */
    data class Failed(val failure: SyncFailure) : RevisionListResult
}

/** 戻せる時点のマニフェストを読んだ結果。 */
sealed interface RevisionReadResult {
    /** 読めた。 */
    data class Loaded(val revision: LoadedRevision) : RevisionReadResult

    /** 読めなかった。 */
    data class Failed(val failure: RevisionFailure) : RevisionReadResult
}

/** 戻せる時点の状態へ戻した結果。 */
sealed interface RestoreResult {
    /** 戻せた。いまの状態がその時点と同じで、PUT しなかったときも含む。[tree] は戻したあとのマニフェストとツリー。 */
    data class Success(val tree: LoadedTree) : RestoreResult

    /** 戻せなかった。届いたかどうかが分からないマニフェストの PUT を残さずに終わった。 */
    data class Failed(val failure: RevisionFailure) : RestoreResult

    /** 戻せたかどうかを確かめられなかった（[WriteResult.Unconfirmed] と同じ）。戻せていることもある。 */
    data class Unconfirmed(val failure: SyncFailure) : RestoreResult
}
