package to.sava.cloudmarksmultiplatform.files

import kotlin.test.Test
import kotlin.test.assertEquals

class NativeCallFailureTest {
    // ネイティブの関数の呼び出しが許されていない失敗は、例外の種類だけにし、メッセージの jar のパスを含めない
    @Test
    fun illegalCallerIsDescribedWithoutMessage() {
        val e = IllegalCallerException("""Illegal native access from: C:\Users\me\AppData\Local\Programs\CloudMarks\app\shared.jar""")

        assertEquals("IllegalCallerException", describeIllegalCaller(e))
    }
}
