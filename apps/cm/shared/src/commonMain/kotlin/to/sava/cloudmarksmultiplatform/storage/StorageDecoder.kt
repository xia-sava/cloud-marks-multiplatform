package to.sava.cloudmarksmultiplatform.storage

private const val FIRST_BODY_LINE = 2

/**
 * JSONL の文字列をマニフェストとして読み、検証する。
 *
 * ヘッダーを `format`、`version` と `readableBy`、残りの欄の順に確かめる。`readableBy` が [knownVersion] より新しければ、
 * 残りは確かめず [ManifestDecodeResult.UpdateRequired] を返す。`version` が新しいときにほかの検証で失敗した場合も、
 * [ManifestDecodeResult.Corrupt] ではなく [ManifestDecodeResult.UpdateRequired] を返す。
 *
 * @param knownVersion このクライアントが知っている形式のバージョン
 */
fun decodeManifest(
    text: String,
    knownVersion: Int = SUPPORTED_FORMAT_VERSION,
): ManifestDecodeResult {
    val rawLines = text.split('\n')
    val head = try {
        readManifestVersions(readHeaderLine(rawLines))
    } catch (e: StorageProblemException) {
        return ManifestDecodeResult.Corrupt(e.problem)
    }
    if (head.readableBy > knownVersion) {
        return ManifestDecodeResult.UpdateRequired(head.version, head.readableBy, cause = null)
    }
    val isNewer = head.version > knownVersion
    val manifest = try {
        readManifest(head, rawLines)
    } catch (e: StorageProblemException) {
        return if (isNewer) {
            ManifestDecodeResult.UpdateRequired(head.version, head.readableBy, e.problem)
        } else {
            ManifestDecodeResult.Corrupt(e.problem)
        }
    }
    return if (isNewer) ManifestDecodeResult.ReadOnly(manifest) else ManifestDecodeResult.Readable(manifest)
}

/**
 * JSONL の文字列を項目ファイルとして読み、検証する。
 *
 * @param keyFolderId キー `folders/<フォルダの ID>/...` のフォルダの ID
 * @param folder このファイルを指している、マニフェストのフォルダの行。`id` と `count` を確かめる
 * @param manifestVersion マニフェストの `version`。知っているバージョンより新しく、検証に失敗したときは
 *   [ItemsFileDecodeResult.Corrupt] ではなく [ItemsFileDecodeResult.UpdateRequired] を返す
 * @param knownVersion このクライアントが知っている形式のバージョン
 */
fun decodeItemsFile(
    text: String,
    keyFolderId: String,
    folder: FolderEntry,
    manifestVersion: Int,
    knownVersion: Int = SUPPORTED_FORMAT_VERSION,
): ItemsFileDecodeResult =
    try {
        ItemsFileDecodeResult.Readable(readItemsFile(text.split('\n'), keyFolderId, folder))
    } catch (e: StorageProblemException) {
        itemsFileFailure(e.problem, manifestVersion, knownVersion)
    }

private class ManifestVersions(val header: JsonlLine, val version: Int, val readableBy: Int)

/** ヘッダーの `format`、`version`、`readableBy` を確かめる。 */
private fun readManifestVersions(header: JsonlLine): ManifestVersions {
    header.requireFormat(MANIFEST_FORMAT)
    val version = header.integer("version")
    val readableBy = header.integer("readableBy")
    if (version < 1) header.fail("`version` が 1 未満: $version")
    if (readableBy < 1) header.fail("`readableBy` が 1 未満: $readableBy")
    if (readableBy > version) header.fail("`readableBy`($readableBy) が `version`($version) より大きい")
    return ManifestVersions(header, version, readableBy)
}

private fun readManifest(head: ManifestVersions, rawLines: List<String>): Manifest {
    val header = ManifestHeader(
        version = head.version,
        readableBy = head.readableBy,
        commit = head.header.uuid("commit"),
        updatedAt = head.header.instant("updatedAt"),
        updatedBy = head.header.string("updatedBy"),
    )
    val folders = readBodyLines(rawLines).map(::readFolderEntry)
    checkFolderTree(folders)
    return Manifest(header, folders)
}

