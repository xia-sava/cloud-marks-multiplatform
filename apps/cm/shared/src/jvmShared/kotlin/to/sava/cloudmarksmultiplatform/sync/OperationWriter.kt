package to.sava.cloudmarksmultiplatform.sync

import to.sava.cloudmarksmultiplatform.operations.Operation
import to.sava.cloudmarksmultiplatform.operations.applyOperations
import to.sava.cloudmarksmultiplatform.operations.requiredFolders
import to.sava.cloudmarksmultiplatform.operations.toFolderEntries
import to.sava.cloudmarksmultiplatform.remote.ObjectStore
import to.sava.cloudmarksmultiplatform.storage.ItemsFile
import to.sava.cloudmarksmultiplatform.storage.ItemsFileHeader
import to.sava.cloudmarksmultiplatform.storage.encodeItemsFileGzip
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * 操作の並びを、書き込みの手順で 1 回の書き込みとして書く。書き込みごとに作る。
 *
 * 手順 2 では、読んだ状態に操作を適用して書く中身を決める。それ以外の手順は [WriteProcedure] が行う。
 *
 * @param updatedBy マニフェストのヘッダーの `updatedBy`
 * @param clock マニフェストのヘッダーの `updatedAt` に使う時計
 * @param timeSource 手順 3 の最初の PUT からの経過を測る、モノトニッククロック
 * @param wait 再送と再適用の前に、与えた長さだけ間を空ける
 */
internal class OperationWriter(
    private val store: ObjectStore,
    private val cache: SyncCache,
    private val reader: TreeReader,
    private val operations: List<Operation>,
    private val updatedBy: String,
    private val clock: Clock,
    private val timeSource: TimeSource,
    private val wait: suspend (Duration) -> Unit,
) {
    suspend fun write(): WriteResult {
        val planner = WritePlanner { manifest, rereadManifest -> plan(manifest, rereadManifest) }
        return when (val result = WriteProcedure(store, cache, reader, updatedBy, clock, timeSource, wait, planner).run()) {
            is WriteProcedureResult.Finished -> result.result
            is WriteProcedureResult.Rejected -> result.reason
        }
    }

    /**
     * 手順 1 の残りと手順 2。操作の適用に要るフォルダの項目を読み、操作を読んだ状態に適用する。
     * 適用した結果が読んだ状態と同じなら、書かない。
     *
     * 項目ファイルを置くのは中身の変わったフォルダで、項目が無くなったフォルダはファイルを置かない。
     * ヘッダーの `version` と `readableBy` は、読んだマニフェストのまま書く。
     */
    private suspend fun plan(manifest: ManifestRead.Usable, rereadManifest: suspend () -> ManifestRead): WritePlan<Nothing> {
        val tree = when (val read = readFoldersForWrite(manifest, rereadManifest)) {
            is StepResult.Failed -> return WritePlan.Failed(read.failure)
            is StepResult.Ok -> read.value
        }
        val applied = applyOperations(tree.state, operations)
        if (applied.unchanged) return WritePlan.Unchanged(tree, applied.results)
        return WritePlan.Change(
            baseETag = tree.eTag,
            version = tree.manifest.header.version,
            readableBy = tree.manifest.header.readableBy,
            files = applied.itemsToWrite.mapValues { (folderId, items) ->
                encodeItemsFileGzip(ItemsFile(ItemsFileHeader(folderId), items))
            },
            foldersOf = { newFileIds -> applied.toFolderEntries(newFileIds) },
            loadedItems = applied.state.items,
            results = applied.results,
        )
    }

    /**
     * 手順 1 の残り。操作の適用に要るフォルダの項目を読む。
     *
     * 項目ファイルが無く、読み直したマニフェストが読むだけにするものなら、失敗にする。操作の適用に要るフォルダの
     * 壊れたファイルも、失敗にする。
     *
     * @param rereadManifest マニフェストを読み直す
     */
    private suspend fun readFoldersForWrite(
        manifest: ManifestRead.Usable,
        rereadManifest: suspend () -> ManifestRead,
    ): StepResult<LoadedTree> {
        val read = reader.readTree(manifest, rereadManifest) { state ->
            requiredFolders(state, operations) - state.items.keys
        }
        val tree = when (read) {
            is TreeReadResult.Readable -> read.tree
            is TreeReadResult.ReadOnly -> return StepResult.Failed(readOnlyFailure(read.tree.manifest))
            is TreeReadResult.Failed -> return StepResult.Failed(read.failure)
        }
        val brokenRequired = tree.brokenFolders.filterKeys { it in requiredFolders(tree.state, operations) }
        return if (brokenRequired.isEmpty()) {
            StepResult.Ok(tree)
        } else {
            StepResult.Failed(SyncFailure.FolderCorrupt(brokenRequired))
        }
    }
}
