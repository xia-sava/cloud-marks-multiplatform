package to.sava.cloudmarksmultiplatform.browser

import to.sava.cloudmarksmultiplatform.files.describeIllegalCaller
import to.sava.cloudmarksmultiplatform.files.describeThrowable
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_CHAR
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.invoke.MethodHandle
import java.nio.charset.StandardCharsets.UTF_16LE

/** レジストリのルートのキー。 */
internal enum class RegistryRoot {
    /** `HKEY_CURRENT_USER`。 */
    CURRENT_USER,

    /** `HKEY_LOCAL_MACHINE`。 */
    LOCAL_MACHINE,
}

/** レジストリを読むだけの口。書く関数は持たない。 */
internal interface RegistryReader {
    /**
     * [root] の [path] のキーの、子のキーの名前。キーが無ければ空。
     *
     * @throws RegistryException 読めなかった
     */
    fun subKeyNames(root: RegistryRoot, path: String): List<String>

    /**
     * [root] の [path] のキーの、名前が [name] の文字列の値。[name] が null なら既定の値。環境変数を含む文字列の値は、展開して返す。
     * キーか値が無いか、文字列の値でなければ null。
     *
     * @throws RegistryException 読めなかった
     */
    fun stringValue(root: RegistryRoot, path: String, name: String?): String?
}

/** レジストリを読めなかった。[message] は後から追うための短い文。 */
internal class RegistryException(override val message: String) : Exception(message)

private const val ADVAPI32 = "advapi32.dll"
private const val REG_OPEN_KEY_EX = "RegOpenKeyExW"
private const val REG_ENUM_KEY_EX = "RegEnumKeyExW"
private const val REG_GET_VALUE = "RegGetValueW"
private const val REG_CLOSE_KEY = "RegCloseKey"

/**
 * `HKEY_CURRENT_USER` と `HKEY_LOCAL_MACHINE` の値。どちらも `LONG` の負の値を、ポインタの幅へ符号を広げたもの。
 */
private val HKEY_CURRENT_USER_VALUE = 0x80000001.toInt().toLong()
private val HKEY_LOCAL_MACHINE_VALUE = 0x80000002.toInt().toLong()

/** キーを読むための権限（`KEY_READ`）。 */
private const val KEY_READ = 0x20019

/** `RegGetValueW` で、文字列の値（`REG_SZ`。環境変数を含む文字列は展開して `REG_SZ` として扱う）だけを受け取る。 */
private const val RRF_RT_REG_SZ = 0x2

private const val ERROR_SUCCESS = 0
private const val ERROR_FILE_NOT_FOUND = 2
private const val ERROR_MORE_DATA = 234
private const val ERROR_NO_MORE_ITEMS = 259
private const val ERROR_UNSUPPORTED_TYPE = 1630

/** キーの名前の長さの上限（文字数）。終わりの null を含まない。 */
private const val MAX_KEY_NAME_CHARS = 255

/**
 * 値を読む回数の上限。大きさを尋ねてから読むあいだに大きさが変わったら（`ERROR_MORE_DATA`）、そのとき返る大きさで 1 回だけ読み直し、
 * それでも変わったら失敗にする。
 */
private const val MAX_VALUE_READS = 2

/** UTF-16 の 1 文字のバイト数。 */
private const val UTF_16_CHAR_BYTES = 2L

/**
 * Windows のレジストリを、`advapi32` の関数を FFM（`java.lang.foreign`）で呼んで読む [RegistryReader]。
 *
 * 警告を出さずに呼ぶには、JVM の起動引数に `--enable-native-access=ALL-UNNAMED` が要る。最初に使うときに DLL を読み込む。
 * Windows でない、DLL を読み込めない、関数を呼べないときは、どの関数も [RegistryException] になる。
 * 読み込みはブロックする。呼び出し側が I/O 用のスレッドで呼ぶ。
 */
internal object WindowsRegistry : RegistryReader {
    private val advapi32: Advapi32 by lazy { Advapi32() }

    override fun subKeyNames(root: RegistryRoot, path: String): List<String> = linked { it.subKeyNames(root.handle, path) }

    override fun stringValue(root: RegistryRoot, path: String, name: String?): String? =
        linked { it.stringValue(root.handle, path, name) }

    private val RegistryRoot.handle: MemorySegment
        get() = MemorySegment.ofAddress(
            when (this) {
                RegistryRoot.CURRENT_USER -> HKEY_CURRENT_USER_VALUE
                RegistryRoot.LOCAL_MACHINE -> HKEY_LOCAL_MACHINE_VALUE
            },
        )

    /** DLL を読み込んだ [Advapi32] で [block] を走らせる。読み込めない・呼べないときは [RegistryException] にする。 */
    private fun <T> linked(block: (Advapi32) -> T): T =
        try {
            block(advapi32)
        } catch (e: IllegalArgumentException) {
            // DLL を読み込めない、または関数を探せない
            throw RegistryException(describeThrowable(e))
        } catch (e: UnsupportedOperationException) {
            // この JVM がネイティブの関数の呼び出しに対応していない
            throw RegistryException(describeThrowable(e))
        } catch (e: IllegalCallerException) {
            // ネイティブの関数の呼び出しが許されていない
            throw RegistryException(describeIllegalCaller(e))
        }
}

/** `advapi32` のレジストリの関数を呼ぶ。作るときに DLL を読み込み、関数を探して呼び出せるようにする。 */
private class Advapi32 {
    private val linker = Linker.nativeLinker()
    private val library = SymbolLookup.libraryLookup(ADVAPI32, Arena.global())

