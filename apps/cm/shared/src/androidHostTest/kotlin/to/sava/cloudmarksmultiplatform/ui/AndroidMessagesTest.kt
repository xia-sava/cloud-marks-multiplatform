package to.sava.cloudmarksmultiplatform.ui

import to.sava.cloudmarksmultiplatform.settings.SettingsReadResult
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidMessagesTest {
    // 保存されているアクセスキーを復号できなかったときの Android の文は、もう一度入力して保存するよう伝えるだけで、詳細は無い
    @Test
    fun undecryptableTextOnAndroid() {
        assertEquals(
            UiMessage("保存されているアクセスキーを読み込めませんでした。もう一度入力して保存してください。"),
            describeSettingsReadProblem(SettingsReadResult.Undecryptable("復号に失敗")),
        )
    }
}
