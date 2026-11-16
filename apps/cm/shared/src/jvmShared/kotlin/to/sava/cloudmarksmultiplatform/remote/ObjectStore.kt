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

    /** [key] へ [body] を、`Content-Type` を [contentType] にし、[condition] を付けて PUT する。`Content-Encoding` は付けない。 */
    suspend fun put(key: String, body: ByteArray, contentType: String, condition: PutCondition): PutObjectResult

    /**
     * キーが [keyPrefix] で始まるオブジェクトを、ListObjectsV2 で最後のページまで読んで返す。
     *
     * 返す時刻は、一覧の応答の `Date`。端末の時計は使わない。
     */
    suspend fun list(keyPrefix: String): ListObjectsResult

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
 * 失敗の `detail` は後から追うための短い文で、秘密情報を含まない。
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

    /** 結果が分からない。通信の失敗、タイムアウト、`5xx`（`503 SlowDown` を含む）。 */
    data class Unknown(val detail: String) : GetObjectResult

    /** そのほかの失敗。 */
    data class Failed(val detail: String) : GetObjectResult
}

/**
 * PUT の結果。
 *
 * 失敗の `detail` は後から追うための短い文で、秘密情報を含まない。
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

    /** 結果が分からない。書けたかどうかが分からない。通信の失敗、タイムアウト、`5xx`（`503 SlowDown` を含む）。 */
    data class Unknown(val detail: String) : PutObjectResult

    /** そのほかの失敗。書いていない。 */
    data class Failed(val detail: String) : PutObjectResult
}

/** 一覧に出たオブジェクト。 */
data class ListedObject(val key: String, val lastModified: Instant)

/**
 * 一覧の結果。
 *
 * 失敗の `detail` は後から追うための短い文で、秘密情報を含まない。
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

    /** 結果が分からない。通信の失敗、タイムアウト、`5xx`（`503 SlowDown` を含む）。 */
    data class Unknown(val detail: String) : ListObjectsResult

    /** そのほかの失敗。 */
    data class Failed(val detail: String) : ListObjectsResult
}

/**
 * DELETE の結果。
 *
 * 失敗の `detail` は後から追うための短い文で、秘密情報を含まない。
 */
sealed interface DeleteObjectResult {
    /** 消せた、または元から無かった（`204`）。 */
    data object Success : DeleteObjectResult

    /** 権限が無い（`403`）。 */
    data object Forbidden : DeleteObjectResult

    /** 結果が分からない。消せたかどうかが分からない。通信の失敗、タイムアウト、`5xx`（`503 SlowDown` を含む）。 */
    data class Unknown(val detail: String) : DeleteObjectResult

    /** そのほかの失敗。消していない。 */
    data class Failed(val detail: String) : DeleteObjectResult
}
