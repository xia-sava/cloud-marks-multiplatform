package to.sava.cloudmarksmultiplatform.storage

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val MANIFEST_FILE_NAME = "manifest.jsonl.gz"
private const val FOLDERS_DIRECTORY = "folders"
private const val ITEMS_FILE_SUFFIX = ".jsonl.gz"

/** プレフィックスの前後の `/` を除く。 */
fun normalizePrefix(prefix: String): String = prefix.trim('/')

/** マニフェストのキー。プレフィックスが空なら `manifest.jsonl.gz` から始まる。 */
fun manifestKey(prefix: String): String = joinKey(prefix, MANIFEST_FILE_NAME)

/** 項目ファイルのキー。 */
fun itemsFileKey(prefix: String, folderId: String, fileId: String): String =
    joinKey(prefix, "$FOLDERS_DIRECTORY/$folderId/$fileId$ITEMS_FILE_SUFFIX")

/** [manifest] のどのフォルダの `<id>/<items>` の組も、キーにして返す。ファイルの ID だけでは比べない。 */
fun referencedItemsFileKeys(prefix: String, manifest: Manifest): Set<String> =
    manifest.folders
        .mapNotNull { folder -> folder.items?.let { itemsFileKey(prefix, folder.id, it) } }
        .toSet()

/** すべての項目ファイルのキーが共通で始まる部分（`<プレフィックス>/folders/`）。一覧のプレフィックスに使う。 */
fun foldersKeyPrefix(prefix: String): String = joinKey(prefix, "$FOLDERS_DIRECTORY/")

private fun joinKey(prefix: String, relativeKey: String): String =
    normalizePrefix(prefix).let { normalized ->
        if (normalized.isEmpty()) relativeKey else "$normalized/$relativeKey"
    }

/** 新しい ID（乱数の UUID。小文字・ハイフン区切りの 36 文字）を振る。 */
@OptIn(ExperimentalUuidApi::class)
fun newId(): String = Uuid.random().toString()
