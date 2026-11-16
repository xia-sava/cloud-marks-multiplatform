package to.sava.cloudmarksmultiplatform.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SettingsFormTest {
    private val filled = SettingsForm(
        region = SETTINGS.region,
        bucket = SETTINGS.bucket,
        prefix = SETTINGS.prefix,
        accessKeyId = CREDENTIALS.accessKeyId,
        secret = CREDENTIALS.secretAccessKey,
    )

    // 保存済みの設定から作った入力は、シークレットの欄だけが空。保存済みの設定が無ければ、どの欄も空
    @Test
    fun formOfStoredSettingsLeavesSecretEmpty() {
        assertEquals(filled.copy(secret = ""), SettingsForm.of(SETTINGS))
        assertEquals(SettingsForm(), SettingsForm.of(null))
    }

    // リージョン・バケット・アクセスキー ID・シークレットは前後の空白を除く。プレフィックスはそのまま使う
    @Test
    fun trimsAllButPrefix() {
        val form = SettingsForm(
            region = " ${SETTINGS.region} ",
            bucket = " ${SETTINGS.bucket}\t",
            prefix = " /${SETTINGS.prefix}/",
            accessKeyId = " ${CREDENTIALS.accessKeyId}",
            secret = "${CREDENTIALS.secretAccessKey} \n",
        )

        assertEquals(SettingsFormResult.Complete(SETTINGS.copy(prefix = " /${SETTINGS.prefix}/")), form.toSettings(stored = null))
    }

    // シークレットの欄が空（空白だけを含む）なら、保存済みのシークレットを使う。入れれば、入れたものを使う
    @Test
    fun emptySecretUsesStoredSecret() {
        val stored = SETTINGS.copy(credentials = CREDENTIALS.copy(secretAccessKey = "stored-secret"))

        assertEquals(SettingsFormResult.Complete(stored), filled.copy(secret = "").toSettings(stored))
        assertEquals(SettingsFormResult.Complete(stored), filled.copy(secret = " \t").toSettings(stored))
        assertEquals(SettingsFormResult.Complete(SETTINGS), filled.toSettings(stored))
    }

    // 入力が要る欄が空なら、足りない欄を返す。プレフィックスは空でもよい。シークレットは、保存済みの設定が無いときだけ要る
    @Test
    fun reportsMissingFields() {
        val cases: List<Triple<SettingsForm, ConnectionSettings?, Set<SettingsField>>> = listOf(
            Triple(filled.copy(region = " "), null, setOf(SettingsField.REGION)),
            Triple(filled.copy(bucket = ""), null, setOf(SettingsField.BUCKET)),
            Triple(filled.copy(accessKeyId = ""), null, setOf(SettingsField.ACCESS_KEY_ID)),
            Triple(filled.copy(secret = ""), null, setOf(SettingsField.SECRET)),
            Triple(filled.copy(secret = " "), null, setOf(SettingsField.SECRET)),
            Triple(SettingsForm(), null, setOf(SettingsField.REGION, SettingsField.BUCKET, SettingsField.ACCESS_KEY_ID, SettingsField.SECRET)),
        )
        cases.forEach { (form, stored, expected) ->
            assertEquals(SettingsFormResult.Incomplete(expected), form.toSettings(stored), "$form")
        }
        assertEquals(SettingsFormResult.Complete(SETTINGS.copy(prefix = "")), filled.copy(prefix = "").toSettings(stored = null))
    }

    // 文字列にしても、アクセスキー ID とシークレットを出さない
    @Test
    fun toStringRedactsCredentials() {
        val text = filled.toString()

        assertFalse(CREDENTIALS.accessKeyId in text)
        assertFalse(CREDENTIALS.secretAccessKey in text)
    }
}
