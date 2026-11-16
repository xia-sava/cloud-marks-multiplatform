package to.sava.cloudmarksmultiplatform.sync

import kotlinx.coroutines.test.runTest
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore.GetFault
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore.PutFault
import to.sava.cloudmarksmultiplatform.remote.PutCondition
import to.sava.cloudmarksmultiplatform.storage.SUPPORTED_FORMAT_VERSION
import to.sava.cloudmarksmultiplatform.storage.truncatedToMilliseconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CreateManifestTest {
    // マニフェストが無ければ、ヘッダーとルートフォルダだけのマニフェストを If-None-Match: * で置き、ETag と一緒にキャッシュに持つ
    @Test
    fun createsManifestWithOnlyRootWhenNone() = runTest {
        val fixture = SyncFixture()

        val result = fixture.sync.createManifest()

        val tree = assertIs<CreateManifestResult.Created>(result).tree
        val stored = fixture.store.storedManifest()
        assertEquals(stored, tree.manifest)
        assertEquals(SUPPORTED_FORMAT_VERSION to SUPPORTED_FORMAT_VERSION, stored.header.version to stored.header.readableBy)
        assertEquals(NOW.truncatedToMilliseconds(), stored.header.updatedAt)
        assertEquals(UPDATED_BY, stored.header.updatedBy)
        val root = stored.folders.single()
        assertEquals(Triple(null, "", null), Triple(root.parent, root.name, root.items))
        assertEquals(0, root.count)
        assertEquals(mapOf(root.id to emptyList()), tree.state.items)
        assertEquals(listOf(PutCondition.IfNoneMatchAny), fixture.store.puts(fixture.isManifest).map { it.condition })
        assertEquals(fixture.store.storedManifestETag(), tree.eTag)
        assertEquals(fixture.store.storedManifestETag(), fixture.cache.manifest()?.eTag)
        // ルートフォルダは項目を持たないので、ローカルキャッシュに残す項目ファイルは無い
        assertEquals(listOf(emptySet()), fixture.cache.retained)
    }

    // マニフェストが既にあれば 412 になり、マニフェストを 1 回読んで、commit がほかのものなので作らない。置いてあるマニフェストは変わらない
    @Test
    fun doesNotCreateWhenManifestExists() = runTest {
        val fixture = SyncFixture().apply { store.seed() }

        val result = fixture.sync.createManifest()

        assertEquals(CreateManifestResult.AlreadyExists, result)
        assertEquals(initialManifest(), fixture.store.storedManifest())
        assertEquals(1, fixture.store.puts(fixture.isManifest).size)
        assertEquals(1, fixture.store.gets(fixture.isManifest).size)
    }

    // PUT の応答が切れ（実は作れていた）、再送が 412：マニフェストを読み、commit がこのマニフェストのものなので作れたとする
    @Test
    fun ownManifestAfterLostResponseIsCreated() = runTest {
        val fixture = SyncFixture()
        fixture.store.onNextPut(PutFault.WriteThenUnknown(), key = fixture.isManifest)

        val result = fixture.sync.createManifest()

        val tree = assertIs<CreateManifestResult.Created>(result).tree
        assertEquals(fixture.store.storedManifest(), tree.manifest)
        assertEquals(fixture.store.storedManifestETag(), tree.eTag)
        assertEquals(fixture.store.storedManifestETag(), fixture.cache.manifest()?.eTag)
        assertEquals(2, fixture.store.puts(fixture.isManifest).size)
    }

    // 412 のあとのマニフェストの GET が失敗した：自分の作ったマニフェストかを確かめられないので、作らなかったとする
    @Test
    fun failedReadAfterPreconditionFailedIsAlreadyExists() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        fixture.store.onNextGet(GetFault.ServerError, key = fixture.isManifest)

        val result = fixture.sync.createManifest()

        assertEquals(CreateManifestResult.AlreadyExists, result)
    }

    // PUT が 409 なら、同じ PUT を間を空けて再送する
    @Test
    fun resendsOnConflict() = runTest {
        val fixture = SyncFixture()
        fixture.store.onNextPut(PutFault.Conflict, key = fixture.isManifest)

        val result = fixture.sync.createManifest()

        assertIs<CreateManifestResult.Created>(result)
        assertEquals(2, fixture.store.puts(fixture.isManifest).size)
        assertEquals(listOf(retryDelay(1)), fixture.waits)
    }
}
