package to.sava.cloudmarksmultiplatform.settings

/** 認証情報を出さない `toString`（[S3Credentials.toString]・[SettingsForm.toString]）が、値の代わりに出す文字列。 */
internal const val REDACTED = "<redacted>"

/**
 * S3 の接続先と認証情報。端末に保存する設定。
 *
 * @property prefix キーの前に付けるプレフィックス。空ならバケットの直下
 */
data class ConnectionSettings(
    val region: String,
    val bucket: String,
    val prefix: String,
    val credentials: S3Credentials,
)

/** S3 の認証情報。[toString] はどちらの値も出さない。 */
data class S3Credentials(
    val accessKeyId: String,
    val secretAccessKey: String,
) {
    override fun toString(): String = "S3Credentials(accessKeyId=$REDACTED, secretAccessKey=$REDACTED)"
}
