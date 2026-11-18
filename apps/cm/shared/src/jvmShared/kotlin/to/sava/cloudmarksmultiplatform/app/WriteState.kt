package to.sava.cloudmarksmultiplatform.app

import to.sava.cloudmarksmultiplatform.operations.InsertItems
import to.sava.cloudmarksmultiplatform.operations.ItemRef
import to.sava.cloudmarksmultiplatform.operations.OperationResult
import to.sava.cloudmarksmultiplatform.storage.Item
import to.sava.cloudmarksmultiplatform.sync.SyncFailure
import to.sava.cloudmarksmultiplatform.sync.WriteResult
import kotlin.time.Instant

/**
 * データを変える操作の、書き込みの進み具合と結果。
 *
 * @property writing 操作を書いている最中か。そのあいだは、ほかの操作も読み直しも受け付けない
 * @property outcome 直前の操作の結果。まだ無いか、閉じたか、そのあと読み直すボタンなどでマニフェストを読み直せたら null。
 * S3 から読み直すきっかけの知らせで読み直したときは消さない
 * @property undoableDelete 元に戻せる、直前の項目の削除。無ければ null。ほかの操作を書き始めたときと、
 * 元に戻すお知らせを閉じたときに null にする。元に戻す書き込みが書けなかったか確かめられなかったときは、同じものを残す。
 * 溜めた削除（[UndoableDelete.pending]）を元に戻したら null にする
 */
data class WriteState(
    val writing: Boolean = false,
    val outcome: WriteOutcome? = null,
    val undoableDelete: UndoableDelete? = null,
)

/** データを変える操作の結果。 */
sealed interface WriteOutcome {
    /** 書けた。適用しなかった操作も、飛ばした対象も無い。 */
    data object Written : WriteOutcome

    /**
     * 書けたが、適用しなかった操作か、飛ばした対象がある。
     *
     * @property results 利用者に伝えるものがある操作の結果（[OperationResult.hasNoticeForUser]）。入力の順
     */
    data class NotApplied(val results: List<OperationResult>) : WriteOutcome

    /** 書けたかどうかを確かめられなかった。書き換えが済んでいることもある。ツリーは書く前のまま。 */
    data class Unconfirmed(val failure: SyncFailure) : WriteOutcome

    /** 書けなかった。ツリーは書く前のまま。 */
    data class Failed(val failure: SyncFailure) : WriteOutcome

    /** 以前の状態に戻せた。[lastModified] は、戻した時点のマニフェストのバージョンの `LastModified`。 */
    data class Restored(val lastModified: Instant) : WriteOutcome
}

/**
 * 元に戻せる、直前の項目の削除。
 *
 * @property restore 消した項目を元の位置へ戻す、項目の挿入
 * @property pendingTargets S3 につながらないために溜めた削除なら、そのとき溜めた対象。元に戻すときは、これを溜めた削除から外し、
 * S3 には書かない。S3 に書けた削除なら null
 */
data class UndoableDelete(val restore: List<InsertItems>, val pendingTargets: Set<ItemRef>? = null) {
    /** 消した項目の数。 */
    val count: Int get() = restore.sumOf { it.items.size }

    /** 溜めた削除か。 */
    val pending: Boolean get() = pendingTargets != null

    /**
     * 溜めた削除のうち [applied] を S3 に書けたあとの、元に戻せる削除。この削除で溜めた対象がすべて [applied] に含まれていれば、
     * S3 に書けた削除（項目の挿入で戻すもの）にする。そうでなければ、このまま。
     */
    fun afterApplying(applied: Set<ItemRef>): UndoableDelete =
        if (pendingTargets != null && applied.containsAll(pendingTargets)) copy(pendingTargets = null) else this
}

/**
 * 書き込みの結果 [result] が、S3 につながらないための失敗か。つながらない失敗と、結果が不明のまま終わったもの
 * （[SyncFailure.Unreachable]。書けなかったときも、書けたかどうかを確かめられなかったときも）が当たる。
 * 項目の削除の書き込みがこれに当たれば、その削除を溜める。
 */
internal fun isUnreachable(result: WriteResult): Boolean =
    when (result) {
        is WriteResult.Success -> false
        is WriteResult.Failed -> result.failure is SyncFailure.Unreachable
        is WriteResult.Unconfirmed -> result.failure is SyncFailure.Unreachable
    }

/**
 * フォルダ [folderId] の項目 [itemsBefore]（消す前の並び）から [deletedIds] の項目を消したあとで、消した項目を元の位置へ戻す項目の挿入。
 *
 * 消した項目が続くところは、まとめて 1 つの挿入にする。位置は、消す前の並びでそのすぐ後ろにあって消さなかった項目の前とし、
 * 後ろに残る項目が無ければ指定しない（末尾）。挿入は消す前の並びの順に並べる。
 */
internal fun restoringInsertions(folderId: String, itemsBefore: List<Item>, deletedIds: Set<String>): List<InsertItems> =
    buildList {
        var run = emptyList<Item>()
        for (item in itemsBefore) {
            if (item.id in deletedIds) {
                run = run + item
            } else if (run.isNotEmpty()) {
                add(InsertItems(folderId, run, before = item.id))
                run = emptyList()
            }
        }
        if (run.isNotEmpty()) add(InsertItems(folderId, run, before = null))
    }
