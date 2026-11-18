package to.sava.cloudmarksmultiplatform.remote

import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import to.sava.cloudmarksmultiplatform.storage.newId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

private const val GZIP_CONTENT_TYPE = "application/gzip"

/** 一覧のページングのテストで、1 ページに入れるキーの数の上限。これより多いキーを書いて、複数のページを通す。 */
private const val PAGE_SIZE = 2

/** バージョニングを有効にしていないバケットのオブジェクトの versionId。 */
private const val NULL_VERSION_ID = "null"

/**
 * 実際の S3 で、[S3ObjectStore] の条件付きの読み書きと、一覧・DELETE の応答が期待どおりの結果に対応づけられることを確かめる。
 *
 * 次の環境変数がすべてあるときだけ走る。無ければ飛ばす。
 * - `CMM_S3_IT_REGION`、`CMM_S3_IT_BUCKET`、`CMM_S3_IT_PREFIX`。書き込み先になるので、プレフィックスはテスト用のものにする
 * - `AWS_ACCESS_KEY_ID`、`AWS_SECRET_ACCESS_KEY`
 *
 * テストは、書いたキーを終わりに自分で消す（DELETE の結果は見ない）。権限に `s3:PutObject`、`s3:GetObject`、
 * `s3:ListBucket`、`s3:DeleteObject` が要る。バージョンを確かめるテストは、さらに `s3:ListBucketVersions` と
 * `s3:GetObjectVersion` が要る。
 * 観察した結果（ETag の形、無いキーの GET の結果など）は標準出力へ出す。認証情報は出さない。
 */
class S3ObjectStoreIntegrationTest {
    private val environment = listOf(
        "CMM_S3_IT_REGION",
        "CMM_S3_IT_BUCKET",
        "CMM_S3_IT_PREFIX",
        "AWS_ACCESS_KEY_ID",
        "AWS_SECRET_ACCESS_KEY",
    ).associateWith { System.getenv(it) }

    private fun assumeEnvironment() {
        assumeTrue("実際の S3 で確かめる環境変数が無い", environment.values.all { !it.isNullOrEmpty() })
    }

    private fun newStore(maxKeys: Int? = null): S3ObjectStore =
        S3ObjectStore(
            region = environment.getValue("CMM_S3_IT_REGION")!!,
            accessKeyId = environment.getValue("AWS_ACCESS_KEY_ID")!!,
            secretAccessKey = environment.getValue("AWS_SECRET_ACCESS_KEY")!!,
            bucket = environment.getValue("CMM_S3_IT_BUCKET")!!,
            prefix = environment.getValue("CMM_S3_IT_PREFIX")!!,
            maxKeys = maxKeys,
        )

