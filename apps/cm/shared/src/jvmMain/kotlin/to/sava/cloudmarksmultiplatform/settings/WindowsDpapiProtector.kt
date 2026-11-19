package to.sava.cloudmarksmultiplatform.settings

import to.sava.cloudmarksmultiplatform.files.describeThrowable
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.StructLayout
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.invoke.MethodHandle

private const val CRYPT32 = "crypt32.dll"
private const val KERNEL32 = "kernel32.dll"
private const val CRYPT_PROTECT_DATA = "CryptProtectData"
private const val CRYPT_UNPROTECT_DATA = "CryptUnprotectData"
private const val LOCAL_FREE = "LocalFree"

/** [Linker.Option.captureCallState] で取る値の名前。 */
private const val GET_LAST_ERROR = "GetLastError"

/** 暗号化・復号のあいだに、利用者へ何も尋ねない。尋ねる必要があれば失敗する。 */
private const val CRYPTPROTECT_UI_FORBIDDEN = 0x1

/** `BOOL` の偽。 */
private const val FALSE = 0

/** `DATA_BLOB` の欄の名前。 */
private const val CB_DATA = "cbData"
private const val PB_DATA = "pbData"

private const val OS_NAME = "os.name"
private const val WINDOWS_OS_NAME_PREFIX = "Windows"

/** 暗号化して復号すると元に戻るかを確かめるための固定のバイト列。秘密ではない。 */
private val PROBE_DATA = "to.sava.cloudmarksmultiplatform/probe".encodeToByteArray()
private val PROBE_ENTROPY = "to.sava.cloudmarksmultiplatform/probe-entropy".encodeToByteArray()

/**
 * Windows の DPAPI（`CryptProtectData` / `CryptUnprotectData`）で暗号化する [SecretProtector]。
 *
 * 暗号文はログオン中のユーザーに結びつき、同じ PC のほかのユーザーや、ファイルを持ち出したほかの PC では復号できない。
 * 利用者へ何も尋ねない（`CRYPTPROTECT_UI_FORBIDDEN`）。説明の文字列は渡さない。
 *
 * 関数は FFM（`java.lang.foreign`）で呼ぶ。警告を出さずに呼ぶには、JVM の起動引数に `--enable-native-access=ALL-UNNAMED` が要る。
 * 最初に使うときに一度だけ DLL を読み込み、暗号化して復号すると元に戻るかを確かめる。Windows でない、DLL を読み込めない、
 * 元に戻らない、のいずれかなら使えないものとし、以後の暗号化・復号はどれも [SecretProtectionException] になる。
 */
object WindowsDpapiProtector : SecretProtector {
    private val availability: Availability by lazy { probe() }

    override fun protect(data: ByteArray, entropy: ByteArray): ByteArray = dpapi().protect(data, entropy)

    override fun unprotect(data: ByteArray, entropy: ByteArray): ByteArray = dpapi().unprotect(data, entropy)

    private fun dpapi(): Dpapi =
        when (val current = availability) {
            is Availability.Available -> current.dpapi
            is Availability.Unavailable -> throw SecretProtectionException("DPAPI を使えない: ${current.reason}")
        }
}

/** DPAPI を使えるか。 */
private sealed interface Availability {
    class Available(val dpapi: Dpapi) : Availability

    class Unavailable(val reason: String) : Availability
}

/** DLL を読み込み、暗号化して復号すると元に戻るかを確かめる。 */
private fun probe(): Availability {
    if (!System.getProperty(OS_NAME).orEmpty().startsWith(WINDOWS_OS_NAME_PREFIX)) {
        return Availability.Unavailable("Windows でない")
    }
    return try {
        Dpapi().let { dpapi ->
            val restored = dpapi.unprotect(dpapi.protect(PROBE_DATA, PROBE_ENTROPY), PROBE_ENTROPY)
            if (restored.contentEquals(PROBE_DATA)) {
                Availability.Available(dpapi)
            } else {
                Availability.Unavailable("復号した結果が元のバイト列と違う")
            }
        }
    } catch (e: SecretProtectionException) {
        Availability.Unavailable(e.message)
    } catch (e: IllegalArgumentException) {
        // DLL を読み込めない、または関数や構造体のレイアウトを組めない
        Availability.Unavailable(describeThrowable(e))
    } catch (e: UnsupportedOperationException) {
        // この JVM がネイティブの関数の呼び出しに対応していない
        Availability.Unavailable(describeThrowable(e))
    } catch (e: IllegalCallerException) {
        // ネイティブの関数の呼び出しが許されていない
        Availability.Unavailable(describeThrowable(e))
    }
}

