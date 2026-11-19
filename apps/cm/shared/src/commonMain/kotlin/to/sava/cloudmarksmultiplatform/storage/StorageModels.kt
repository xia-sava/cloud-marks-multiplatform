package to.sava.cloudmarksmultiplatform.storage

import kotlin.time.Instant

/** このクライアントが知っている形式のバージョン。 */
const val SUPPORTED_FORMAT_VERSION = 1

internal const val MANIFEST_FORMAT = "cloud-marks-manifest"
internal const val ITEMS_FORMAT = "cloud-marks-items"

/**
 * マニフェストのヘッダー（1 行目）。`format` は定数なので持たない。
 *
 * @property version データを書いた形式のバージョン
 * @property readableBy このデータを読むのに要る最低のバージョン
 * @property commit 書き込みごとに新しく振る UUID
 * @property updatedBy 書き込んだクライアントの種類。読み手は知らない値も受け入れる
 */
data class ManifestHeader(
    val version: Int,
    val readableBy: Int,
    val commit: String,
    val updatedAt: Instant,
    val updatedBy: String,
)

/**
 * マニフェストのフォルダの行（2 行目から）。
 *
 * @property parent 親のフォルダの ID。ルートフォルダだけが null
 * @property items このフォルダの項目ファイルの ID。項目が無ければ null
 * @property count 項目の数。`items` が null のときだけ 0
 */
data class FolderEntry(
    val id: String,
    val parent: String?,
    val name: String,
    val items: String?,
    val count: Int,
)

/** マニフェスト。`folders` はルートフォルダから深さ優先の行きがけ順で、先頭がルートフォルダ。 */
data class Manifest(
    val header: ManifestHeader,
    val folders: List<FolderEntry>,
)

/** 項目ファイルのヘッダー（1 行目）。`format` は定数なので持たない。 */
data class ItemsFileHeader(
    val folder: String,
)

/** 項目ファイルの項目。`added` は取り込んだ項目など、無いことがある。 */
data class Item(
    val id: String,
    val url: String,
    val title: String,
    val added: Instant? = null,
)

/** 項目ファイル。`items` の並びが表示の並び順になる。 */
data class ItemsFile(
    val header: ItemsFileHeader,
    val items: List<Item>,
)
