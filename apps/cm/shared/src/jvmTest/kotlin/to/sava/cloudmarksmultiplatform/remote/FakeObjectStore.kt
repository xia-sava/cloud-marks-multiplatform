package to.sava.cloudmarksmultiplatform.remote

import kotlin.time.Instant

/** S3 につながらないあいだ（[FakeObjectStore.unreachable]）に返す、結果が不明の内容。 */
const val UNREACHABLE_DETAIL = "つながらない"

/**
 * メモリ上の [ObjectStore]。S3 と同じく、ETag と条件付きの書き込みを扱う。
 *
 * - `If-Match` の PUT は、オブジェクトが無ければ `404`、ETag が違えば `412`
 * - `If-None-Match: *` の PUT は、オブジェクトがあれば `412`
 * - `If-None-Match` の GET は、ETag が一致すれば `304`
 *
 * [onNextPut]、[onNextGet]、[onNextList]、[onNextDelete] で、次のリクエストに、失敗やほかの書き手の書き込みなどの事象を差し込める。
 * [unreachable] を真にすると、偽に戻すまで S3 につながらないものとして振る舞う。
 *
 * 一覧は、S3 と同じく、[listPageSize] 個のキーごとのページに分けて返し、[listAllPages] で最後まで読む。
 * 一覧の応答の時刻は [serverTime]、オブジェクトの `LastModified` は置いた時点の [serverTime]（[overwrite] では指定できる）。
 * runTest の中で、1 つのスレッドから使う。
 */
class FakeObjectStore(override val prefix: String = "cm/data") : ObjectStore {
    /** 置いてあるオブジェクト。[contentType] は、条件を付けずに置いたものでは null。 */
    class StoredObject(val body: ByteArray, val eTag: String, val contentType: String?, val lastModified: Instant)

    /** 受けたリクエスト。 */
    sealed interface Request {
        /** GET と PUT と DELETE ではキー、一覧ではキーのプレフィックス。 */
        val key: String

        data class Get(override val key: String, val ifNoneMatch: String?) : Request

        /** 一覧の 1 ページのリクエスト。[key] はキーのプレフィックス。 */
        data class ListPage(override val key: String, val continuationToken: String?) : Request

        data class Delete(override val key: String) : Request

        class Put(
            override val key: String,
            val body: ByteArray,
            val contentType: String,
            val condition: PutCondition,
        ) : Request
    }

    /** PUT に差し込む事象。 */
    sealed interface PutFault {
        /** 条件のとおりに扱ったうえで、[afterWrite] を走らせ、結果が不明として返す（応答が途中で切れた）。 */
        class WriteThenUnknown(val afterWrite: suspend () -> Unit = {}) : PutFault

        /** 条件のとおりに扱ったうえで、`412` を返す（SDK が自分で再送し、先に届いていた PUT のために `412` を受けた）。 */
        data object WriteThenPreconditionFailed : PutFault

        /** 書き込まずに、結果が不明として返す（リクエストが届かなかった）。 */
        data object UnknownWithoutWrite : PutFault

        /** 書き込まずに `409` を返す。 */
        data object Conflict : PutFault

        /** 書き込まずに `412` を返す。 */
        data object PreconditionFailed : PutFault

        /** 書き込まずに `403` を返す。 */
        data object Forbidden : PutFault

        /** 結果が不明として返し、書き込みは遅れて届く。遅れた書き込みは、次の PUT を受けたとき、その PUT より先に届く。 */
        data object ArriveLate : PutFault

        /** 先に [action] を走らせてから、ふつうに扱う。 */
        class RunBefore(val action: suspend () -> Unit) : PutFault
    }

    /** GET に差し込む事象。 */
    sealed interface GetFault {
        /** `5xx` が返ったとして、結果が不明として返す。 */
        data object ServerError : GetFault

        /** `403` が返ったとして返す。 */
        data object Forbidden : GetFault

        /** 先に [action] を走らせてから、ふつうに扱う。 */
        class RunBefore(val action: suspend () -> Unit) : GetFault
    }

    /** 一覧に差し込む事象。 */
    sealed interface ListFault {
        /** `403` が返ったとして返す。 */
        data object Forbidden : ListFault

        /** `5xx` が返ったとして、結果が不明として返す。 */
        data object ServerError : ListFault
    }