    /** 条件付きの PUT と GET の各応答が、`412`・`304`・`404` などの結果に対応づけられる。 */
    @Test
    fun conditionalRequestsMapToExpectedResults() = runTest {
        assumeEnvironment()
        newStore().use { store ->
            val key = "${store.prefix}/object-${newId()}.jsonl.gz"
            val missingKey = "${store.prefix}/missing-${newId()}.jsonl.gz"
            val first = byteArrayOf(1, 2, 3)
            val second = byteArrayOf(4, 5, 6)
            try {
                val created = store.put(key, first, GZIP_CONTENT_TYPE, PutCondition.IfNoneMatchAny)
                println("PUT If-None-Match: * (new) -> $created")
                val eTag = assertIs<PutObjectResult.Success>(created).eTag
                assertTrue(eTag.startsWith("\"") && eTag.endsWith("\""), "ETag が引用符ごと返る: $eTag")

                val again = store.put(key, first, GZIP_CONTENT_TYPE, PutCondition.IfNoneMatchAny)
                println("PUT If-None-Match: * (exists) -> $again")
                assertEquals(PutObjectResult.PreconditionFailed, again)

                val found = store.get(key)
                println("GET -> ${found::class.simpleName}")
                assertIs<GetObjectResult.Found>(found).let {
                    assertEquals(eTag, it.eTag)
                    assertContentEquals(first, it.body)
                }

                val notModified = store.get(key, ifNoneMatch = eTag)
                println("GET If-None-Match: <ETag> -> $notModified")
                assertEquals(GetObjectResult.NotModified, notModified)

                val wrongMatch = store.put(key, second, GZIP_CONTENT_TYPE, PutCondition.IfMatch("\"0\""))
                println("PUT If-Match: <wrong> -> $wrongMatch")
                assertEquals(PutObjectResult.PreconditionFailed, wrongMatch)

                val replaced = store.put(key, second, GZIP_CONTENT_TYPE, PutCondition.IfMatch(eTag))
                println("PUT If-Match: <ETag> -> $replaced")
                assertNotEquals(eTag, assertIs<PutObjectResult.Success>(replaced).eTag)

                val missing = store.get(missingKey)
                println("GET (missing key) -> $missing")
                assertTrue(missing == GetObjectResult.NotFound || missing == GetObjectResult.Forbidden, "無いキーの GET: $missing")

                val missingMatch = store.put(missingKey, first, GZIP_CONTENT_TYPE, PutCondition.IfMatch(eTag))
                println("PUT If-Match: <ETag> (missing key) -> $missingMatch")
            } finally {
                listOf(key, missingKey).forEach { store.delete(it) }
            }
        }
    }

    /**
     * 一覧と DELETE：3 キーを 1 ページ 2 キーまでで読み（2 ページに分かれる）、書いたキーがすべて一覧に出る。
     * 一覧の応答の `Date` が取れ（端末の時計に近い）、削除したキーが一覧から消える。無いキーの削除も成功になる。
     *
     * テストごとの固有のプレフィックスの下に書く。
     */
    @Test
    fun listAndDeleteMapToExpectedResults() = runTest {
        assumeEnvironment()
        newStore(maxKeys = PAGE_SIZE).use { store ->
            val keyPrefix = "${store.prefix}/it-list-${newId()}/"
            val keys = (1..3).map { "$keyPrefix$it.jsonl.gz" }
            try {
                keys.forEach { key ->
                    assertIs<PutObjectResult.Success>(store.put(key, byteArrayOf(1), GZIP_CONTENT_TYPE, PutCondition.IfNoneMatchAny))
                }

                val listed = store.list(keyPrefix)
                println("LIST -> ${(listed as? ListObjectsResult.Listed)?.let { "Listed ${it.objects.size} keys, Date=${it.serverTime}" } ?: listed}")
                val written = assertIs<ListObjectsResult.Listed>(listed)
                assertEquals(keys, written.objects.map { it.key })
                val skew = (Clock.System.now() - written.serverTime).absoluteValue
                assertTrue(skew < 1.hours, "一覧の応答の Date が端末の時計に近い: ${written.serverTime}")
                written.objects.forEach {
                    assertTrue((written.serverTime - it.lastModified).absoluteValue < 1.hours, "LastModified が Date に近い: $it")
                }

                val deleted = store.delete(keys.first())
                println("DELETE -> $deleted")
                assertEquals(DeleteObjectResult.Success, deleted)
                val afterDelete = assertIs<ListObjectsResult.Listed>(store.list(keyPrefix))
                assertEquals(keys.drop(1), afterDelete.objects.map { it.key })

                val deletedAgain = store.delete(keys.first())
                println("DELETE (missing key) -> $deletedAgain")
                assertEquals(DeleteObjectResult.Success, deletedAgain)
            } finally {
                keys.forEach { store.delete(it) }
            }
        }
    }

