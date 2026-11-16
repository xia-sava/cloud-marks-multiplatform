package to.sava.cloudmarksmultiplatform.settings

/** 認証情報の暗号化に添えるエントロピー。書いたファイルを読み続けるため、変わらないことを確かめる。 */
internal val EXPECTED_CREDENTIALS_ENTROPY: ByteArray = "to.sava.cloudmarksmultiplatform/credentials".encodeToByteArray()

/** AWS の文書が例に使う認証情報。 */
internal val CREDENTIALS = S3Credentials(
    accessKeyId = "AKIAIOSFODNN7EXAMPLE",
    secretAccessKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
)

internal val SETTINGS = ConnectionSettings(
    region = "ap-northeast-1",
    bucket = "example-bucket",
    prefix = "cloud_marks",
    credentials = CREDENTIALS,
)

/** [other] が、連続したバイト列としてこの中に現れるか。 */
internal fun ByteArray.containsSequence(other: ByteArray): Boolean =
    (0..size - other.size).any { start -> other.indices.all { this[start + it] == other[it] } }
