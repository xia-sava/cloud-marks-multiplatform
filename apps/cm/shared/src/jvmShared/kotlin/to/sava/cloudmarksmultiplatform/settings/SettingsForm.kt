package to.sava.cloudmarksmultiplatform.settings

/** 設定の画面の、入力が要る欄。プレフィックスは空でもよいので含まない。 */
enum class SettingsField { REGION, BUCKET, ACCESS_KEY_ID, SECRET }

/**
 * 設定の画面に入力した値。[toString] はアクセスキー ID とシークレットを出さない。
 *
 * @property prefix 空でもよい（バケットの直下）
 * @property secret 保存済みの設定があるときは、変えるときだけ入れる。空（空白だけを含む）なら保存済みのものを使う
 */
data class SettingsForm(
    val region: String = "",
    val bucket: String = "",
    val prefix: String = "",
    val accessKeyId: String = "",
    val secret: String = "",
) {
    override fun toString(): String =
        "SettingsForm(region=$region, bucket=$bucket, prefix=$prefix, accessKeyId=$REDACTED, secret=$REDACTED)"

    companion object {
        /** 保存済みの設定 [stored] の値を入れた入力。シークレットの欄は空にする。[stored] が無ければ、どの欄も空。 */
        fun of(stored: ConnectionSettings?): SettingsForm =
            stored?.let {
                SettingsForm(
                    region = it.region,
                    bucket = it.bucket,
                    prefix = it.prefix,
                    accessKeyId = it.credentials.accessKeyId,
                )
            } ?: SettingsForm()
    }
}

/**
 * 設定の画面の 1 行の欄に入れた [input] の、最初の改行（`\n` か `\r`）より前。改行とその後ろは受け付けない（貼り付けたものを含む）。
 * 改行が無ければ [input] のまま。
 */
internal fun firstLineOf(input: String): String = input.takeWhile { it != '\n' && it != '\r' }

/** 入力から設定を作った結果。 */
sealed interface SettingsFormResult {
    /** 作れた。 */
    data class Complete(val settings: ConnectionSettings) : SettingsFormResult

    /** 入力の足りない欄がある。 */
    data class Incomplete(val fields: Set<SettingsField>) : SettingsFormResult
}

/**
 * 入力から、保存する設定を作る。
 *
 * どの欄も、最初の改行とその後ろは使わない（[firstLineOf]）。
 * リージョン・バケット・アクセスキー ID は前後の空白を除き、空なら足りないとする。プレフィックスはそのまま使い、空でもよい。
 * シークレットも前後の空白を除く。空なら [stored] のもの（最初の改行とその後ろは使わない）を使い、[stored] も無ければ足りないとする。
 *
 * @param stored 保存済みの設定
 */
fun SettingsForm.toSettings(stored: ConnectionSettings?): SettingsFormResult {
    val region = firstLineOf(region).trim()
    val bucket = firstLineOf(bucket).trim()
    val prefix = firstLineOf(prefix)
    val accessKeyId = firstLineOf(accessKeyId).trim()
    val secretAccessKey = firstLineOf(secret).trim().ifEmpty { firstLineOf(stored?.credentials?.secretAccessKey.orEmpty()) }
    val missing = buildSet {
        if (region.isEmpty()) add(SettingsField.REGION)
        if (bucket.isEmpty()) add(SettingsField.BUCKET)
        if (accessKeyId.isEmpty()) add(SettingsField.ACCESS_KEY_ID)
        if (secretAccessKey.isEmpty()) add(SettingsField.SECRET)
    }
    if (missing.isNotEmpty()) return SettingsFormResult.Incomplete(missing)
    return SettingsFormResult.Complete(
        ConnectionSettings(region, bucket, prefix, S3Credentials(accessKeyId, secretAccessKey)),
    )
}
