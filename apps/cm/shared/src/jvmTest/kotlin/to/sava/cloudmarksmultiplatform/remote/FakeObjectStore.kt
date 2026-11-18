package to.sava.cloudmarksmultiplatform.remote

import kotlin.time.Instant

/** S3 につながらないあいだ（[FakeObjectStore.unreachable]）に返す、結果が不明の内容。 */
const val UNREACHABLE_DETAIL = "つながらない"

/** バージョニングを有効にしていないバケットのオブジェクトの versionId。 */
private const val NULL_VERSION_ID = "null"

/**
 * メモリ上の [ObjectStore]。S3 と同じく、ETag と条件付きの書き込みを扱う。
 *
 * - `If-Match` の PUT は、オブジェクトが無ければ `404`、ETag が違えば `412`
 * - `If-None-Match: *` の PUT は、オブジェクトがあれば `412`
 * - `If-None-Match` の GET は、ETag が一致すれば `304`
 *
 * [onNextPut]、[onNextGet]、[onNextList]、[onNextListVersions]、[onNextDelete] で、次のリクエストに、失敗やほかの書き手の書き込みなどの事象を差し込める。
 * [unreachable] を真にすると、偽に戻すまで S3 につながらないものとして振る舞う。
 *
 * 一覧は、S3 と同じく、[listPageSize] 個のキーごとのページに分けて返し、[listAllPages] で最後まで読む。
 * 一覧の応答の時刻は [serverTime]、オブジェクトの `LastModified` は置いた時点の [serverTime]（[overwrite] では指定できる）。
 *
 * [versioning] が真のあいだ（既定）は、S3 のバージョニングを有効にしたバケットと同じく、キーごとにバージョンの履歴を持つ。
 * 置く（PUT と [overwrite]）たびにバージョンが増え、消す（DELETE と [remove]）たびに削除マーカーが増える。
 * バージョンの一覧は、[listPageSize] 個のエントリごとのページに分けて返し、[listAllVersionPages] で最後まで読む。
 * 同じ `LastModified` のバージョンも、新しく置いた順に返す。
 * [versioning] が偽なら履歴を持たず、一覧と [getVersion] は、いまのオブジェクトだけを versionId `"null"` として扱う。
 *
 * runTest の中で、1 つのスレッドから使う。
 */
class FakeObjectStore(override val prefix: String = "cm/data") : ObjectStore {
    /** 置いてあるオブジェクト。[contentType] は、条件を付けずに置いたものでは null。 */
    class StoredObject(val body: ByteArray, val eTag: String, val contentType: String?, val lastModified: Instant)

    /** キーの履歴の 1 つのバージョン。[stored] が null なら削除マーカー。 */
    class StoredVersion(val versionId: String, val lastModified: Instant, val stored: StoredObject?)

    /** 受けたリクエスト。 */
    sealed interface Request {
        /** GET と PUT と DELETE ではキー、一覧ではキーのプレフィックス。 */
        val key: String

        data class Get(override val key: String, val ifNoneMatch: String?) : Request

        data class GetVersion(override val key: String, val versionId: String) : Request

        /** 一覧の 1 ページのリクエスト。[key] はキーのプレフィックス。 */
        data class ListPage(override val key: String, val continuationToken: String?) : Request

        /** バージョンの一覧の 1 ページのリクエスト。[versionIdMarker] は、続きを読むときの直前のバージョンの ID。 */
        data class ListVersionsPage(override val key: String, val versionIdMarker: String?) : Request

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

    /** 一覧（バージョンの一覧を含む）に差し込む事象。 */
    sealed interface ListFault {
        /** `403` が返ったとして返す。 */
        data object Forbidden : ListFault

        /** `5xx` が返ったとして、結果が不明として返す。 */
        data object ServerError : ListFault

        /** 先に [action] を走らせてから、ふつうに扱う。 */
        class RunBefore(val action: suspend () -> Unit) : ListFault
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

    /** キーごとの履歴。置いた順（古いものが先）。 */
    private val histories = mutableMapOf<String, MutableList<StoredVersion>>()
    private val putFaults = mutableListOf<PendingFault<PutFault>>()
    private val getFaults = mutableListOf<PendingFault<GetFault>>()
    private val listFaults = mutableListOf<PendingFault<ListFault>>()
    private val listVersionsFaults = mutableListOf<PendingFault<ListFault>>()
    private val deleteFaults = mutableListOf<PendingFault<DeleteFault>>()
    private val latePuts = mutableListOf<Request.Put>()
    private val receivedRequests = mutableListOf<Request>()
    private var eTagSequence = 0
    private var versionSequence = 0

