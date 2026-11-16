package to.sava.cloudmarksmultiplatform.operations

import to.sava.cloudmarksmultiplatform.storage.Item
import to.sava.cloudmarksmultiplatform.storage.truncatedToMilliseconds

/** 操作を適用しなかった理由。 */
enum class NotAppliedReason {
    /** 対象のフォルダまたは項目が無い。 */
    TargetNotFound,

    /** 入れる先・移す先・親・新しい親のフォルダが無い。 */
    DestinationNotFound,

    /** 根は、名前の変更・移動・削除ができない。 */
    RootNotAllowed,

    /** フォルダの新しい親が、対象そのものかその子孫である。 */
    NewParentIsSelfOrDescendant,

    /** フォルダの名前が空である。 */
    EmptyName,
}

/** 操作 1 つの適用の結果。 */
sealed interface OperationStatus {
    /** 適用した。 */
    data object Applied : OperationStatus

    /**
     * 適用済みだった。作るものの ID が既にあった、削除の対象がもう無かった、または対象や項目が空で何もする必要が無かった
     * （項目の移動の対象が空、項目の追加・挿入の項目が空）。利用者に伝えない。
     */
    data object AlreadyApplied : OperationStatus

    /** 適用しなかった。利用者に伝える。 */
    data class NotApplied(val reason: NotAppliedReason) : OperationStatus
}

/**
 * 操作 1 つの結果。
 *
 * @property skippedTargets 項目の移動で、無いために飛ばした対象。[status] が [OperationStatus.Applied] でも持つことがある。
 * 空でなければ利用者に伝える
 */
data class OperationResult(
    val operation: Operation,
    val status: OperationStatus,
    val skippedTargets: List<ItemRef> = emptyList(),
) {
    /** 利用者に伝えるものがあるか。適用しなかった操作、または飛ばした対象がある。 */
    val hasNoticeForUser: Boolean
        get() = status is OperationStatus.NotApplied || skippedTargets.isNotEmpty()
}

/**
 * 操作の並びを適用した結果。
 *
 * @property state 適用後の木の状態
 * @property changedFolderIds 項目の並びが変わったフォルダの ID。適用後に残っているフォルダに限る。
 * 新しく作って項目を入れたフォルダと、項目が無くなったフォルダを含む
 * @property itemsToWrite フォルダのファイルを置くフォルダの ID と、置く項目の並び。[changedFolderIds] のうち、
 * 項目が 1 件以上あるもの（項目が無くなったフォルダは含まない。ファイルを置かず、目録の `items` を null にする）
 * @property treeChanged フォルダの構成・名前・並びが変わったか
 * @property results 操作ごとの結果。入力の順
 * @property unchanged 適用の前後で状態が同じか。同じなら書き込まない
 */
data class ApplyResult(
    val state: TreeState,
    val changedFolderIds: Set<String>,
    val itemsToWrite: Map<String, List<Item>>,
    val treeChanged: Boolean,
    val results: List<OperationResult>,
    val unchanged: Boolean,
)

/**
 * [operations] を [state] へ順に適用する。適用しなかった操作は飛ばして、残りを続ける。
 *
 * 操作の対象のうち既にあるフォルダの項目は、[requiredFolders] のとおり読み込んでおくこと。
 *
 * @throws IllegalStateException 適用に要るフォルダの項目が読み込まれていないとき
 */
fun applyOperations(state: TreeState, operations: List<Operation>): ApplyResult {
    val (finalState, results) = operations.fold(state to emptyList<OperationResult>()) { (current, results), operation ->
        val step = current.applyOperation(operation)
        step.state to results + OperationResult(operation, step.status, step.skippedTargets)
    }
    val changedItems = finalState.items.filter { (folderId, items) -> items != state.items[folderId].orEmpty() }
    return ApplyResult(
        state = finalState,
        changedFolderIds = changedItems.keys,
        itemsToWrite = changedItems.filterValues { it.isNotEmpty() },
        treeChanged = finalState.folders != state.folders,
        results = results,
        unchanged = finalState == state,
    )
}

private class Step(
    val state: TreeState,
    val status: OperationStatus,
    val skippedTargets: List<ItemRef> = emptyList(),
)