    /** `LSTATUS RegOpenKeyExW(HKEY, LPCWSTR, DWORD, REGSAM, PHKEY)`。 */
    private val regOpenKeyEx = downcall(REG_OPEN_KEY_EX, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS))

    /** `LSTATUS RegEnumKeyExW(HKEY, DWORD, LPWSTR, LPDWORD, LPDWORD, LPWSTR, LPDWORD, PFILETIME)`。 */
    private val regEnumKeyEx = downcall(
        REG_ENUM_KEY_EX,
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS),
    )

    /** `LSTATUS RegGetValueW(HKEY, LPCWSTR, LPCWSTR, DWORD, LPDWORD, PVOID, LPDWORD)`。 */
    private val regGetValue = downcall(
        REG_GET_VALUE,
        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS),
    )

    /** `LSTATUS RegCloseKey(HKEY)`。 */
    private val regCloseKey = downcall(REG_CLOSE_KEY, FunctionDescriptor.of(JAVA_INT, ADDRESS))

    private fun downcall(name: String, descriptor: FunctionDescriptor): MethodHandle =
        library.find(name)
            .orElseThrow { IllegalArgumentException("$name が見つからない") }
            .let { linker.downcallHandle(it, descriptor) }

    fun subKeyNames(root: MemorySegment, path: String): List<String> =
        Arena.ofConfined().use { arena ->
            val key = openKey(arena, root, path) ?: return emptyList()
            try {
                enumerateSubKeys(arena, key)
            } finally {
                // 閉じる失敗は、読んだ名前に影響しないため問わない
                regCloseKey.invoke(key)
            }
        }

    fun stringValue(root: MemorySegment, path: String, name: String?): String? =
        Arena.ofConfined().use { arena ->
            val subKey = arena.allocateFrom(path, UTF_16LE)
            val valueName = name?.let { arena.allocateFrom(it, UTF_16LE) } ?: MemorySegment.NULL
            val size = arena.allocate(JAVA_INT)
            val probe = regGetValue.invoke(root, subKey, valueName, RRF_RT_REG_SZ, MemorySegment.NULL, MemorySegment.NULL, size) as Int
            if (probe.isMissingValue()) return null
            if (probe != ERROR_SUCCESS) throw RegistryException(failure(REG_GET_VALUE, probe))
            repeat(MAX_VALUE_READS) {
                val bytes = Integer.toUnsignedLong(size.get(JAVA_INT, 0)).coerceAtLeast(UTF_16_CHAR_BYTES)
                val buffer = arena.allocate(bytes, UTF_16_CHAR_BYTES)
                size.set(JAVA_INT, 0, buffer.byteSize().toInt())
                val status = regGetValue.invoke(root, subKey, valueName, RRF_RT_REG_SZ, MemorySegment.NULL, buffer, size) as Int
                when {
                    status == ERROR_SUCCESS -> return buffer.getString(0, UTF_16LE)
                    status.isMissingValue() -> return null
                    // 読むあいだに値が大きくなった。size に要る大きさが入っているので、その大きさで読み直す
                    status == ERROR_MORE_DATA -> return@repeat
                    else -> throw RegistryException(failure(REG_GET_VALUE, status))
                }
            }
            throw RegistryException("$REG_GET_VALUE で読み直しても、読むあいだに値の大きさが変わった")
        }

    /** [root] の [path] のキーを開く。キーが無ければ null。開いたキーは呼び出し側が閉じる。 */
    private fun openKey(arena: Arena, root: MemorySegment, path: String): MemorySegment? {
        val opened = arena.allocate(ADDRESS)
        val status = regOpenKeyEx.invoke(root, arena.allocateFrom(path, UTF_16LE), 0, KEY_READ, opened) as Int
        return when (status) {
            ERROR_SUCCESS -> opened.get(ADDRESS, 0)
            ERROR_FILE_NOT_FOUND -> null
            else -> throw RegistryException(failure(REG_OPEN_KEY_EX, status))
        }
    }

    /** 開いた [key] の、子のキーの名前を順に読む。 */
    private fun enumerateSubKeys(arena: Arena, key: MemorySegment): List<String> {
        val name = arena.allocate(JAVA_CHAR, (MAX_KEY_NAME_CHARS + 1).toLong())
        val nameLength = arena.allocate(JAVA_INT)
        return buildList {
            var index = 0
            while (true) {
                nameLength.set(JAVA_INT, 0, MAX_KEY_NAME_CHARS + 1)
                val status = regEnumKeyEx.invoke(
                    key,
                    index,
                    name,
                    nameLength,
                    MemorySegment.NULL,
                    MemorySegment.NULL,
                    MemorySegment.NULL,
                    MemorySegment.NULL,
                ) as Int
                when (status) {
                    ERROR_SUCCESS -> add(name.getString(0, UTF_16LE))
                    ERROR_NO_MORE_ITEMS -> return@buildList
                    else -> throw RegistryException(failure(REG_ENUM_KEY_EX, status))
                }
                index++
            }
        }
    }

    /** 値が無いか、文字列の値でないことを表す、`RegGetValueW` の戻り値か。 */
    private fun Int.isMissingValue(): Boolean = this == ERROR_FILE_NOT_FOUND || this == ERROR_UNSUPPORTED_TYPE

    private fun failure(function: String, status: Int): String = "$function が失敗した（$status）"
}