/** DPAPI の関数を呼ぶ。作るときに DLL を読み込み、関数を探して呼び出せるようにする。 */
private class Dpapi {
    private val linker = Linker.nativeLinker()

    /** `DATA_BLOB`。`DWORD cbData` と `BYTE* pbData`。64 ビットでは、ポインタの位置を揃えるパディングが間に入る。 */
    private val dataBlob: StructLayout = MemoryLayout.structLayout(
        JAVA_INT.withName(CB_DATA),
        MemoryLayout.paddingLayout(ADDRESS.byteAlignment() - JAVA_INT.byteSize()),
        ADDRESS.withName(PB_DATA),
    )
    private val cbDataOffset = dataBlob.byteOffset(MemoryLayout.PathElement.groupElement(CB_DATA))
    private val pbDataOffset = dataBlob.byteOffset(MemoryLayout.PathElement.groupElement(PB_DATA))

    private val callState: StructLayout = Linker.Option.captureStateLayout()
    private val lastErrorOffset = callState.byteOffset(MemoryLayout.PathElement.groupElement(GET_LAST_ERROR))

    /**
     * `CryptProtectData` と `CryptUnprotectData` の形。戻り値は `BOOL`。
     * 引数は 7 つで、6 番目の `dwFlags`（`DWORD`）のほかはポインタ。
     */
    private val cryptFunction = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS)

    private val crypt32 = SymbolLookup.libraryLookup(CRYPT32, Arena.global())
    private val kernel32 = SymbolLookup.libraryLookup(KERNEL32, Arena.global())

    private val captureLastError = Linker.Option.captureCallState(GET_LAST_ERROR)
    private val cryptProtectData = crypt32.downcall(CRYPT_PROTECT_DATA, cryptFunction, captureLastError)
    private val cryptUnprotectData = crypt32.downcall(CRYPT_UNPROTECT_DATA, cryptFunction, captureLastError)
    private val localFree = kernel32.downcall(LOCAL_FREE, FunctionDescriptor.of(ADDRESS, ADDRESS))

    fun protect(data: ByteArray, entropy: ByteArray): ByteArray =
        call(cryptProtectData, CRYPT_PROTECT_DATA, data, entropy)

    fun unprotect(data: ByteArray, entropy: ByteArray): ByteArray =
        call(cryptUnprotectData, CRYPT_UNPROTECT_DATA, data, entropy)

    private fun SymbolLookup.downcall(
        name: String,
        descriptor: FunctionDescriptor,
        vararg options: Linker.Option,
    ): MethodHandle =
        find(name)
            .orElseThrow { SecretProtectionException("$name が見つからない") }
            .let { linker.downcallHandle(it, descriptor, *options) }

    /**
     * `CryptProtectData` か `CryptUnprotectData` を呼び、出力のバイト列を返す。失敗したら `GetLastError` の値を添えて投げる。
     *
     * 説明の文字列（暗号化では入力、復号では出力）、予約の引数、尋ねるときの設定には、どれも null を渡す。
     */
    private fun call(function: MethodHandle, name: String, data: ByteArray, entropy: ByteArray): ByteArray =
        Arena.ofConfined().use { arena ->
            val state = arena.allocate(callState)
            val output = arena.allocate(dataBlob)
            val result = function.invoke(
                state,
                arena.blobOf(data),
                MemorySegment.NULL,
                arena.blobOf(entropy),
                MemorySegment.NULL,
                MemorySegment.NULL,
                CRYPTPROTECT_UI_FORBIDDEN,
                output,
            ) as Int
            if (result == FALSE) {
                val lastError = state.get(JAVA_INT, lastErrorOffset)
                throw SecretProtectionException("$name が失敗した（GetLastError: 0x%08X）".format(lastError))
            }
            takeBytes(output)
        }

    /** [bytes] をコピーした `DATA_BLOB` を、この [Arena] の中に作る。 */
    private fun Arena.blobOf(bytes: ByteArray): MemorySegment =
        allocate(dataBlob).also { blob ->
            blob.set(JAVA_INT, cbDataOffset, bytes.size)
            blob.set(ADDRESS, pbDataOffset, allocateFrom(JAVA_BYTE, *bytes))
        }

    /** 出力の `DATA_BLOB` が指すバイト列をコピーし、DPAPI が確保したバッファを `LocalFree` で解放する。 */
    private fun takeBytes(blob: MemorySegment): ByteArray {
        val buffer = blob.get(ADDRESS, pbDataOffset)
        return try {
            buffer.reinterpret(Integer.toUnsignedLong(blob.get(JAVA_INT, cbDataOffset))).toArray(JAVA_BYTE)
        } finally {
            // 解放の失敗は、コピーしたバイト列に影響しないため問わない
            localFree.invoke(buffer)
        }
    }
}
