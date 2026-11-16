package to.sava.cloudmarksmultiplatform

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** Androidとデスクトップの双方から呼ぶ共通のエントリポイント。 */
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
