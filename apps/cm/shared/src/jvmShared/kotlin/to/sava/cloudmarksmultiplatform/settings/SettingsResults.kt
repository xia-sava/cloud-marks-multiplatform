package to.sava.cloudmarksmultiplatform.settings

/**
 * 設定のファイルを読んだ結果。
 *
 * 失敗の `detail` は後から調べるための短い文で、認証情報を含まない。
 */
sealed interface SettingsReadResult {
    /** ファイルが無い。まだ設定していない。 */
    data object NotFound : SettingsReadResult

    /** 読めた。 */
    data class Found(val settings: ConnectionSettings) : SettingsReadResult

    /** 中身が壊れている。JSON として読めない、必須の値が無い、復号した認証情報が読めない。 */
    data class Corrupt(val detail: String) : SettingsReadResult

    /** 認証情報を復号できない。別のユーザーや別の PC で暗号化された、暗号化の仕組みがこの環境で使えない、など。 */
    data class Undecryptable(val detail: String) : SettingsReadResult

    /** 読み込みの I/O の失敗。 */
    data class Failed(val detail: String) : SettingsReadResult
}

/**
 * 設定のファイルを書いた結果。
 *
 * 失敗の `detail` は後から調べるための短い文で、認証情報を含まない。
 */
sealed interface SettingsWriteResult {
    /** 書けた。 */
    data object Success : SettingsWriteResult

    /** 認証情報を暗号化する仕組みが使えないので、書かなかった。ファイルもディレクトリも作っていない。 */
    data class ProtectionUnavailable(val detail: String) : SettingsWriteResult

    /** 書き込みの I/O の失敗。元のファイルは置き換えていない。 */
    data class Failed(val detail: String) : SettingsWriteResult
}