    /** DELETE に差し込む事象。消さずに返す。 */
    sealed interface DeleteFault {
        /** `403` が返ったとして返す。 */
        data object Forbidden : DeleteFault

        /** `5xx` が返ったとして、結果が不明として返す。 */
        data object ServerError : DeleteFault
    }

    private class PendingFault<F>(val matches: (String) -> Boolean, val fault: F)

    private val objects = mutableMapOf<String, StoredObject>()
    private val putFaults = mutableListOf<PendingFault<PutFault>>()
    private val getFaults = mutableListOf<PendingFault<GetFault>>()
    private val listFaults = mutableListOf<PendingFault<ListFault>>()
    private val deleteFaults = mutableListOf<PendingFault<DeleteFault>>()
    private val latePuts = mutableListOf<Request.Put>()
    private val receivedRequests = mutableListOf<Request>()
    private var eTagSequence = 0

    /** 一覧の 1 ページに入れるキーの数の上限。 */
    var listPageSize = 1000

    /** 一覧の応答の `Date` として返す時刻。置いたオブジェクトの `LastModified` の既定でもある。 */
    var serverTime: Instant = Instant.parse("2026-11-16T12:00:00Z")

    /**
     * 真のあいだは、S3 につながらないとして、どのリクエストにも、届かなかったものとして結果が不明を返す。リクエストは記録し、
     * 差し込んだ事象は使わず、遅れている PUT も届けない。
     */
    var unreachable: Boolean = false

    /** 受けたリクエスト。受けた順。 */
    val requests: List<Request> get() = receivedRequests.toList()

    /** 置いてあるオブジェクトのキー。 */
    val keys: Set<String> get() = objects.keys.toSet()

    /** [key] に合う次の PUT に、[fault] を差し込む。登録した順に 1 つずつ使う。 */
    fun onNextPut(fault: PutFault, key: (String) -> Boolean = { true }) {
        putFaults += PendingFault(key, fault)
    }

    /** [key] に合う次の GET に、[fault] を差し込む。登録した順に 1 つずつ使う。 */
    fun onNextGet(fault: GetFault, key: (String) -> Boolean = { true }) {
        getFaults += PendingFault(key, fault)
    }

    /** [key] に合う次の一覧のページに、[fault] を差し込む。登録した順に 1 つずつ使う。[key] は一覧のプレフィックスに合わせる。 */
    fun onNextList(fault: ListFault, key: (String) -> Boolean = { true }) {
        listFaults += PendingFault(key, fault)
    }

    /** [key] に合う次の DELETE に、[fault] を差し込む。登録した順に 1 つずつ使う。 */
    fun onNextDelete(fault: DeleteFault, key: (String) -> Boolean = { true }) {
        deleteFaults += PendingFault(key, fault)
    }

    fun objectAt(key: String): StoredObject? = objects[key]

    /** 条件を付けずに置き、新しい ETag を返す。リクエストとしては記録せず、差し込んだ事象も使わない。 */
    fun overwrite(key: String, body: ByteArray, lastModified: Instant = serverTime): String =
        store(key, body, contentType = null, lastModified = lastModified)

    /** 条件を付けずに消す。リクエストとしては記録しない。 */
    fun remove(key: String) {
        objects.remove(key)
    }

    /** 遅れている PUT を、いま届ける。届いた時点の条件で扱う。 */
    fun deliverLatePuts() {
        latePuts.toList().also { latePuts.clear() }.forEach(::evaluate)
    }

    override suspend fun get(key: String, ifNoneMatch: String?): GetObjectResult {
        receivedRequests += Request.Get(key, ifNoneMatch)
        if (unreachable) return GetObjectResult.Unknown(UNREACHABLE_DETAIL)
        when (val fault = takeFault(getFaults, key)) {
            GetFault.ServerError -> return GetObjectResult.Unknown("HTTP 500")
            GetFault.Forbidden -> return GetObjectResult.Forbidden
            is GetFault.RunBefore -> fault.action()
            null -> Unit
        }
        val stored = objects[key] ?: return GetObjectResult.NotFound
        return if (ifNoneMatch == stored.eTag) GetObjectResult.NotModified else GetObjectResult.Found(stored.body, stored.eTag)
    }

