package to.sava.cloudmarksmultiplatform.operations

import to.sava.cloudmarksmultiplatform.storage.Item
import kotlin.time.Instant

/** 項目を、項目を持つフォルダの ID と項目の ID の組で指す。 */
data class ItemRef(
    val folderId: String,
    val itemId: String,
)

/**
 * 操作が新しく作る項目。ID は操作を組み立てるときに振り、`added` は操作が持つ追加した日時になる。
 * [url] は空でない。
 */
data class NewItem(
    val id: String,
    val url: String,
    val title: String,
) {
    init {
        require(url.isNotEmpty()) { "項目の URL が空" }
    }
}

/**
 * データを変える操作。作るものの ID と追加した日時は操作が持ち、再適用しても変わらない。
 *
 * 位置（`before`）は、どの項目（フォルダ）の前かの ID。null、または並びに無い ID は末尾を表す。
 */
sealed interface Operation

/** 複数の URL を、ルートフォルダの子のフォルダの末尾に作る新しいフォルダにまとめて追加する。[name] は空でない。 */
data class AddFolder(
    val folderId: String,
    val name: String,
    val items: List<NewItem>,
    val added: Instant,
) : Operation

/** URL を、ルートフォルダの項目の末尾に入力の順に追加する。 */
data class AddBookmarks(
    val items: List<NewItem>,
    val added: Instant,
) : Operation

/** 項目を消す。もう無い項目は何もしない。 */
data class DeleteItems(
    val targets: List<ItemRef>,
) : Operation

/** 消した項目を、消す前の値のまま [before] の前に戻す。 */
data class InsertItems(
    val folderId: String,
    val items: List<Item>,
    val before: String?,
) : Operation

/** 項目を、元のフォルダから外して [destinationId] の [before] の前へ入力の順に移す。 */
data class MoveItems(
    val targets: List<ItemRef>,
    val destinationId: String,
    val before: String?,
) : Operation

/** 項目のタイトルを変える。[title] は空でもよい。 */
data class ChangeItemTitle(
    val target: ItemRef,
    val title: String,
) : Operation

/** [parentId] の子のフォルダの [before] の前に、フォルダを作る。[name] は空でない。 */
data class CreateFolder(
    val folderId: String,
    val parentId: String,
    val name: String,
    val before: String?,
) : Operation

/** フォルダを子孫ごと、[newParentId] の子のフォルダの [before] の前へ移す。 */
data class MoveFolder(
    val folderId: String,
    val newParentId: String,
    val before: String?,
) : Operation

/** フォルダの名前を変える。[name] は空でない。 */
data class RenameFolder(
    val folderId: String,
    val name: String,
) : Operation

/** フォルダを、子孫のフォルダとそれらの項目ごと消す。もう無ければ何もしない。 */
data class DeleteFolder(
    val folderId: String,
) : Operation

/**
 * [operation] の適用の前に、項目を読み込んでおく必要があるフォルダの ID。
 *
 * ツリーに無いフォルダは含まない（操作が作るフォルダは、作った時点で項目を読み込んだ状態になる）。
 * 読み込み済みかどうかは見ない（呼び出し側が [TreeState.items] との差を取る）。
 */
fun requiredFolders(state: TreeState, operation: Operation): Set<String> =
    when (operation) {
        is AddFolder, is CreateFolder, is MoveFolder, is RenameFolder, is DeleteFolder -> emptySet()
        is AddBookmarks -> setOf(state.rootId)
        is DeleteItems -> operation.targets.map { it.folderId }
        is InsertItems -> listOf(operation.folderId)
        is MoveItems -> operation.targets.map { it.folderId } + operation.destinationId
        is ChangeItemTitle -> listOf(operation.target.folderId)
    }.filter { it in state.folders }.toSet()

/** [operations] の適用の前に、項目を読み込んでおく必要があるフォルダの ID。 */
fun requiredFolders(state: TreeState, operations: List<Operation>): Set<String> =
    operations.flatMapTo(mutableSetOf()) { requiredFolders(state, it) }