private fun TreeState.applyOperation(operation: Operation): Step =
    when (operation) {
        is AddFolder -> addFolder(operation)
        is AddBookmarks -> addBookmarks(operation)
        is DeleteItems -> deleteItems(operation)
        is InsertItems -> insertItems(operation)
        is MoveItems -> moveItems(operation)
        is ChangeItemTitle -> changeItemTitle(operation)
        is CreateFolder -> createFolder(operation)
        is MoveFolder -> moveFolder(operation)
        is RenameFolder -> renameFolder(operation)
        is DeleteFolder -> deleteFolder(operation)
    }

private fun TreeState.notApplied(reason: NotAppliedReason, skippedTargets: List<ItemRef> = emptyList()): Step =
    Step(this, OperationStatus.NotApplied(reason), skippedTargets)

private fun TreeState.alreadyApplied(): Step = Step(this, OperationStatus.AlreadyApplied)

private fun applied(state: TreeState, skippedTargets: List<ItemRef> = emptyList()): Step =
    Step(state, OperationStatus.Applied, skippedTargets)

// 追加

private fun TreeState.addFolder(operation: AddFolder): Step {
    if (operation.folderId in folders) return alreadyApplied()
    if (operation.name.isEmpty()) return notApplied(NotAppliedReason.EmptyName)
    val added = operation.added.truncatedToMilliseconds()
    val newItems = operation.items.distinctBy { it.id }.map { Item(it.id, it.url, it.title, added) }
    return applied(
        withFolderAdded(operation.folderId, rootId, operation.name, before = null)
            .withLoadedItems(operation.folderId, newItems),
    )
}

private fun TreeState.addBookmarks(operation: AddBookmarks): Step {
    val current = itemsOf(rootId)
    val presentIds = current.map { it.id }.toSet()
    val added = operation.added.truncatedToMilliseconds()
    val newItems = operation.items
        .filter { it.id !in presentIds }
        .distinctBy { it.id }
        .map { Item(it.id, it.url, it.title, added) }
    if (newItems.isEmpty()) return alreadyApplied()
    return applied(withLoadedItems(rootId, current + newItems))
}

// 項目

private fun TreeState.deleteItems(operation: DeleteItems): Step {
    val (found, _) = resolve(operation.targets)
    if (found.isEmpty()) return alreadyApplied()
    return applied(withoutItems(found.map { (ref, _) -> ref }.toSet()))
}

private fun TreeState.insertItems(operation: InsertItems): Step {
    if (operation.folderId !in folders) return notApplied(NotAppliedReason.DestinationNotFound)
    val current = itemsOf(operation.folderId)
    val presentIds = current.map { it.id }.toSet()
    val inserted = operation.items.filter { it.id !in presentIds }.distinctBy { it.id }
    if (inserted.isEmpty()) return alreadyApplied()
    return applied(
        withLoadedItems(operation.folderId, current.insertedBefore(inserted, operation.before) { it.id }),
    )
}

private fun TreeState.moveItems(operation: MoveItems): Step {
    if (operation.destinationId !in folders) return notApplied(NotAppliedReason.DestinationNotFound)
    val (found, missing) = resolve(operation.targets)
    if (found.isEmpty()) {
        return if (missing.isEmpty()) alreadyApplied() else notApplied(NotAppliedReason.TargetNotFound, missing)
    }
    val removed = withoutItems(found.map { (ref, _) -> ref }.toSet())
    val destination = removed.itemsOf(operation.destinationId)
    val moved = destination.insertedBefore(found.map { (_, item) -> item }, operation.before) { it.id }
    return applied(removed.withLoadedItems(operation.destinationId, moved), missing)
}

private fun TreeState.changeItemTitle(operation: ChangeItemTitle): Step {
    val ref = operation.target
    if (ref.folderId !in folders) return notApplied(NotAppliedReason.TargetNotFound)
    val current = itemsOf(ref.folderId)
    if (current.none { it.id == ref.itemId }) return notApplied(NotAppliedReason.TargetNotFound)
    return applied(
        withLoadedItems(
            ref.folderId,
            current.map { if (it.id == ref.itemId) it.copy(title = operation.title) else it },
        ),
    )
}

// フォルダ

private fun TreeState.createFolder(operation: CreateFolder): Step {
    if (operation.folderId in folders) return alreadyApplied()
    if (operation.name.isEmpty()) return notApplied(NotAppliedReason.EmptyName)
    if (operation.parentId !in folders) return notApplied(NotAppliedReason.DestinationNotFound)
    return applied(
        withFolderAdded(operation.folderId, operation.parentId, operation.name, operation.before)
            .withLoadedItems(operation.folderId, emptyList()),
    )
}