    /** 一覧の 1 ページに入れるキー（バージョンの一覧ではエントリ）の数の上限。 */
    var listPageSize = 1000

    /** 真なら、キーごとにバージョンの履歴を持つ。偽なら持たない。 */
    var versioning: Boolean = true

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

    /** [key] に合う次のバージョンの一覧のページに、[fault] を差し込む。登録した順に 1 つずつ使う。[key] は一覧のキーに合わせる。 */
    fun onNextListVersions(fault: ListFault, key: (String) -> Boolean = { true }) {
        listVersionsFaults += PendingFault(key, fault)
    }

    fun objectAt(key: String): StoredObject? = objects[key]

    /** [key] の履歴。新しい順。削除マーカーを含む。[versioning] が偽なら空。 */
    fun versionsOf(key: String): List<StoredVersion> = histories[key].orEmpty().asReversed().toList()

    /** 条件を付けずに置き、新しい ETag を返す。リクエストとしては記録せず、差し込んだ事象も使わない。 */
    fun overwrite(key: String, body: ByteArray, lastModified: Instant = serverTime): String =
        store(key, body, contentType = null, lastModified = lastModified)

    /** 条件を付けずに消す。リクエストとしては記録しない。 */
    fun remove(key: String) {
        objects.remove(key)
        addDeleteMarker(key)
    }

    /** ライフサイクルが古いバージョンを消したものとして、[key] の履歴からいまのバージョン以外を取り除く。 */
    fun expireNoncurrentVersions(key: String) {
        histories[key]?.let { history -> history.removeAll(history.dropLast(1)) }
    }

    /** 遅れている PUT を、いま届ける。届いた時点の条件で扱う。 */
    fun deliverLatePuts() {
        latePuts.toList().also { latePuts.clear() }.forEach(::evaluate)
    }

    override suspend fun get(key: String, ifNoneMatch: String?): GetObjectResult {
        receivedRequests += Request.Get(key, ifNoneMatch)
        returnedByUnreachableOrFault(key)?.let { return it }
        val stored = objects[key] ?: return GetObjectResult.NotFound
        return if (ifNoneMatch == stored.eTag) GetObjectResult.NotModified else GetObjectResult.Found(stored.body, stored.eTag)
    }

    /** S3 と同じく、削除マーカーのバージョンの GET は失敗（`405`）にする。 */
    override suspend fun getVersion(key: String, versionId: String): GetObjectResult {
        receivedRequests += Request.GetVersion(key, versionId)
        returnedByUnreachableOrFault(key)?.let { return it }
        val version = versionAt(key, versionId) ?: return GetObjectResult.NotFound
        val stored = version.stored ?: return GetObjectResult.Failed("HTTP 405")
        return GetObjectResult.Found(stored.body, stored.eTag)
    }

    /** つながらないか、GET に差し込んだ事象が結果を決めるなら、その結果。ふつうに扱うなら null。 */
    private suspend fun returnedByUnreachableOrFault(key: String): GetObjectResult? {
        if (unreachable) return GetObjectResult.Unknown(UNREACHABLE_DETAIL)
        return when (val fault = takeFault(getFaults, key)) {
            GetFault.ServerError -> GetObjectResult.Unknown("HTTP 500")
            GetFault.Forbidden -> GetObjectResult.Forbidden
            is GetFault.RunBefore -> {
                fault.action()
                null
            }
            null -> null
        }
    }

    /** [versioning] が偽なら、いまのオブジェクトだけが versionId `"null"` のバージョンとして見える。 */
    private fun versionAt(key: String, versionId: String): StoredVersion? =
        if (versioning) {
            histories[key]?.firstOrNull { it.versionId == versionId }
        } else {
            currentAsNullVersion(key)?.takeIf { it.versionId == versionId }
        }