    override suspend fun put(
        key: String,
        body: ByteArray,
        contentType: String,
        condition: PutCondition,
    ): PutObjectResult {
        val request = Request.Put(key, body, contentType, condition)
        receivedRequests += request
        if (unreachable) return PutObjectResult.Unknown(UNREACHABLE_DETAIL)
        deliverLatePuts()
        when (val fault = takeFault(putFaults, key)) {
            is PutFault.WriteThenUnknown -> {
                evaluate(request)
                fault.afterWrite()
                return PutObjectResult.Unknown("応答が途中で切れた")
            }
            PutFault.WriteThenPreconditionFailed -> {
                evaluate(request)
                return PutObjectResult.PreconditionFailed
            }
            PutFault.UnknownWithoutWrite -> return PutObjectResult.Unknown("リクエストが届かなかった")
            PutFault.Conflict -> return PutObjectResult.Conflict
            PutFault.PreconditionFailed -> return PutObjectResult.PreconditionFailed
            PutFault.Forbidden -> return PutObjectResult.Forbidden
            PutFault.ArriveLate -> {
                latePuts += request
                return PutObjectResult.Unknown("リクエストが遅れている")
            }
            is PutFault.RunBefore -> fault.action()
            null -> Unit
        }
        return evaluate(request)
    }

    override suspend fun list(keyPrefix: String): ListObjectsResult =
        listAllPages { token -> readPage(keyPrefix, token) }

    /** 継続トークンは、次のページの先頭のキーの、一覧の中での番号。 */
    private fun readPage(keyPrefix: String, token: String?): ListPageResult {
        receivedRequests += Request.ListPage(keyPrefix, token)
        if (unreachable) return ListPageResult.Stopped(ListObjectsResult.Unknown(UNREACHABLE_DETAIL))
        when (takeFault(listFaults, keyPrefix)) {
            ListFault.Forbidden -> return ListPageResult.Stopped(ListObjectsResult.Forbidden)
            ListFault.ServerError -> return ListPageResult.Stopped(ListObjectsResult.Unknown("HTTP 500"))
            null -> Unit
        }
        val matching = objects.filterKeys { it.startsWith(keyPrefix) }.toSortedMap().entries.toList()
        val start = token?.toInt() ?: 0
        val page = matching.drop(start).take(listPageSize)
        return ListPageResult.Page(
            objects = page.map { (key, stored) -> ListedObject(key, stored.lastModified) },
            next = (start + page.size).takeIf { it < matching.size }?.toString(),
            serverTime = serverTime,
        )
    }

    /** S3 と同じく、無いキーの削除も成功にする。 */
    override suspend fun delete(key: String): DeleteObjectResult {
        receivedRequests += Request.Delete(key)
        if (unreachable) return DeleteObjectResult.Unknown(UNREACHABLE_DETAIL)
        return when (takeFault(deleteFaults, key)) {
            DeleteFault.Forbidden -> DeleteObjectResult.Forbidden
            DeleteFault.ServerError -> DeleteObjectResult.Unknown("HTTP 500")
            null -> {
                objects.remove(key)
                DeleteObjectResult.Success
            }
        }
    }

    /** S3 と同じく、条件を確かめて書く。 */
    private fun evaluate(request: Request.Put): PutObjectResult {
        val current = objects[request.key]
        val accepted = when (val condition = request.condition) {
            is PutCondition.IfMatch -> {
                if (current == null) return PutObjectResult.NotFound
                current.eTag == condition.eTag
            }
            PutCondition.IfNoneMatchAny -> current == null
        }
        return if (accepted) {
            PutObjectResult.Success(store(request.key, request.body, request.contentType))
        } else {
            PutObjectResult.PreconditionFailed
        }
    }

    private fun store(key: String, body: ByteArray, contentType: String?, lastModified: Instant = serverTime): String {
        eTagSequence += 1
        val eTag = "\"etag-$eTagSequence\""
        objects[key] = StoredObject(body, eTag, contentType, lastModified)
        return eTag
    }

    private fun <F> takeFault(faults: MutableList<PendingFault<F>>, key: String): F? =
        faults.indexOfFirst { it.matches(key) }
            .takeIf { it >= 0 }
            ?.let { faults.removeAt(it).fault }
}
