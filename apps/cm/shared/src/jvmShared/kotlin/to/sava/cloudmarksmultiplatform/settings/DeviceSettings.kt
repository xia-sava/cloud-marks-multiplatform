package to.sava.cloudmarksmultiplatform.settings

/** 同時に読み込むタブの数の下限。 */
const val MINIMUM_TAB_COUNT = 1

/** 先に読み込むタブの数の下限。0 は、すべてのタブを順に読み込むことを表す。 */
const val MINIMUM_LOAD_AHEAD = 0

/** 同時に読み込むタブの数の既定。 */
private const val DEFAULT_LOAD_AT_ONCE = 3

/** 先に読み込むタブの数の既定。 */
private const val DEFAULT_LOAD_AHEAD = 20

/**
 * 2 つ以上の項目をまとめてブラウザで開くときの、タブの読み込み方。
 * [loadAtOnce] は [MINIMUM_TAB_COUNT] 以上、[loadAhead] は [MINIMUM_LOAD_AHEAD] 以上。
 *
 * @property loadAtOnce 同時に読み込むタブの数
 * @property loadAhead 先頭からこの数のタブを、少しずつ読み込む。これより後ろのタブは、表示したときに読み込む。0 なら、すべてのタブを順に読み込む
 */
data class TabLoading(
    val loadAtOnce: Int = DEFAULT_LOAD_AT_ONCE,
    val loadAhead: Int = DEFAULT_LOAD_AHEAD,
) {
    init {
        require(loadAtOnce >= MINIMUM_TAB_COUNT) { "同時に読み込むタブの数 $loadAtOnce が $MINIMUM_TAB_COUNT 未満" }
        require(loadAhead >= MINIMUM_LOAD_AHEAD) { "先に読み込むタブの数 $loadAhead が $MINIMUM_LOAD_AHEAD 未満" }
    }
}

/**
 * 端末ごとの設定。S3 に載せない。
 *
 * @property displayScale 画面全体の表示の大きさ
 * @property tabLoading 2 つ以上の項目をまとめてブラウザで開くときの、タブの読み込み方
 */
data class DeviceSettings(
    val displayScale: DisplayScale = DisplayScale.DEFAULT,
    val tabLoading: TabLoading = TabLoading(),
)

/** 入力した [text] を、同時に読み込むタブの数として読む。前後の空白は除く。[MINIMUM_TAB_COUNT] 以上の整数でなければ null。 */
fun parseTabCount(text: String): Int? = parseCount(text, MINIMUM_TAB_COUNT)

/** 入力した [text] を、先に読み込むタブの数として読む。前後の空白は除く。[MINIMUM_LOAD_AHEAD] 以上の整数でなければ null。 */
fun parseLoadAhead(text: String): Int? = parseCount(text, MINIMUM_LOAD_AHEAD)

private fun parseCount(text: String, minimum: Int): Int? = text.trim().toIntOrNull()?.takeIf { it >= minimum }