private fun readFolderEntry(line: JsonlLine): FolderEntry {
    val entry = FolderEntry(
        id = line.uuid("id"),
        parent = line.nullableUuid("parent"),
        name = line.string("name"),
        items = line.nullableUuid("items"),
        count = line.integer("count"),
    )
    if (entry.count < 0) line.fail("`count` が負: ${entry.count}")
    if ((entry.items == null) != (entry.count == 0)) {
        line.fail("`items` が null であることと `count` が 0 であることが揃っていない")
    }
    return entry
}

/**
 * フォルダの行の並びを確かめる。`folders` は 2 行目から後ろの行を、`id` と `parent` を読んだ形で持つ。
 *
 * 2 行目がルートフォルダ（`parent` が null で名前が空）で、3 行目からの各行の `parent` が、
 * 直前の行から `parent` を辿ってルートフォルダへ向かう途中にあるフォルダ（直前の行を含む）の `id` であること。
 * 辿った経路をスタックで持ち、1 回の走査で、親が先にあること・循環が無いこと・行きがけ順であることを確かめる。
 */
private fun checkFolderTree(folders: List<FolderEntry>) {
    val root = folders.firstOrNull() ?: throwStorageProblem(FIRST_BODY_LINE, "ルートフォルダの行が無い")
    if (root.parent != null) throwStorageProblem(FIRST_BODY_LINE, "ルートフォルダの `parent` が null でない")
    if (root.name.isNotEmpty()) throwStorageProblem(FIRST_BODY_LINE, "ルートフォルダの `name` が空でない")

    val seenIds = hashSetOf(root.id)
    val pathFromRoot = arrayListOf(root.id)
    folders.drop(1).forEachIndexed { index, folder ->
        val number = index + FIRST_BODY_LINE + 1
        if (!seenIds.add(folder.id)) throwStorageProblem(number, "`id` が重複している: ${folder.id}")
        if (folder.name.isEmpty()) throwStorageProblem(number, "`name` が空")
        val parentIndex = pathFromRoot.lastIndexOf(folder.parent ?: throwStorageProblem(number, "`parent` が null"))
        if (parentIndex < 0) {
            throwStorageProblem(number, "`parent` が、直前の行からルートフォルダへ向かう途中のフォルダの `id` でない: ${folder.parent}")
        }
        pathFromRoot.subList(parentIndex + 1, pathFromRoot.size).clear()
        pathFromRoot.add(folder.id)
    }
}

private fun readItemsFile(rawLines: List<String>, keyFolderId: String, folder: FolderEntry): ItemsFile {
    val header = readHeaderLine(rawLines)
    header.requireFormat(ITEMS_FORMAT)
    val folderId = header.string("folder")
    if (folderId != keyFolderId) header.fail("`folder` がキーのフォルダの ID と一致しない: $folderId")
    if (folderId != folder.id) header.fail("`folder` がマニフェストのフォルダの `id` と一致しない: $folderId")

    val items = readBodyLines(rawLines).map(::readItem)
    val seenIds = hashSetOf<String>()
    items.forEachIndexed { index, item ->
        if (!seenIds.add(item.id)) throwStorageProblem(index + FIRST_BODY_LINE, "`id` が重複している: ${item.id}")
    }
    if (items.size != folder.count) {
        throwStorageProblem(null,"項目の行の数(${items.size})がマニフェストの `count`(${folder.count}) と一致しない")
    }
    return ItemsFile(ItemsFileHeader(folderId), items)
}

private fun readItem(line: JsonlLine): Item {
    val item = Item(
        id = line.uuid("id"),
        url = line.string("url"),
        title = line.string("title"),
        added = line.optionalInstant("added"),
    )
    if (item.url.isEmpty()) line.fail("`url` が空")
    return item
}
