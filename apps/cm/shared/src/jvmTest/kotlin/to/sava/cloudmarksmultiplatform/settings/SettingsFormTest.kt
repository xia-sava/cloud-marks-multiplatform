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

    // 1 行の欄に入れたものは、最初の改行（\n でも \r でも）より前だけを受け付ける。改行が無ければそのまま
    @Test
    fun firstLineDropsLineBreakAndRest() {
        val cases = listOf(
            "bucket\n─────" to "bucket",
            "bucket\r─────" to "bucket",
            "bucket\r\n─────\nmore" to "bucket",
            "\nbucket" to "",
            "bucket " to "bucket ",
            "" to "",
        )
        cases.forEach { (input, expected) -> assertEquals(expected, firstLineOf(input), input) }
    }

    // 設定を作るときも、どの欄も最初の改行とその後ろを使わない（保存済みの値に改行が入っていても、保存し直せば除く）
    @Test
    fun toSettingsDropsLineBreakAndRest() {
        val form = SettingsForm(
            region = "${SETTINGS.region}\n─────",
            bucket = "${SETTINGS.bucket}\r─────",
            prefix = "${SETTINGS.prefix}\r\n─────",
            accessKeyId = "${CREDENTIALS.accessKeyId} \n─────",
            secret = "${CREDENTIALS.secretAccessKey}\n─────",
        )

        assertEquals(SettingsFormResult.Complete(SETTINGS), form.toSettings(stored = null))
    }

    // シークレットの欄が空のときに使う保存済みのシークレットも、最初の改行とその後ろを使わない
    @Test
    fun storedSecretDropsLineBreakAndRest() {
        val stored = SETTINGS.copy(credentials = CREDENTIALS.copy(secretAccessKey = "${CREDENTIALS.secretAccessKey}\n─────"))

        assertEquals(SettingsFormResult.Complete(SETTINGS), filled.copy(secret = "").toSettings(stored))
    }

    // 改行より前が空なら、その欄は足りないとする
    @Test
    fun fieldEmptyBeforeLineBreakIsMissing() {
        val form = filled.copy(region = "\n${SETTINGS.region}", secret = "\r${CREDENTIALS.secretAccessKey}")

        assertEquals(SettingsFormResult.Incomplete(setOf(SettingsField.REGION, SettingsField.SECRET)), form.toSettings(stored = null))
    }

    // 文字列にしても、アクセスキー ID とシークレットを出さない
    @Test
    fun toStringRedactsCredentials() {
        val text = filled.toString()

        assertFalse(CREDENTIALS.accessKeyId in text)
        assertFalse(CREDENTIALS.secretAccessKey in text)
    }
}
