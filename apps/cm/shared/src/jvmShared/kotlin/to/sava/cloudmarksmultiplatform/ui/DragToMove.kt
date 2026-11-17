package to.sava.cloudmarksmultiplatform.ui

/**
 * リストの項目とフォルダ、ツリーのフォルダを、ドラッグして移せるか。ツリーの行とリストの行のどちらへも落とせる。デスクトップだけが移せる。
 * Android は移せない（リストとツリーをなぞる操作は、スクロールに使う）。
 */
internal expect val dragsToMove: Boolean