    private fun currentAsNullVersion(key: String): StoredVersion? =
        objects[key]?.let { StoredVersion(NULL_VERSION_ID, it.lastModified, it) }

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
    private suspend fun readPage(keyPrefix: String, token: String?): ListPageResult {
        receivedRequests += Request.ListPage(keyPrefix, token)
        if (unreachable) return ListPageResult.Stopped(ListObjectsResult.Unknown(UNREACHABLE_DETAIL))
        when (val fault = takeFault(listFaults, keyPrefix)) {
            ListFault.Forbidden -> return ListPageResult.Stopped(ListObjectsResult.Forbidden)
            ListFault.ServerError -> return ListPageResult.Stopped(ListObjectsResult.Unknown("HTTP 500"))
            is ListFault.RunBefore -> fault.action()
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

    override suspend fun listVersions(key: String): ListVersionsResult =
        listAllVersionPages { token -> readVersionPage(key, token) }

    /**
     * 続きの位置は、直前のバージョンの ID。そのバージョンの次のエントリから読む。
     * S3 と同じく、ページの中ではバージョンを先に、削除マーカーを後に、それぞれ新しい順で返す。
     */
    private suspend fun readVersionPage(key: String, token: VersionPageToken?): VersionPageResult {
        receivedRequests += Request.ListVersionsPage(key, token?.versionIdMarker)
        if (unreachable) return VersionPageResult.Stopped(ListVersionsResult.Unknown(UNREACHABLE_DETAIL))
        when (val fault = takeFault(listVersionsFaults, key)) {
            ListFault.Forbidden -> return VersionPageResult.Stopped(ListVersionsResult.Forbidden)
            ListFault.ServerError -> return VersionPageResult.Stopped(ListVersionsResult.Unknown("HTTP 500"))
            is ListFault.RunBefore -> fault.action()
            null -> Unit
        }
        val entries = entriesOf(key)
        val start = if (token == null) 0 else indexAfter(entries, token.versionIdMarker)
        val page = entries.drop(start).take(listPageSize)
        return VersionPageResult.Page(
            versions = page.sortedBy { it.isDeleteMarker },
            next = page.lastOrNull()?.takeIf { start + page.size < entries.size }?.let { VersionPageToken(key, it.versionId) },
        )
    }

    /** [versionId] のエントリの次の番号。履歴にそのバージョンが無ければ、続きを読めないのでエラーにする。 */
    private fun indexAfter(entries: List<ListedVersion>, versionId: String): Int {
        val index = entries.indexOfFirst { it.versionId == versionId }
        check(index >= 0) { "続きの位置のバージョン $versionId が履歴に無い" }
        return index + 1
    }

    /** バージョンの一覧に出るエントリ。新しい順。 */
    private fun entriesOf(key: String): List<ListedVersion> =
        if (versioning) {
            histories[key].orEmpty().asReversed().mapIndexed { index, version -> listedVersionOf(version, isLatest = index == 0) }
        } else {
            listOfNotNull(currentAsNullVersion(key)?.let { listedVersionOf(it, isLatest = true) })
        }

    private fun listedVersionOf(version: StoredVersion, isLatest: Boolean) =
        ListedVersion(version.versionId, version.lastModified, isLatest, isDeleteMarker = version.stored == null)

    /** S3 と同じく、無いキーの削除も成功にする。 */
    override suspend fun delete(key: String): DeleteObjectResult {
        receivedRequests += Request.Delete(key)
        if (unreachable) return DeleteObjectResult.Unknown(UNREACHABLE_DETAIL)
        return when (takeFault(deleteFaults, key)) {
            DeleteFault.Forbidden -> DeleteObjectResult.Forbidden
            DeleteFault.ServerError -> DeleteObjectResult.Unknown("HTTP 500")
            null -> {
                objects.remove(key)
                addDeleteMarker(key)
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
        val stored = StoredObject(body, eTag, contentType, lastModified)
        objects[key] = stored
        addVersion(key, StoredVersion(nextVersionId(), lastModified, stored))
        return eTag
    }

    /** S3 と同じく、無いキーの削除にも削除マーカーを付ける。 */
    private fun addDeleteMarker(key: String) {
        addVersion(key, StoredVersion(nextVersionId(), serverTime, stored = null))
    }

    private fun addVersion(key: String, version: StoredVersion) {
        if (versioning) histories.getOrPut(key) { mutableListOf() } += version
    }

    private fun nextVersionId(): String {
        versionSequence += 1
        return "version-$versionSequence"
    }

    private fun <F> takeFault(faults: MutableList<PendingFault<F>>, key: String): F? =
        faults.indexOfFirst { it.matches(key) }
            .takeIf { it >= 0 }
            ?.let { faults.removeAt(it).fault }
}
