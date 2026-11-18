package to.sava.cloudmarksmultiplatform.remote

import kotlin.time.Instant

/**
 * オブジェクトのストレージ。S3 のバケットのキーを、条件を付けて読み書きする。
 *
 * ETag は不透明な値として扱う。応答の値を引用符ごとそのまま返し、条件にもそのまま渡す。
 * 失敗は例外にせず結果の値で返す。キャンセルだけは例外のまま伝える。
 */
interface ObjectStore {
    /** キーの前に付けるプレフィックス。前後の `/` を除いたもの。空なら、キーはバケットの直下から始まる。 */
    val prefix: String

    /**
     * [key] を GET する。
     *
     * @param ifNoneMatch 与えると `If-None-Match` に付ける。いまの ETag と一致すれば [GetObjectResult.NotModified] になる
     */
    suspend fun get(key: String, ifNoneMatch: String? = null): GetObjectResult

    /**
     * [key] のバージョン [versionId] を GET する。条件は付けない。
     *
     * 結果は [get] と同じ型で、[GetObjectResult.NotModified] にはならない。無いバージョンは [GetObjectResult.NotFound]。
     * 削除マーカーのバージョンは、S3 が `405` を返すので [GetObjectResult.Failed]。
     */
    suspend fun getVersion(key: String, versionId: String): GetObjectResult

    /** [key] へ [body] を、`Content-Type` を [contentType] にし、[condition] を付けて PUT する。`Content-Encoding` は付けない。 */
    suspend fun put(key: String, body: ByteArray, contentType: String, condition: PutCondition): PutObjectResult

    /**
     * キーが [keyPrefix] で始まるオブジェクトを、ListObjectsV2 で最後のページまで読んで返す。
     *
     * 返す時刻は、一覧の応答の `Date`。端末の時計は使わない。
     */
    suspend fun list(keyPrefix: String): ListObjectsResult

    /**
     * キーが [key] に厳密に一致するオブジェクトのバージョンと削除マーカーを、ListObjectVersions で最後のページまで読んで返す。
     *
     * バージョニングを有効にしていないバケットでは、いまのオブジェクトだけが versionId `"null"` で返る。
     */
    suspend fun listVersions(key: String): ListVersionsResult

    /**
     * [key] を、条件を付けずに DELETE する。無いキーの削除は成功とする（S3 は `204` を返す）。
     */
    suspend fun delete(key: String): DeleteObjectResult
}

/** PUT に付ける条件。 */
sealed interface PutCondition {
    /** `If-Match: <ETag>`。いまのオブジェクトの ETag が [eTag] のときだけ書く。 */
    data class IfMatch(val eTag: String) : PutCondition

    /** `If-None-Match: *`。オブジェクトが無いときだけ書く。 */
    data object IfNoneMatchAny : PutCondition
}

/**
 * GET の結果。
 *
 * 失敗の `detail` は後から調べるための短い文で、秘密情報を含まない。
 */
sealed interface GetObjectResult {
    /** 見つかった（`200`）。 */
    class Found(val body: ByteArray, val eTag: String) : GetObjectResult

    /** `If-None-Match` の ETag が、いまの ETag と一致した（`304`）。 */
    data object NotModified : GetObjectResult

    /** 無い（`404`）。 */
    data object NotFound : GetObjectResult

    /** 権限が無い（`403`）。 */
    data object Forbidden : GetObjectResult

    /** 結果が不明。通信の失敗、タイムアウト、`5xx`（`503 SlowDown` を含む）。 */
    data class Unknown(val detail: String) : GetObjectResult

    /** そのほかの失敗。 */
    data class Failed(val detail: String) : GetObjectResult
}

/**
 * PUT の結果。
 *
 * 失敗の `detail` は後から調べるための短い文で、秘密情報を含まない。
 */
sealed interface PutObjectResult {
    /** 書けた（`200`）。[eTag] は書いたオブジェクトの ETag。 */
    data class Success(val eTag: String) : PutObjectResult