private fun TreeState.moveFolder(operation: MoveFolder): Step {
    val target = folders[operation.folderId] ?: return notApplied(NotAppliedReason.TargetNotFound)
    if (operation.newParentId !in folders) return notApplied(NotAppliedReason.DestinationNotFound)
    if (target.id == rootId) return notApplied(NotAppliedReason.RootNotAllowed)
    if (operation.newParentId in descendantsAndSelf(target.id)) {
        return notApplied(NotAppliedReason.NewParentIsSelfOrDescendant)
    }
    val oldParentId = checkNotNull(target.parent) { "根以外のフォルダは親を持つ" }
    val detached = withChildren(oldParentId) { it - target.id }
    val attached = detached.withChildren(operation.newParentId) {
        it.insertedBefore(listOf(target.id), operation.before) { id -> id }
    }
    return applied(
        attached.copy(folders = attached.folders + (target.id to target.copy(parent = operation.newParentId))),
    )
}

private fun TreeState.renameFolder(operation: RenameFolder): Step {
    val target = folders[operation.folderId] ?: return notApplied(NotAppliedReason.TargetNotFound)
    if (target.id == rootId) return notApplied(NotAppliedReason.RootNotAllowed)
    if (operation.name.isEmpty()) return notApplied(NotAppliedReason.EmptyName)
    return applied(copy(folders = folders + (target.id to target.copy(name = operation.name))))
}

private fun TreeState.deleteFolder(operation: DeleteFolder): Step {
    val target = folders[operation.folderId] ?: return alreadyApplied()
    if (target.id == rootId) return notApplied(NotAppliedReason.RootNotAllowed)
    val parentId = checkNotNull(target.parent) { "根以外のフォルダは親を持つ" }
    val removedIds = descendantsAndSelf(target.id)
    val detached = withChildren(parentId) { it - target.id }
    return applied(detached.copy(folders = detached.folders - removedIds, items = detached.items - removedIds))
}

// 木の状態の操作

/** 項目を読み込んでいなければ、プログラムの誤りとして投げる。 */
private fun TreeState.itemsOf(folderId: String): List<Item> =
    checkNotNull(items[folderId]) { "フォルダ $folderId の項目が読み込まれていない" }

private fun TreeState.descendantsAndSelf(folderId: String): Set<String> =
    folders.getValue(folderId).children.fold(setOf(folderId)) { acc, child -> acc + descendantsAndSelf(child) }

private fun TreeState.withChildren(folderId: String, transform: (List<String>) -> List<String>): TreeState =
    folders.getValue(folderId).let { node ->
        copy(folders = folders + (folderId to node.copy(children = transform(node.children))))
    }

/** [parentId] の子のフォルダの [before] の前に、項目を持たないフォルダ [id] を足す。 */
private fun TreeState.withFolderAdded(id: String, parentId: String, name: String, before: String?): TreeState =
    withChildren(parentId) { it.insertedBefore(listOf(id), before) { child -> child } }
        .let { it.copy(folders = it.folders + (id to FolderNode(id, parentId, name, emptyList(), null, 0))) }

private fun TreeState.withoutItems(refs: Set<ItemRef>): TreeState =
    copy(items = items.mapValues { (folderId, list) -> list.filterNot { ItemRef(folderId, it.id) in refs } })

/**
 * 対象を、見つかったものと見つからなかったものに分ける。入力の順を保ち、同じ対象は 1 つにする。
 * 木にあるフォルダの項目は読み込んでおく必要がある。
 */
private fun TreeState.resolve(targets: List<ItemRef>): Pair<List<Pair<ItemRef, Item>>, List<ItemRef>> {
    val resolved = targets.distinct().map { ref ->
        ref to ref.folderId.takeIf { it in folders }?.let { folderId ->
            itemsOf(folderId).firstOrNull { it.id == ref.itemId }
        }
    }
    val found = resolved.mapNotNull { (ref, item) -> item?.let { ref to it } }
    val missing = resolved.filter { (_, item) -> item == null }.map { (ref, _) -> ref }
    return found to missing
}

/** [beforeId] の要素の前に [inserted] を入れる。[beforeId] が null または並びに無ければ末尾に入れる。 */
private fun <T> List<T>.insertedBefore(inserted: List<T>, beforeId: String?, idOf: (T) -> String): List<T> {
    val index = indexOfFirst { idOf(it) == beforeId }.let { if (it < 0) size else it }
    return take(index) + inserted + drop(index)
}
