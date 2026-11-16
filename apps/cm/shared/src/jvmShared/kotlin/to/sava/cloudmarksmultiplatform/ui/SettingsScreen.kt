package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import to.sava.cloudmarksmultiplatform.app.SettingsScreenState
import to.sava.cloudmarksmultiplatform.app.SettingsStatus
import to.sava.cloudmarksmultiplatform.settings.SettingsField
import to.sava.cloudmarksmultiplatform.settings.SettingsForm
import to.sava.cloudmarksmultiplatform.settings.SettingsReadResult

/** 入力欄を並べる幅の上限。広い画面で横に伸びすぎないようにする。 */
private val SETTINGS_MAX_WIDTH = 560.dp

private val SETTINGS_PADDING = 16.dp
private val SETTINGS_SPACING = 12.dp
private val SETTINGS_PROGRESS_SIZE = 20.dp

private const val MISSING_FIELD = "入力してください。"

/** 保存済みの設定があるときに、シークレットの欄に添える文。 */
private const val STORED_SECRET_HINT = "変更する場合だけ入力してください。"

/** プレフィックスの欄に常に添える文。 */
private const val PREFIX_HINT = "空欄にすると、バケットの直下に保存します。"

/**
 * 設定の画面。S3 の接続先と認証情報を入れて保存する。
 *
 * シークレットは伏せ字で入れる。保存済みの設定があるときは、シークレットの欄を空にして [STORED_SECRET_HINT] を添える。
 * 保存済みのシークレットは画面に出さない。
 *
 * @param onEdit 入力を変えたときに、変えたあとの入力を渡す
 * @param onSave 保存する
 * @param onCreateManifest マニフェストが無いと分かったあとで、マニフェストを作る
 */
@Composable
internal fun SettingsScreen(
    settings: SettingsScreenState,
    onEdit: (SettingsForm) -> Unit,
    onSave: () -> Unit,
    onCreateManifest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val form = settings.form
    val busy = settings.status.busy
    val missing = (settings.status as? SettingsStatus.Incomplete)?.fields.orEmpty()
    Box(modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier.widthIn(max = SETTINGS_MAX_WIDTH).fillMaxWidth().padding(SETTINGS_PADDING),
            verticalArrangement = Arrangement.spacedBy(SETTINGS_SPACING),
        ) {
            settings.readProblem?.let { readProblem ->
                describeSettingsReadProblem(readProblem)?.let { problem ->
                    val color = if (readProblem == SettingsReadResult.NotFound) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                    MessageText(problem, color = color)
                }
            }
            SettingsTextField("リージョン", form.region, { onEdit(form.copy(region = it)) }, SettingsField.REGION in missing, busy)
            SettingsTextField("バケット名", form.bucket, { onEdit(form.copy(bucket = it)) }, SettingsField.BUCKET in missing, busy)
            SettingsTextField(
                label = "プレフィックス",
                value = form.prefix,
                onValueChange = { onEdit(form.copy(prefix = it)) },
                missing = false,
                busy = busy,
                hint = PREFIX_HINT,
            )
            SettingsTextField(
                label = "アクセスキー ID",
                value = form.accessKeyId,
                onValueChange = { onEdit(form.copy(accessKeyId = it)) },
                missing = SettingsField.ACCESS_KEY_ID in missing,
                busy = busy,
            )
            SettingsTextField(
                label = "シークレットアクセスキー",
                value = form.secret,
                onValueChange = { onEdit(form.copy(secret = it)) },
                missing = SettingsField.SECRET in missing,
                busy = busy,
                hint = STORED_SECRET_HINT.takeIf { settings.hasStoredSettings },
                secret = true,
            )
            Button(onClick = onSave, enabled = !busy) { Text("保存") }
            SettingsStatusView(settings.status, onCreateManifest)
        }
    }
}

/**
 * 設定の入力欄。
 *
 * @param missing 入力が要るのに空か。そうなら誤りの色にして、[MISSING_FIELD] を添える
 * @param hint 欄の下に常に添える文。[missing] のときは [MISSING_FIELD] を優先する
 * @param secret 伏せ字にするか
 */
@Composable
private fun SettingsTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    missing: Boolean,
    busy: Boolean,
    hint: String? = null,
    secret: Boolean = false,
) {
    val supporting = if (missing) MISSING_FIELD else hint
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = supporting?.let { { Text(it) } },
        isError = missing,
        enabled = !busy,
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = if (secret) {
            KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)
        } else {
            KeyboardOptions(autoCorrectEnabled = false)
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SettingsStatusView(status: SettingsStatus, onCreateManifest: () -> Unit) {
    val message = describeSettingsStatus(status) ?: return
    val failed = status is SettingsStatus.Incomplete ||
        status is SettingsStatus.ProtectionUnavailable ||
        status is SettingsStatus.WriteFailed ||
        status is SettingsStatus.ConnectionFailed
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SETTINGS_SPACING)) {
        if (status.busy) CircularProgressIndicator(Modifier.size(SETTINGS_PROGRESS_SIZE))
        MessageText(
            message = message,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
    if (status == SettingsStatus.ManifestNotFound) {
        Button(onClick = onCreateManifest) { Text("作成") }
    }
}