    /** 条件に合わず、書かなかった（`412`）。 */
    data object PreconditionFailed : PutObjectResult

    /** 同時に走った書き込みと衝突し、書かなかった（`409`）。 */
    data object Conflict : PutObjectResult

    /** 無い（`404`）。`If-Match` を付けた PUT で、オブジェクトが無いとき。 */
    data object NotFound : PutObjectResult

    /** 権限が無い（`403`）。 */
    data object Forbidden : PutObjectResult

    /** 結果が不明。書けたかどうかが分からない。通信の失敗、タイムアウト、`5xx`（`503 SlowDown` を含む）。 */
    data class Unknown(val detail: String) : PutObjectResult

    /** そのほかの失敗。書いていない。 */
    data class Failed(val detail: String) : PutObjectResult
}

/** 一覧に出たオブジェクト。 */
data class ListedObject(val key: String, val lastModified: Instant)

/**
 * 一覧の結果。
 *
 * 失敗の `detail` は後から調べるための短い文で、秘密情報を含まない。
 */
sealed interface ListObjectsResult {
    /**
     * 最後のページまで読めた。
     *
     * @property objects 読んだ順（S3 ではキーの昇順）
     * @property serverTime 一覧の応答の `Date`（秒の精度）。複数のページがあれば最初のページのもの。
     * 各オブジェクトの `LastModified` との差で経過を測る
     */
    data class Listed(val objects: List<ListedObject>, val serverTime: Instant) : ListObjectsResult

    /** 権限が無い（`403`）。 */
    data object Forbidden : ListObjectsResult

    /** 結果が不明。通信の失敗、タイムアウト、`5xx`（`503 SlowDown` を含む）。 */
    data class Unknown(val detail: String) : ListObjectsResult

    /** そのほかの失敗。 */
    data class Failed(val detail: String) : ListObjectsResult
}

/**
 * 一覧に出たオブジェクトのバージョン、または削除マーカー。
 *
 * @property versionId バージョニングを有効にしていないバケットのオブジェクトでは `"null"`
 * @property isLatest いまのバージョン（そのキーの最新のもの）か
 * @property isDeleteMarker 削除マーカーか。真なら、このバージョンは [ObjectStore.getVersion] で読めない
 */
data class ListedVersion(val versionId: String, val lastModified: Instant, val isLatest: Boolean, val isDeleteMarker: Boolean)

/**
 * バージョンの一覧の結果。
 *
 * 失敗の `detail` は後から調べるための短い文で、秘密情報を含まない。
 */
sealed interface ListVersionsResult {
    /**
     * 最後のページまで読めた。
     *
     * @property versions バージョン・削除マーカーそれぞれの中では S3 の返した順（新しい順）。両者のあいだの順は決めない
     */
    data class Listed(val versions: List<ListedVersion>) : ListVersionsResult

    /** 権限が無い（`403`）。 */
    data object Forbidden : ListVersionsResult

    /** 結果が不明。通信の失敗、タイムアウト、`5xx`（`503 SlowDown` を含む）。 */
    data class Unknown(val detail: String) : ListVersionsResult

    /** そのほかの失敗。 */
    data class Failed(val detail: String) : ListVersionsResult
}

/**
 * DELETE の結果。
 *
 * 失敗の `detail` は後から調べるための短い文で、秘密情報を含まない。
 */
sealed interface DeleteObjectResult {
    /** 消せた、または元から無かった（`204`）。 */
    data object Success : DeleteObjectResult

    /** 権限が無い（`403`）。 */
    data object Forbidden : DeleteObjectResult

    /** 結果が不明。消せたかどうかが分からない。通信の失敗、タイムアウト、`5xx`（`503 SlowDown` を含む）。 */
    data class Unknown(val detail: String) : DeleteObjectResult

    /** そのほかの失敗。消していない。 */
    data class Failed(val detail: String) : DeleteObjectResult
}
