package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 画面を、タッチの画面の振る舞いで動かすプラットフォームか。Android は動かし、デスクトップは動かさない。
 *
 * タッチの画面の振る舞いでは、項目を押すと（選んでいる項目が無ければ）既定のブラウザで開き、項目を長押しすると（選んでいる項目が無ければ）
 * 項目のメニューを出し（[ItemLongPressMenuItems]）、項目を選んでいるあいだは AppBar を選ぶ状態の形に替え（[CloudMarksTopBar]）、
 * ツリーとリストのフォルダの行を長押しするとフォルダのメニューを出し、システムの戻る操作を受ける（[SystemBackHandler]）。
 */
internal expect val usesTouchScreen: Boolean

/**
 * 画面を、タッチの画面の振る舞い（[usesTouchScreen]）で動かすか。既定はプラットフォームの値。画面のテストは、両方の振る舞いを試すために
 * 入れ替える。
 */
internal val LocalTouchScreen = staticCompositionLocalOf { usesTouchScreen }
