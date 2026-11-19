package to.sava.cloudmarksmultiplatform.update

/**
 * アプリの更新の状態。設定の画面の「アプリの更新」の節が描く。
 *
 * @property version 動いているアプリのバージョン
 * @property check 更新を確認した結果
 * @property install 新しいバージョンをダウンロードしてから、インストーラへ渡すまでの段階。始めていなければ null
 * @property applyFailedVersionName 前の起動で更新を適用できなかったときの、適用しようとしたバージョン。伝えることが無ければ null
 */
data class UpdateState(
    val version: AppVersion,
    val check: UpdateCheck = UpdateCheck.NotChecked,
    val install: UpdateInstall? = null,
    val applyFailedVersionName: String? = null,
) {
    /**
     * 更新の確認を受け付けるか。確認している最中と、ダウンロードからインストーラへ渡すまでのあいだ（[UpdateInstall.inProgress]）は
     * 受け付けない。確認し直すと、ダウンロードしている配布物と確認の結果が食い違う。開発版でも受け付ける。
     */
    val canCheck: Boolean
        get() = check != UpdateCheck.Checking && install?.inProgress != true

    /**
     * 新しいバージョンのダウンロードを受け付けるか。配布版で、新しいバージョンがあると分かっていて、ダウンロードからインストーラへ渡す
     * までのあいだでないときだけ。開発版は、配布版とインストールの仕方が違うため受け付けない。
     */
    val canDownload: Boolean
        get() = version.distributed && check is UpdateCheck.Available && install?.inProgress != true
}

/** 更新を確認した結果。 */
sealed interface UpdateCheck {
    /** まだ確認していない。 */
    data object NotChecked : UpdateCheck

    /** 確認している。 */
    data object Checking : UpdateCheck

    /** 動いているバージョンが最新。配布しているバージョンが同じか古い。 */
    data object UpToDate : UpdateCheck

    /**
     * 新しいバージョンがある。
     *
     * @property versionName 新しいバージョンの文字列
     * @property sha256 新しいバージョンの配布物の SHA-256。小文字の 16 進。ダウンロードしたファイルを確かめるのに使う
     */
    data class Available(val versionName: String, val sha256: String) : UpdateCheck

    /** 確認できなかった。 */
    data class Failed(val failure: UpdateCheckFailure) : UpdateCheck
}

/** 更新を確認できなかった理由。細かい理由（HTTP の状態、例外）はログにだけ書く。 */
enum class UpdateCheckFailure {
    /** 更新情報を取れなかった。つながらない、HTTP の状態が成功でない、大きさが上限を超えるなど。 */
    UNREACHABLE,

    /** 更新情報の署名を取れなかったか、署名が合わなかった。 */
    SIGNATURE_UNVERIFIED,

    /** 署名は合ったが、更新情報を読めなかった。形が合わない、このプラットフォームの項目が無いなど。 */
    UNREADABLE,
}

/** 新しいバージョンをダウンロードしてから、インストーラへ渡すまでの段階。 */
sealed interface UpdateInstall {
    /**
     * ダウンロードからインストーラへ渡すまでの途中か。途中のあいだは、ダウンロードをやり直させず、確認もし直させない。
     * [Failed] は途中ではない。理由を出したまま、確認し直すこともダウンロードし直すこともできる。
     */
    val inProgress: Boolean
        get() = when (this) {
            is Downloading, Verifying, ReadyToApply, Launching -> true
            is Failed -> false
        }

    /**
     * 配布物をダウンロードしている。
     *
     * @property receivedBytes 受け取ったバイト数
     * @property totalBytes 全体のバイト数。分からなければ 0
     */
    data class Downloading(val receivedBytes: Long, val totalBytes: Long) : UpdateInstall {
        /** 0 から 1 までの進み具合。全体の大きさが分からなければ null。 */
        val fraction: Float?
            get() = if (totalBytes > 0) (receivedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null
    }

    /** ダウンロードした配布物の SHA-256 を確かめている。 */
    data object Verifying : UpdateInstall

    /**
     * 確かめ終え、インストールしてよいかの返事を待っている。インストールするとアプリをいったん終了するプラットフォーム（デスクトップ）
     * だけがこの段階を通る。端末がインストールしてよいかを尋ねるプラットフォーム（Android）は、確かめ終えたらそのまま [Launching] へ進む。
     */
    data object ReadyToApply : UpdateInstall

    /** インストーラへ渡した。 */
    data object Launching : UpdateInstall

    /** ダウンロードからインストーラへ渡すまでのどこかで止まった。 */
    data class Failed(val failure: UpdateInstallFailure) : UpdateInstall
}

/** 新しいバージョンをインストーラへ渡せなかった理由。細かい理由（HTTP の状態、例外）はログにだけ書く。 */
enum class UpdateInstallFailure {
    /** 配布物をダウンロードできなかった。 */
    DOWNLOAD_FAILED,

    /** ダウンロードした配布物の SHA-256 が更新情報と合わなかったか、確かめるために読めなかった。 */
    VERIFICATION_FAILED,

    /** 確かめた配布物を、インストーラへ渡せなかった。 */
    HAND_OVER_FAILED,
}
