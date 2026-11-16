package to.sava.cloudmarksmultiplatform.browse

import to.sava.cloudmarksmultiplatform.operations.toTreeState
import to.sava.cloudmarksmultiplatform.storage.FolderEntry
import to.sava.cloudmarksmultiplatform.storage.Item
import to.sava.cloudmarksmultiplatform.storage.Manifest
import to.sava.cloudmarksmultiplatform.storage.ManifestHeader
import to.sava.cloudmarksmultiplatform.sync.LoadedTree
import to.sava.cloudmarksmultiplatform.sync.TreeReadResult
import to.sava.cloudmarksmultiplatform.sync.fileId
import to.sava.cloudmarksmultiplatform.sync.folderId
import to.sava.cloudmarksmultiplatform.sync.item
import kotlin.time.Instant

internal val ROOT = folderId(0)
internal val A = folderId(1)
internal val B = folderId(2)
internal val A1 = folderId(3)
internal val A2 = folderId(4)
internal val A1A = folderId(5)

/** A1 の項目。 */
internal val A1_ITEMS: List<Item> = listOf(item(1), item(2))

/**
 * ルートフォルダ → A（→ A1（→ A1A）、A2）、B のマニフェストの行。ルートフォルダから深さ優先の行きがけ順。A1 だけが項目を持つ。
 *
 * @param without 除くフォルダ。その子孫も一緒に除く
 */
internal fun treeEntries(without: Set<String> = emptySet()): List<FolderEntry> {
    val entries = listOf(
        FolderEntry(ROOT, null, "", null, 0),
        FolderEntry(A, ROOT, "A", null, 0),
        FolderEntry(A1, A, "A1", fileId(1), A1_ITEMS.size),
        FolderEntry(A1A, A1, "A1a", null, 0),
        FolderEntry(A2, A, "A2", null, 0),
        FolderEntry(B, ROOT, "B", null, 0),
    )
    val removed = entries.fold(without) { removed, entry -> if (entry.parent in removed) removed + entry.id else removed }
    return entries.filterNot { it.id in removed }
}

/**
 * [entries] のマニフェストを読んだフォルダのツリー。[loadedItems] のフォルダの項目を読み込んである。
 *
 * @param eTag マニフェストの ETag。読み直したフォルダのツリーを別のマニフェストとして扱うときは替える
 */
internal fun loadedTree(
    entries: List<FolderEntry> = treeEntries(),
    eTag: String = "\"etag-1\"",
    loadedItems: Map<String, List<Item>> = emptyMap(),
): LoadedTree {
    val manifest = Manifest(
        header = ManifestHeader(1, 1, folderId(90), Instant.parse("2026-11-01T00:00:00.000Z"), "android"),
        folders = entries,
    )
    val state = loadedItems.entries.fold(manifest.toTreeState()) { state, (folderId, items) -> state.withLoadedItems(folderId, items) }
    return LoadedTree(manifest, eTag, state, emptyMap())
}

/** 初めてフォルダのツリーを読んだあとの状態。ルートフォルダを開いている。 */
internal fun browseWithTree(tree: LoadedTree = loadedTree()): BrowseState =
    BrowseState().startLoading().withTreeRead(TreeReadResult.Readable(tree))
