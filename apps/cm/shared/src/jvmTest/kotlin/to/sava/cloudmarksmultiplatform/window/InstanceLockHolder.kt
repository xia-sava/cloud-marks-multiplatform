package to.sava.cloudmarksmultiplatform.window

import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * テストから別のプロセスとして起動し、引数で渡したファイルの OS のロックを取って持つ。
 *
 * ロックを取れたら [LOCK_HELD_LINE] を標準出力に 1 行出す。標準入力が閉じるまでロックを持ち、閉じたらロックを外して終わる。
 * Kotlin の標準ライブラリのほかには何も使わないので、クラスパスはこのクラスの場所と標準ライブラリの場所だけでよい。
 */
object InstanceLockHolder {
    /** ロックを取れたことを知らせる行。 */
    const val LOCK_HELD_LINE = "lock-held"

    @JvmStatic
    fun main(args: Array<String>) {
        FileChannel.open(Path.of(args.single()), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock()
            println(LOCK_HELD_LINE)
            System.out.flush()
            System.`in`.readAllBytes()
        }
    }
}
