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

/** 一覧のページングの試験で、1 ページに入れるキーの数の上限。これより多いキーを書いて、複数のページを通す。 */
private const val PAGE_SIZE = 2

/**
 * 実際の S3 で、[S3ObjectStore] の条件付きの読み書きと、一覧・DELETE が期待どおりの結果に写ることを確かめる。
 *
 * 次の環境変数がすべてあるときだけ走る。無ければ飛ばす。
 * - `CMM_S3_IT_REGION`、`CMM_S3_IT_BUCKET`、`CMM_S3_IT_PREFIX`（書く先。試し用のプレフィックスにする）
 * - `AWS_ACCESS_KEY_ID`、`AWS_SECRET_ACCESS_KEY`
 *
 * 試験は、書いたキーを終わりに自分で消す（DELETE の結果は見ない）。権限に `s3:PutObject`、`s3:GetObject`、
 * `s3:ListBucket`、`s3:DeleteObject` が要る。
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

    /** 条件付きの PUT と GET の各応答が、`412`・`304`・`404` などの結果に写る。 */
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
     * 試験ごとの固有のプレフィックスの下に書く。
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
}
