package to.sava.cloudmarksmultiplatform

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

// TODO: Android のエントリポイントが CloudMarksApp を使うようになったら消す
/** Android のエントリポイントが呼ぶ、アプリの名前だけを出す画面。デスクトップは `jvmShared` の `CloudMarksApp()` を呼ぶ。 */
@Composable
fun App() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(contentAlignment = Alignment.Center) {
                Text(text = APP_NAME, style = MaterialTheme.typography.headlineMedium)
            }
        }
    }
}

private const val APP_NAME = "Cloud Marks"