    /**
     * バージョンの一覧と、バージョンを指定した GET：同じキーを 3 回 PUT し、DELETE して削除マーカーを付け、
     * 1 ページ 2 エントリまでで読む（バージョン 3 つと削除マーカー 1 つが 2 ページ以上に分かれる）。
     * プレフィックスだけが一致する別のキーのバージョンは含まれない。
     * いまのバージョンは削除マーカーだけで、バージョンと削除マーカーが分かれて返り、古いバージョンの中身を取れる。
     *
     * バージョニングを有効にしていない（無効、または一時停止の）バケットでは、いまのオブジェクトだけが versionId `"null"` で
     * 返り、それを読めることを確かめて、あとは飛ばす。権限に `s3:ListBucketVersions` と `s3:GetObjectVersion` も要る。
     * このテストは古いバージョンを消せない（`s3:DeleteObjectVersion` を使わない）ので、テストごとに別のキーに書き、
     * 書いたバージョンはバケットのライフサイクルに任せて消す。
     */
    @Test
    fun versionsMapToExpectedResults() = runTest {
        assumeEnvironment()
        newStore(maxKeys = PAGE_SIZE).use { store ->
            val key = "${store.prefix}/it-versions-${newId()}.jsonl.gz"
            val siblingKey = "$key.sibling"
            val bodies = (1..3).map { byteArrayOf(it.toByte()) }
            try {
                var eTag: String? = null
                bodies.forEach { body ->
                    val condition = eTag?.let { PutCondition.IfMatch(it) } ?: PutCondition.IfNoneMatchAny
                    eTag = assertIs<PutObjectResult.Success>(store.put(key, body, GZIP_CONTENT_TYPE, condition)).eTag
                }
                assertIs<PutObjectResult.Success>(store.put(siblingKey, byteArrayOf(9), GZIP_CONTENT_TYPE, PutCondition.IfNoneMatchAny))

                val beforeDelete = assertIs<ListVersionsResult.Listed>(store.listVersions(key)).versions
                if (beforeDelete.any { it.versionId == NULL_VERSION_ID }) {
                    val only = beforeDelete.single()
                    println("LIST VERSIONS (versioning not enabled) -> $only")
                    assertTrue(only.isLatest && !only.isDeleteMarker, "バージョニングが有効でないバケットでは、いまのオブジェクトだけが返る: $only")
                    assertContentEquals(bodies.last(), assertIs<GetObjectResult.Found>(store.getVersion(key, NULL_VERSION_ID)).body)
                }
                assumeTrue("バージョニングを有効にしたバケットでだけ確かめる", beforeDelete.none { it.versionId == NULL_VERSION_ID })
                assertEquals(bodies.size, beforeDelete.size)
                assertTrue(beforeDelete.none { it.isDeleteMarker }, "DELETE の前は削除マーカーが無い")
                assertEquals(DeleteObjectResult.Success, store.delete(key))

                val listed = store.listVersions(key)
                println("LIST VERSIONS -> ${(listed as? ListVersionsResult.Listed)?.let { "Listed ${it.versions.size} entries" } ?: listed}")
                val versions = assertIs<ListVersionsResult.Listed>(listed).versions
                assertEquals(bodies.size + 1, versions.size)
                assertEquals(versions.size, versions.map { it.versionId }.distinct().size)
                assertEquals(listOf(true), versions.filter { it.isDeleteMarker }.map { it.isLatest })
                assertEquals(1, versions.count { it.isLatest })

                val bodiesNewestFirst = versions.filterNot { it.isDeleteMarker }.map { version ->
                    assertIs<GetObjectResult.Found>(store.getVersion(key, version.versionId)).body.toList()
                }
                assertEquals(bodies.reversed().map { it.toList() }, bodiesNewestFirst)

                val marker = versions.single { it.isDeleteMarker }
                val markerGet = store.getVersion(key, marker.versionId)
                println("GET (delete marker version) -> $markerGet")
                assertIs<GetObjectResult.Failed>(markerGet)

                val siblingGet = store.getVersion(siblingKey, versions.last { !it.isDeleteMarker }.versionId)
                println("GET (version of another key) -> $siblingGet")
                assertEquals(GetObjectResult.NotFound, siblingGet)
            } finally {
                listOf(key, siblingKey).forEach { store.delete(it) }
            }
        }
    }
}
