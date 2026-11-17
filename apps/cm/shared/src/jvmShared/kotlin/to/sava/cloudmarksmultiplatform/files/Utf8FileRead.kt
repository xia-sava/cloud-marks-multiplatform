package to.sava.cloudmarksmultiplatform.files

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * [path] の中身を、UTF-8 の文字列として読む。読み込みはブロックする。呼び出し側が I/O 用のスレッドで呼ぶ。
 *
 * `Files.readString` は Android では API 36.1 からなので、バイト列で読んでから文字列にする。
 *
 * @throws java.nio.file.NoSuchFileException ファイルが無い
 * @throws java.nio.charset.CharacterCodingException UTF-8 として読めない。[IOException] の一種
 * @throws IOException ほかの理由で読めなかった
 */
internal fun readUtf8File(path: Path): String = Files.readAllBytes(path).decodeToString(throwOnInvalidSequence = true)
