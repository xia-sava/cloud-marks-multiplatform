package to.sava.cloudmarksmultiplatform.app

import to.sava.cloudmarksmultiplatform.browse.BrowseState
import to.sava.cloudmarksmultiplatform.operations.toTreeState
import to.sava.cloudmarksmultiplatform.sync.LoadedTree
import to.sava.cloudmarksmultiplatform.sync.initialManifest
import kotlin.test.Test
import kotlin.test.assertEquals

/** 読み書きできるツリーを読み終え、操作も設定の保存もしていない、設定の画面の状態。以前の状態に戻すことを受け付ける。 */
private fun idleSettingsState(): AppState {
    val manifest = initialManifest()
    val tree = LoadedTree(manifest, "\"etag\"", manifest.toTreeState(), emptyMap())
    return AppState(
        screen = Screen.SETTINGS,
        browse = BrowseState(tree = tree),
        settings = SettingsScreenState(hasStoredSettings = true, canReturn = true),
    )
}

class AppStateTest {
    // 以前の状態に戻すことを受け付けるのは、読み書きできるツリーがあり、マニフェストを読んでいる最中でも、操作を書いている最中でも、
    // 設定の保存の最中でもないときだけ
    @Test
    fun canRestoreRequiresIdleWritableTree() {
        val idle = idleSettingsState()
        val cases: List<Triple<String, AppState, Boolean>> = listOf(
            Triple("何もしていない", idle, true),
            Triple("ツリーが無い", idle.copy(browse = idle.browse.copy(tree = null)), false),
            Triple("読むだけのツリー", idle.copy(browse = idle.browse.copy(readOnly = true)), false),
            Triple("マニフェストを読んでいる最中", idle.copy(browse = idle.browse.copy(loading = true)), false),
            Triple("操作を書いている最中", idle.copy(write = WriteState(writing = true)), false),
            Triple("設定の保存の最中", idle.copy(settings = idle.settings.copy(status = SettingsStatus.Saving)), false),
            Triple("マニフェストを作っている最中", idle.copy(settings = idle.settings.copy(status = SettingsStatus.CreatingManifest)), false),
        )
        cases.forEach { (name, state, expected) -> assertEquals(expected, state.canRestore, name) }
    }
}
