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
 * リージョン・バケット・アクセスキー ID は前後の空白を除き、空なら足りないとする。プレフィックスはそのまま使い、空でもよい。
 * シークレットも前後の空白を除く。空なら [stored] のものを使い、[stored] も無ければ足りないとする。
 *
 * @param stored 保存済みの設定
 */
fun SettingsForm.toSettings(stored: ConnectionSettings?): SettingsFormResult {
    val region = region.trim()
    val bucket = bucket.trim()
    val accessKeyId = accessKeyId.trim()
    val secretAccessKey = secret.trim().ifEmpty { stored?.credentials?.secretAccessKey.orEmpty() }
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
