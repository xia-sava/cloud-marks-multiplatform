package to.sava.cloudmarksmultiplatform.files

/**
 * ネイティブの関数の呼び出しが許されていない失敗（[IllegalCallerException]）を、後から調べるための短い文にする。
 *
 * 例外の種類だけを使う。例外のメッセージは、呼び出したコードの jar のパス（利用者名を含む）を含むため使わない。
 */
internal fun describeIllegalCaller(e: IllegalCallerException): String = e.javaClass.simpleName
