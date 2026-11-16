package to.sava.cloudmarksmultiplatform.sync

import kotlinx.coroutines.test.runTest
import to.sava.cloudmarksmultiplatform.operations.AddBookmarks
import to.sava.cloudmarksmultiplatform.operations.DeleteItems
import to.sava.cloudmarksmultiplatform.operations.ItemRef
import to.sava.cloudmarksmultiplatform.operations.MoveItems
import to.sava.cloudmarksmultiplatform.operations.NewItem
import to.sava.cloudmarksmultiplatform.operations.OperationStatus
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore.GetFault
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore.PutFault
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore.Request
import to.sava.cloudmarksmultiplatform.remote.PutCondition
import to.sava.cloudmarksmultiplatform.storage.FolderEntry
import to.sava.cloudmarksmultiplatform.storage.Item
import to.sava.cloudmarksmultiplatform.storage.Manifest
import to.sava.cloudmarksmultiplatform.storage.ManifestDecodeResult
import to.sava.cloudmarksmultiplatform.storage.decodeManifestGzip
import to.sava.cloudmarksmultiplatform.storage.encodeManifestGzip
import to.sava.cloudmarksmultiplatform.storage.referencedItemsFileKeys
import to.sava.cloudmarksmultiplatform.storage.truncatedToMilliseconds
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class OperationWriteTest {
    private val newItem = NewItem(itemId(100), "https://example.com/new", "new")
    private val addBookmark = AddBookmarks(listOf(newItem), ADDED)

    /** 置いてある目録が指す根の項目に、追加した項目がいくつあるか。 */
    private fun SyncFixture.newItemCountInRoot(): Int =
        store.storedItems(store.storedManifest(), ROOT_ID).count { it.id == newItem.id }

    private fun SyncFixture.storedEntry(folderId: String): FolderEntry =
        store.storedManifest().folders.single { it.id == folderId }

    private fun WriteResult.statuses(): List<OperationStatus> =
        assertIs<WriteResult.Success>(this).results.map { it.status }

    private fun manifestOf(body: ByteArray): Manifest =
        assertIs<ManifestDecodeResult.Readable>(decodeManifestGzip(body)).manifest

    // 普通の成功：中身の変わったフォルダのファイルを If-None-Match: * で置いてから、目録を If-Match で置く。
    // 目録は読み書きできる形で、commit が新しく、版は読んだまま、updatedAt は時計の現在をミリ秒に切り詰めたもの。
    // 書いた目録と ETag、置いたファイルをキャッシュに持ち、書いたあとの木を返す
    @Test
    fun writesItemsFilesThenManifest() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        val initialETag = fixture.store.storedManifestETag()

        val result = fixture.sync.write(listOf(addBookmark))

        val success = assertIs<WriteResult.Success>(result)
        assertEquals(listOf(OperationStatus.Applied), result.statuses())

        val stored = fixture.store.storedManifest()
        assertEquals(stored, success.tree.manifest)
        assertNotEquals(INITIAL_COMMIT, stored.header.commit)
        assertEquals(1 to 1, stored.header.version to stored.header.readableBy)
        assertEquals(NOW.truncatedToMilliseconds(), stored.header.updatedAt)
        assertEquals(UPDATED_BY, stored.header.updatedBy)

        val rootFileId = assertNotNull(fixture.storedEntry(ROOT_ID).items)
        assertNotEquals(ROOT_FILE_ID, rootFileId)
        assertEquals(ROOT_ITEMS + Item(newItem.id, newItem.url, newItem.title, ADDED), fixture.store.storedItems(stored, ROOT_ID))
        assertEquals(initialManifest().folders.drop(1), stored.folders.drop(1))

        val puts = fixture.store.puts { true }
        assertEquals(listOf(fixture.itemsFileKey(ROOT_ID, rootFileId), fixture.manifestKey), puts.map { it.key })
        assertEquals(listOf(PutCondition.IfNoneMatchAny, PutCondition.IfMatch(initialETag)), puts.map { it.condition })
        assertEquals(listOf("application/gzip", "application/gzip"), puts.map { it.contentType })

        assertEquals(fixture.store.storedManifestETag(), success.tree.eTag)
        assertEquals(fixture.store.storedManifestETag(), fixture.cache.manifest()?.eTag)
        assertNotNull(fixture.cache.itemsFile(fixture.itemsFileKey(ROOT_ID, rootFileId)))
        assertEquals(fixture.store.storedItems(stored, ROOT_ID), success.tree.state.items[ROOT_ID])
        assertEquals(rootFileId, success.tree.state.folders.getValue(ROOT_ID).itemsFileId)
    }

    // 書けたとき、書いた目録が指すフォルダのファイルのキーだけを手元に残す。置き換えられた古い根のファイルは手元から消え、
    // 置いた新しい根のファイルと、変わっていない A のファイルは残る
    @Test
    fun retainsKeysReferencedByWrittenManifest() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        val oldRootKey = fixture.itemsFileKey(ROOT_ID, ROOT_FILE_ID)
        val folderAKey = fixture.itemsFileKey(FOLDER_A, A_FILE_ID)
        fixture.sync.read { setOf(ROOT_ID, FOLDER_A) }
        assertNotNull(fixture.cache.itemsFile(oldRootKey))

        assertIs<WriteResult.Success>(fixture.sync.write(listOf(addBookmark)))

        val stored = fixture.store.storedManifest()
        val newRootKey = fixture.itemsFileKey(ROOT_ID, assertNotNull(fixture.storedEntry(ROOT_ID).items))
        assertEquals(setOf(newRootKey, folderAKey), fixture.cache.retained.last())
        assertEquals(referencedItemsFileKeys(fixture.store.prefix, stored), fixture.cache.retained.last())
        assertNull(fixture.cache.itemsFile(oldRootKey))
        assertNotNull(fixture.cache.itemsFile(newRootKey))
        assertNotNull(fixture.cache.itemsFile(folderAKey))
    }

    // 2 つのフォルダの中身が変わる操作：両方のフォルダのファイルを置いてから目録を置く。
    // 項目が無くなったフォルダはファイルを置かず、目録の items を null、count を 0 にする
    @Test
    fun movingTheOnlyItemPlacesOneFileAndEmptiesTheSource() = runTest {
        val fixture = SyncFixture().apply { store.seed() }

        val result = fixture.sync.write(listOf(MoveItems(listOf(ItemRef(FOLDER_A, A_ITEMS.single().id)), ROOT_ID, before = null)))

        assertEquals(listOf(OperationStatus.Applied), result.statuses())
        assertEquals(FolderEntry(FOLDER_A, ROOT_ID, "A", null, 0), fixture.storedEntry(FOLDER_A))
        assertEquals(ROOT_ITEMS + A_ITEMS, fixture.store.storedItems(fixture.store.storedManifest(), ROOT_ID))
        assertEquals(0, fixture.store.puts(fixture.isItemsFileOf(FOLDER_A)).size)
        assertEquals(listOf(true, false), fixture.store.puts { true }.map { it.key != fixture.manifestKey })
    }

    // 2 つのフォルダの中身が変わる書き込み：フォルダのファイルを両方置いてから目録を置き、目録の各フォルダは
    // そのフォルダのために置いたファイルを指す
    @Test
    fun placesOneFilePerChangedFolder() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        val moved = ROOT_ITEMS.first()

        val result = fixture.sync.write(listOf(MoveItems(listOf(ItemRef(ROOT_ID, moved.id)), FOLDER_A, before = null)))

        assertEquals(listOf(OperationStatus.Applied), result.statuses())
        val rootPut = fixture.store.puts(fixture.isItemsFileOf(ROOT_ID)).single()
        val aPut = fixture.store.puts(fixture.isItemsFileOf(FOLDER_A)).single()
        assertEquals(fileIdOfKey(rootPut.key), fixture.storedEntry(ROOT_ID).items)
        assertEquals(fileIdOfKey(aPut.key), fixture.storedEntry(FOLDER_A).items)
        val stored = fixture.store.storedManifest()
        assertEquals(ROOT_ITEMS.drop(1), fixture.store.storedItems(stored, ROOT_ID))
        assertEquals(A_ITEMS + moved, fixture.store.storedItems(stored, FOLDER_A))
        assertEquals(fixture.manifestKey, fixture.store.puts { true }.last().key)
        assertEquals(3, fixture.store.puts { true }.size)
    }

    // 手元にキャッシュがあれば、手順 1 は If-None-Match を付けた GET の 304 で手元の目録を使い、
    // 手元にあるフォルダのファイルは GET しない。目録の PUT の If-Match は手元の ETag
    @Test
    fun writeAfterReadUsesCachedManifestAndFiles() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        fixture.sync.read { setOf(ROOT_ID) }
        val cachedETag = fixture.store.storedManifestETag()
        val requestsBefore = fixture.store.requests.size

        val result = fixture.sync.write(listOf(addBookmark))

        assertEquals(listOf(OperationStatus.Applied), result.statuses())
        val gets = fixture.store.requests.drop(requestsBefore).filterIsInstance<Request.Get>()
        assertEquals(listOf(Request.Get(fixture.manifestKey, ifNoneMatch = cachedETag)), gets)
        assertEquals(PutCondition.IfMatch(cachedETag), fixture.store.puts(fixture.isManifest).single().condition)
    }

    // 適用した結果が読んだ状態と同じなら、PUT を 1 回も送らずに成功とする
    @Test
    fun unchangedOperationsDoNotPut() = runTest {
        val fixture = SyncFixture().apply { store.seed() }

        val result = fixture.sync.write(listOf(DeleteItems(listOf(ItemRef(FOLDER_A, itemId(999))))))

        assertEquals(listOf(OperationStatus.AlreadyApplied), result.statuses())
        assertEquals(initialManifest(), assertIs<WriteResult.Success>(result).tree.manifest)
        assertEquals(emptyList(), fixture.store.puts { true })
    }

    // 目録の PUT の前にほかの書き手が目録を書き換えた（412）：読み直して commit が替わっていれば、間を空けて適用し直す。
    // ほかの書き手の変更と、この書き込みの変更の両方が残る
    @Test
    fun reappliesAfterAnotherWriterAndKeepsBothChanges() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        val othersFolder = folderId(9)
        fixture.store.onNextPut(PutFault.RunBefore { fixture.store.addFolderAsOtherWriter(othersFolder) }, key = fixture.isManifest)

        val result = fixture.sync.write(listOf(addBookmark))

        assertEquals(listOf(OperationStatus.Applied), result.statuses())
        assertTrue(fixture.store.storedManifest().folders.any { it.id == othersFolder })
        assertEquals(1, fixture.newItemCountInRoot())
        assertEquals(2, fixture.store.puts(fixture.isManifest).size)
        assertEquals(2, fixture.store.puts(fixture.isItemsFileOf(ROOT_ID)).size)
        assertEquals(listOf(retryDelay(1)), fixture.waits)
    }

    // 目録の PUT が書き込まれたのに応答が切れた：読み直した目録の commit が送ったものなので成功とし、適用し直さない。
    // 追加は二重にならない
    @Test
    fun lostResponseAfterManifestWriteIsJudgedSuccessful() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        fixture.store.onNextPut(PutFault.WriteThenUnknown(), key = fixture.isManifest)

        val result = fixture.sync.write(listOf(addBookmark))

        val success = assertIs<WriteResult.Success>(result)
        assertEquals(listOf(OperationStatus.Applied), result.statuses())
        assertEquals(fixture.store.storedManifest(), success.tree.manifest)
        assertEquals(fixture.store.storedManifestETag(), success.tree.eTag)
        assertEquals(fixture.store.storedManifestETag(), fixture.cache.manifest()?.eTag)
        assertEquals(1, fixture.store.puts(fixture.isManifest).size)
        assertEquals(1, fixture.store.puts(fixture.isItemsFileOf(ROOT_ID)).size)
        assertEquals(1, fixture.newItemCountInRoot())
    }

    // 先に送った目録の PUT が遅れて届いていた：読み直した目録の commit が、この書き込みで送った commit のどれかと
    // 一致すれば成功とする（最後に送ったものでなくてよい）。後から送った目録の PUT は 412 になり、追加は二重にならない
    @Test
    fun delayedManifestWriteIsJudgedByAnyCommitSent() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        fixture.store.onNextPut(PutFault.ArriveLate, key = fixture.isManifest)

        val result = fixture.sync.write(listOf(addBookmark))

        val success = assertIs<WriteResult.Success>(result)
        val manifestPuts = fixture.store.puts(fixture.isManifest)
        assertEquals(2, manifestPuts.size)
        val firstSent = manifestOf(manifestPuts.first().body)
        assertEquals(firstSent, fixture.store.storedManifest())
        assertEquals(firstSent, success.tree.manifest)
        assertEquals(listOf(OperationStatus.Applied), result.statuses())
        assertEquals(1, fixture.newItemCountInRoot())
    }

    // 目録の PUT の応答が切れたあとに、ほかの書き手が挟まった：commit は替わっているので適用し直すが、
    // 追加の項目の ID が既にあるので適用済みになり、二重にならない。PUT し直さない
    @Test
    fun lostResponseThenAnotherWriterLeavesSingleAddition() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        val othersFolder = folderId(9)
        fixture.store.onNextPut(
            PutFault.WriteThenUnknown { fixture.store.addFolderAsOtherWriter(othersFolder) },
            key = fixture.isManifest,
        )

        val result = fixture.sync.write(listOf(addBookmark))

        val success = assertIs<WriteResult.Success>(result)
        assertEquals(listOf(OperationStatus.AlreadyApplied), result.statuses())
        assertEquals(fixture.store.storedManifest(), success.tree.manifest)
        assertTrue(success.tree.manifest.folders.any { it.id == othersFolder })
        assertEquals(1, fixture.store.puts(fixture.isManifest).size)
        assertEquals(1, fixture.newItemCountInRoot())
    }

    // 目録の PUT が 409：同じ PUT（同じ中身・同じ条件）を、間を空けて送り直す
    @Test
    fun conflictOnManifestResendsTheSamePut() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        fixture.store.onNextPut(PutFault.Conflict, key = fixture.isManifest)

        val result = fixture.sync.write(listOf(addBookmark))

        assertEquals(listOf(OperationStatus.Applied), result.statuses())
        val (first, second) = fixture.store.puts(fixture.isManifest)
        assertContentEquals(first.body, second.body)
        assertEquals(first.condition, second.condition)
        assertEquals(1, fixture.store.puts(fixture.isItemsFileOf(ROOT_ID)).size)
        assertEquals(listOf(retryDelay(1)), fixture.waits)
        assertEquals(1, fixture.newItemCountInRoot())
    }

    // 手順 3 の最初の PUT から 1 時間を過ぎた：目録を送らずに手順 1 からやり直し、フォルダのファイルも新しい ID で置き直す
    @Test
    fun restartsWhenAnHourPassedSinceFirstFilePut() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        fixture.store.onNextPut(PutFault.RunBefore { fixture.timeSource += 61.minutes }, key = fixture.isItemsFileOf(ROOT_ID))

        val result = fixture.sync.write(listOf(addBookmark))

        assertEquals(listOf(OperationStatus.Applied), result.statuses())
        val filePuts = fixture.store.puts(fixture.isItemsFileOf(ROOT_ID))
        assertEquals(2, filePuts.size)
        assertNotEquals(filePuts[0].key, filePuts[1].key)
        assertEquals(1, fixture.store.puts(fixture.isManifest).size)
        assertEquals(fileIdOfKey(filePuts[1].key), fixture.storedEntry(ROOT_ID).items)
    }

    // 手順 3 の最初の PUT から 1 時間を過ぎていなければ、そのまま目録を送る
    @Test
    fun sendsManifestWithinAnHourSinceFirstFilePut() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        fixture.store.onNextPut(PutFault.RunBefore { fixture.timeSource += 59.minutes }, key = fixture.isItemsFileOf(ROOT_ID))

        val result = fixture.sync.write(listOf(addBookmark))

        assertEquals(listOf(OperationStatus.Applied), result.statuses())
        assertEquals(1, fixture.store.puts(fixture.isItemsFileOf(ROOT_ID)).size)
        assertEquals(1, fixture.store.puts(fixture.isManifest).size)
    }

    // 目録の PUT が 409 で、送り直しを待つあいだに手順 3 の最初の PUT から 1 時間を過ぎた：
    // 送り直さずに手順 1 からやり直し、フォルダのファイルも新しい ID で置き直す
    @Test
    fun conflictResendIsSkippedWhenAnHourPassed() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        fixture.store.onNextPut(PutFault.Conflict, key = fixture.isManifest)
        fixture.onWait = {
            fixture.onWait = {}
            fixture.timeSource += 61.minutes
        }

        val result = fixture.sync.write(listOf(addBookmark))

        assertEquals(listOf(OperationStatus.Applied), result.statuses())
        val manifestPuts = fixture.store.puts(fixture.isManifest)
        assertEquals(2, manifestPuts.size)
        assertNotEquals(manifestOf(manifestPuts[0].body).header.commit, manifestOf(manifestPuts[1].body).header.commit)
        val filePuts = fixture.store.puts(fixture.isItemsFileOf(ROOT_ID))
        assertEquals(2, filePuts.size)
        assertNotEquals(filePuts[0].key, filePuts[1].key)
        assertEquals(fileIdOfKey(filePuts[1].key), fixture.storedEntry(ROOT_ID).items)
    }

    // 目録の PUT の 409 が、送り直しの上限を超えて続いた：busy に当たる失敗。送り直しのたびに間を延ばし、目録は書き換えない
    @Test
    fun conflictsBeyondResendLimitAreBusy() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        repeat(MAX_RESENDS + 1) { fixture.store.onNextPut(PutFault.Conflict, key = fixture.isManifest) }

        val result = fixture.sync.write(listOf(addBookmark))

        assertEquals(WriteResult.Failed(SyncFailure.Busy), result)
        assertEquals(MAX_RESENDS + 1, fixture.store.puts(fixture.isManifest).size)
        assertEquals(initialManifest(), fixture.store.storedManifest())
        assertEquals((1..MAX_RESENDS).map(::retryDelay), fixture.waits)
    }

    // ほかの書き手が毎回先に目録を書き換え、適用し直しの上限を超えた：busy に当たる失敗
    @Test
    fun reappliesBeyondLimitAreBusy() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        repeat(MAX_REAPPLIES + 1) { n ->
            fixture.store.onNextPut(
                PutFault.RunBefore { fixture.store.addFolderAsOtherWriter(folderId(10 + n)) },
                key = fixture.isManifest,
            )
        }

        val result = fixture.sync.write(listOf(addBookmark))

        assertEquals(WriteResult.Failed(SyncFailure.Busy), result)
        assertEquals(MAX_REAPPLIES + 1, fixture.store.puts(fixture.isManifest).size)
        assertEquals(0, fixture.newItemCountInRoot())
        assertEquals((1..MAX_REAPPLIES).map(::retryDelay), fixture.waits)
    }

    // 目録が読むだけのもの・更新が要るもの・壊れているもの・無いときは、フォルダのファイルも読まず、
    // 何も PUT せずに、それぞれの失敗として返す
    @Test
    fun doesNotWriteUnlessManifestIsWritable() = runTest {
        val cases: List<Pair<ByteArray?, (SyncFailure) -> Unit>> = listOf(
            encodeManifestGzip(initialManifest(version = 2, readableBy = 1)) to { failure ->
                assertEquals(SyncFailure.ReadOnly(version = 2, readableBy = 1), failure)
            },
            encodeManifestGzip(initialManifest(version = 2, readableBy = 2)) to { failure ->
                assertEquals(SyncFailure.UpdateRequired(version = 2, readableBy = 2), failure)
            },
            "not gzip".toByteArray() to { failure -> assertIs<SyncFailure.ManifestCorrupt>(failure) },
            null to { failure -> assertEquals(SyncFailure.ManifestNotFound, failure) },
        )

        cases.forEach { (manifestBody, check) ->
            val fixture = SyncFixture()
            manifestBody?.let { body ->
                fixture.store.seed()
                fixture.store.overwrite(fixture.manifestKey, body)
            }

            val result = fixture.sync.write(listOf(addBookmark))

            check(assertIs<WriteResult.Failed>(result).failure)
            assertEquals(listOf(fixture.manifestKey), fixture.store.requests.map { it.key })
        }
    }

    // フォルダのファイルの PUT の応答が切れ、送り直しが 412：先の PUT が成功していたとみなし、そのファイルを目録から指す
    @Test
    fun itemsFilePreconditionFailedAfterResendCountsAsPlaced() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        fixture.store.onNextPut(PutFault.WriteThenUnknown(), key = fixture.isItemsFileOf(ROOT_ID))

        val result = fixture.sync.write(listOf(addBookmark))

        assertEquals(listOf(OperationStatus.Applied), result.statuses())
        val filePuts = fixture.store.puts(fixture.isItemsFileOf(ROOT_ID))
        assertEquals(2, filePuts.size)
        assertEquals(filePuts[0].key, filePuts[1].key)
        assertEquals(fileIdOfKey(filePuts[0].key), fixture.storedEntry(ROOT_ID).items)
        assertEquals(1, fixture.newItemCountInRoot())
        assertEquals(listOf(retryDelay(1)), fixture.waits)
    }

    // フォルダのファイルの最初の PUT が 412：ファイルの ID を振り直して置き直し、新しいファイルを目録から指す
    @Test
    fun itemsFilePreconditionFailedOnFirstPutGetsNewFileId() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        fixture.store.onNextPut(PutFault.PreconditionFailed, key = fixture.isItemsFileOf(ROOT_ID))

        val result = fixture.sync.write(listOf(addBookmark))

        assertEquals(listOf(OperationStatus.Applied), result.statuses())
        val filePuts = fixture.store.puts(fixture.isItemsFileOf(ROOT_ID))
        assertEquals(2, filePuts.size)
        assertNotEquals(filePuts[0].key, filePuts[1].key)
        assertEquals(fileIdOfKey(filePuts[1].key), fixture.storedEntry(ROOT_ID).items)
    }

    // フォルダのファイルの PUT が 409：同じキーへ、同じ中身を、間を空けて送り直す
    @Test
    fun conflictOnItemsFileResendsTheSamePut() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        fixture.store.onNextPut(PutFault.Conflict, key = fixture.isItemsFileOf(ROOT_ID))

        val result = fixture.sync.write(listOf(addBookmark))

        assertEquals(listOf(OperationStatus.Applied), result.statuses())
        val filePuts = fixture.store.puts(fixture.isItemsFileOf(ROOT_ID))
        assertEquals(2, filePuts.size)
        assertEquals(filePuts[0].key, filePuts[1].key)
        assertContentEquals(filePuts[0].body, filePuts[1].body)
        assertEquals(fileIdOfKey(filePuts[0].key), fixture.storedEntry(ROOT_ID).items)
        assertEquals(listOf(retryDelay(1)), fixture.waits)
    }

    // フォルダのファイルの PUT の結果が分からないことが、送り直しの上限を超えて続いた：目録を送らず、
    // 上限を超えたではなく S3 につながらない失敗として返す
    @Test
    fun unknownItemsFilePutsBeyondResendLimitAreUnreachable() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        repeat(MAX_RESENDS + 1) { fixture.store.onNextPut(PutFault.UnknownWithoutWrite, key = fixture.isItemsFileOf(ROOT_ID)) }

        val result = fixture.sync.write(listOf(addBookmark))

        assertIs<SyncFailure.Unreachable>(assertIs<WriteResult.Failed>(result).failure)
        assertEquals(MAX_RESENDS + 1, fixture.store.puts(fixture.isItemsFileOf(ROOT_ID)).size)
        assertEquals(0, fixture.store.puts(fixture.isManifest).size)
    }

    // フォルダのファイルの PUT が 403：目録を送らずに失敗として返す
    @Test
    fun forbiddenItemsFilePutFailsWithoutManifestPut() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        fixture.store.onNextPut(PutFault.Forbidden, key = fixture.isItemsFileOf(ROOT_ID))

        val result = fixture.sync.write(listOf(addBookmark))

        assertEquals(WriteResult.Failed(SyncFailure.Forbidden), result)
        assertEquals(0, fixture.store.puts(fixture.isManifest).size)
    }

    // 目録の PUT が 403：失敗として返し、送り直しも適用し直しもしない
    @Test
    fun forbiddenManifestPutFailsWithoutRetry() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        fixture.store.onNextPut(PutFault.Forbidden, key = fixture.isManifest)

        val result = fixture.sync.write(listOf(addBookmark))

        assertEquals(WriteResult.Failed(SyncFailure.Forbidden), result)
        assertEquals(1, fixture.store.puts(fixture.isManifest).size)
        assertEquals(emptyList(), fixture.waits)
    }

    // 目録の PUT が 404（目録が消されている）：失敗として返し、適用し直さない
    @Test
    fun manifestDeletedBeforePutFails() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        fixture.store.onNextPut(PutFault.RunBefore { fixture.store.remove(fixture.manifestKey) }, key = fixture.isManifest)

        val result = fixture.sync.write(listOf(addBookmark))

        assertEquals(WriteResult.Failed(SyncFailure.ManifestDeleted), result)
        assertEquals(1, fixture.store.puts(fixture.isManifest).size)
    }

    // 目録の PUT の応答が切れ、直後の読み直しの GET も 1 回 5xx：間を空けて GET を送り直し、成功を確かめる
    @Test
    fun rereadIsResentAfterLostManifestResponse() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        fixture.store.onNextPut(
            PutFault.WriteThenUnknown { fixture.store.onNextGet(GetFault.ServerError, key = fixture.isManifest) },
            key = fixture.isManifest,
        )

        val result = fixture.sync.write(listOf(addBookmark))

        assertEquals(listOf(OperationStatus.Applied), result.statuses())
        assertEquals(fixture.store.storedManifest(), assertIs<WriteResult.Success>(result).tree.manifest)
        // 手順 1 の GET、失敗した読み直し、送り直した読み直し
        assertEquals(3, fixture.store.gets(fixture.isManifest).size)
        assertEquals(1, fixture.store.puts(fixture.isManifest).size)
        assertEquals(listOf(retryDelay(1)), fixture.waits)
        assertEquals(1, fixture.newItemCountInRoot())
    }

    // 目録の PUT が書き込まれたのに 412 が返った（SDK が自分で送り直した）：読み直した目録の commit が送ったものなので
    // 成功とし、適用し直さない
    @Test
    fun preconditionFailedAfterOwnWriteIsJudgedSuccessful() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        fixture.store.onNextPut(PutFault.WriteThenPreconditionFailed, key = fixture.isManifest)

        val result = fixture.sync.write(listOf(addBookmark))

        assertEquals(listOf(OperationStatus.Applied), result.statuses())
        assertEquals(fixture.store.storedManifest(), assertIs<WriteResult.Success>(result).tree.manifest)
        assertEquals(1, fixture.store.puts(fixture.isManifest).size)
        assertEquals(1, fixture.newItemCountInRoot())
    }

    // 目録の PUT が書き込まれたのに 412 が返り、読み直しの GET も送り直しの上限まで失敗した：
    // 届いたかどうかを確かめられないので Unconfirmed で返す
    @Test
    fun preconditionFailedWithFailedRereadsIsUnconfirmed() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        // 手順 1 の GET のあと、フォルダのファイルの PUT の時点で、以降の目録の GET の失敗を差し込む
        fixture.store.onNextPut(
            PutFault.RunBefore {
                repeat(MAX_RESENDS + 1) { fixture.store.onNextGet(GetFault.ServerError, key = fixture.isManifest) }
            },
            key = fixture.isItemsFileOf(ROOT_ID),
        )
        fixture.store.onNextPut(PutFault.WriteThenPreconditionFailed, key = fixture.isManifest)

        val result = fixture.sync.write(listOf(addBookmark))

        assertIs<SyncFailure.Unreachable>(assertIs<WriteResult.Unconfirmed>(result).failure)
        assertEquals(1, fixture.store.puts(fixture.isManifest).size)
        assertEquals(1 + MAX_RESENDS + 1, fixture.store.gets(fixture.isManifest).size)
    }

    // 最初の手順 1 の目録の GET が 5xx：目録の PUT をまだ送っていないので GET を送り直さず、S3 につながらない失敗
    @Test
    fun firstManifestReadIsNotResent() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        fixture.store.onNextGet(GetFault.ServerError, key = fixture.isManifest)

        val result = fixture.sync.write(listOf(addBookmark))

        assertIs<SyncFailure.Unreachable>(assertIs<WriteResult.Failed>(result).failure)
        assertEquals(1, fixture.store.gets(fixture.isManifest).size)
        assertEquals(emptyList(), fixture.waits)
    }

    // 目録の PUT が届かず適用し直すとき、適用し直しの手順 1 の目録の GET が 1 回 5xx：間を空けて送り直し、書き込みを続ける
    @Test
    fun reapplyStepOneReadIsResent() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        fixture.store.onNextPut(PutFault.UnknownWithoutWrite, key = fixture.isManifest)
        // 適用し直しの前の待ちで、次の目録の GET（適用し直しの手順 1）に 5xx を差し込む
        fixture.onWait = {
            fixture.onWait = {}
            fixture.store.onNextGet(GetFault.ServerError, key = fixture.isManifest)
        }

        val result = fixture.sync.write(listOf(addBookmark))

        assertEquals(listOf(OperationStatus.Applied), result.statuses())
        // 最初の手順 1、成功の判定、失敗した手順 1、送り直した手順 1
        assertEquals(4, fixture.store.gets(fixture.isManifest).size)
        assertEquals(listOf(retryDelay(1), retryDelay(1)), fixture.waits)
        assertEquals(2, fixture.store.puts(fixture.isManifest).size)
        assertEquals(1, fixture.newItemCountInRoot())
    }

    // 目録の PUT の結果が分からず、実は届いていなかった：読み直した目録の commit が送ったものでないので適用し直し、追加は 1 つ
    @Test
    fun manifestPutThatDidNotArriveIsReapplied() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        fixture.store.onNextPut(PutFault.UnknownWithoutWrite, key = fixture.isManifest)

        val result = fixture.sync.write(listOf(addBookmark))

        assertEquals(listOf(OperationStatus.Applied), result.statuses())
        assertEquals(2, fixture.store.puts(fixture.isManifest).size)
        assertEquals(1, fixture.newItemCountInRoot())
    }

    // 目録の PUT の結果が分からないことが、適用し直しの上限を超えて続いた：届いたかどうかを確かめられないので、
    // Unconfirmed の中に S3 につながらない失敗として返す
    @Test
    fun unknownManifestPutsBeyondReapplyLimitAreUnconfirmed() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        repeat(MAX_REAPPLIES + 1) { fixture.store.onNextPut(PutFault.UnknownWithoutWrite, key = fixture.isManifest) }

        val result = fixture.sync.write(listOf(addBookmark))

        assertIs<SyncFailure.Unreachable>(assertIs<WriteResult.Unconfirmed>(result).failure)
        assertEquals(MAX_REAPPLIES + 1, fixture.store.puts(fixture.isManifest).size)
    }

    // 適用し直しの手順 1 で読んだ目録の commit が、先に送ったものだった（遅れて届いた）：その場で成功とし、適用し直さない
    @Test
    fun lateManifestFoundInReapplyStepOneIsSuccess() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        fixture.store.onNextPut(PutFault.ArriveLate, key = fixture.isManifest)
        // 成功の判定の GET のあと、適用し直しの手順 1 の GET の前に届く
        fixture.onWait = {
            fixture.onWait = {}
            fixture.store.deliverLatePuts()
        }

        val result = fixture.sync.write(listOf(addBookmark))

        assertEquals(listOf(OperationStatus.Applied), result.statuses())
        assertEquals(manifestOf(fixture.store.puts(fixture.isManifest).single().body), fixture.store.storedManifest())
        assertEquals(1, fixture.store.puts(fixture.isItemsFileOf(ROOT_ID)).size)
        assertEquals(1, fixture.newItemCountInRoot())
    }

    // 目録の PUT の応答が切れ、読み直しの GET も送り直しの上限まで失敗した：届いたかどうかを確かめられないので
    // Unconfirmed で返す。同じ操作の並びでもう一度書き込むと、届いていた追加は適用済みになり、二重にならない
    @Test
    fun unconfirmedWriteCanBeRepeatedWithTheSameOperations() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        fixture.store.onNextPut(
            PutFault.WriteThenUnknown {
                repeat(MAX_RESENDS + 1) { fixture.store.onNextGet(GetFault.ServerError, key = fixture.isManifest) }
            },
            key = fixture.isManifest,
        )

        val first = fixture.sync.write(listOf(addBookmark))

        assertIs<SyncFailure.Unreachable>(assertIs<WriteResult.Unconfirmed>(first).failure)
        val second = fixture.sync.write(listOf(addBookmark))
        assertEquals(listOf(OperationStatus.AlreadyApplied), second.statuses())
        assertEquals(1, fixture.newItemCountInRoot())
    }

    // 操作が項目を読む必要のあるフォルダのファイルが壊れていれば、何も PUT せずに失敗にする
    @Test
    fun corruptRequiredFolderStopsTheWrite() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        // 目録の count（1）と項目の数が合わない
        fixture.store.overwrite(fixture.itemsFileKey(FOLDER_A, A_FILE_ID), itemsFileBody(FOLDER_A, listOf(item(3), item(4))))

        val result = fixture.sync.write(listOf(DeleteItems(listOf(ItemRef(FOLDER_A, A_ITEMS.single().id)))))

        val failure = assertIs<SyncFailure.FolderCorrupt>(assertIs<WriteResult.Failed>(result).failure)
        assertEquals(setOf(FOLDER_A), failure.problems.keys)
        assertEquals(emptyList(), fixture.store.puts { true })
    }

    // 壊れたフォルダの項目を読まない操作は書ける。書いた目録は、壊れたフォルダの行をそのまま引き継ぐ
    @Test
    fun corruptFolderDoesNotStopOperationsOnOtherFolders() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        fixture.store.overwrite(fixture.itemsFileKey(FOLDER_A, A_FILE_ID), itemsFileBody(FOLDER_A, listOf(item(3), item(4))))

        val result = fixture.sync.write(listOf(addBookmark))

        assertEquals(listOf(OperationStatus.Applied), result.statuses())
        assertEquals(initialManifest().folders.single { it.id == FOLDER_A }, fixture.storedEntry(FOLDER_A))
    }
}
