package to.sava.cloudmarksmultiplatform.settings

/** 表示の大きさの段の、百分率。小さい順。 */
private val DISPLAY_SCALE_PERCENTS = listOf(80, 90, 100, 110, 125, 150, 175, 200)

/** 既定の表示の大きさの、百分率。 */
private const val DEFAULT_DISPLAY_SCALE_PERCENT = 100

/** 百分率を倍率に直すときの、割る数。 */
private const val PERCENT = 100f

/**
 * 画面全体の表示の大きさ。[STEPS] の段のどれかで、百分率 [percent] で表す。端末ごとの設定で、S3 に載せない。
 */
@JvmInline
value class DisplayScale private constructor(val percent: Int) {
    /** 画面の密度に掛ける倍率。 */
    val factor: Float get() = percent / PERCENT

    /** 1 段大きい表示の大きさ。いちばん大きい段なら、そのまま。 */
    fun larger(): DisplayScale = STEPS.firstOrNull { it.percent > percent } ?: STEPS.last()

    /** 1 段小さい表示の大きさ。いちばん小さい段なら、そのまま。 */
    fun smaller(): DisplayScale = STEPS.lastOrNull { it.percent < percent } ?: STEPS.first()

    companion object {
        /** 選べる段。小さい順。 */
        val STEPS: List<DisplayScale> = DISPLAY_SCALE_PERCENTS.map(::DisplayScale)

        /** 既定の表示の大きさ。 */
        val DEFAULT: DisplayScale = DisplayScale(DEFAULT_DISPLAY_SCALE_PERCENT)

        /** 百分率が [percent] の段。段に無ければ null。 */
        fun ofPercent(percent: Int): DisplayScale? = STEPS.firstOrNull { it.percent == percent }
    }
}
