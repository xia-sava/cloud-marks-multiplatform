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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import to.sava.cloudmarksmultiplatform.app.SettingsScreenState
import to.sava.cloudmarksmultiplatform.app.SettingsStatus
import to.sava.cloudmarksmultiplatform.settings.DisplayScale
import to.sava.cloudmarksmultiplatform.settings.SettingsField
import to.sava.cloudmarksmultiplatform.settings.SettingsForm
import to.sava.cloudmarksmultiplatform.settings.SettingsReadResult
import to.sava.cloudmarksmultiplatform.settings.TabLoading
import to.sava.cloudmarksmultiplatform.settings.firstLineOf
import to.sava.cloudmarksmultiplatform.settings.parseLoadAhead
import to.sava.cloudmarksmultiplatform.settings.parseTabCount

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
 * 設定の画面。S3 の接続先と認証情報を入れて保存する。その下で、画面全体の表示の大きさを選び、[tabLoading] があれば、
 * 見出し [OPEN_IN_BROWSER_SECTION_TITLE] の下で、まとめてブラウザで開くときのタブの読み込み方を入れる。
 *
 * シークレットは伏せ字で入れる。保存済みの設定があるときは、シークレットの欄を空にして [STORED_SECRET_HINT] を添える。
 * 保存済みのシークレットは画面に出さない。
 * 表示の大きさは、選ぶとすぐに [onDisplayScaleChange] へ渡し、保存の操作を要しない。保存の最中でも選べる。
 * タブの読み込み方も同じく、欄に正しい数（同時に読み込むタブの数は 1 以上、先に読み込むタブの数は 0 以上の整数）を入れるたびに
 * [onTabLoadingChange] へ渡す（[TabCountField]）。
 * 末尾に、以前の状態に戻す画面を開くボタン（[REVISIONS_TITLE]）と、その説明（[REVISIONS_DESCRIPTION]）を置く。
 *
 * @param displayScale いまの表示の大きさ
 * @param tabLoading いまのタブの読み込み方。null なら、その見出しと欄を出さない（まとめて開けないプラットフォーム）
 * @param canRestore 以前の状態に戻すことを受け付けるか。偽なら、以前の状態に戻す画面を開くボタンを押せない
 * @param onEdit 入力を変えたときに、変えたあとの入力を渡す
 * @param onSave 保存する
 * @param onCreateManifest マニフェストが無いと分かったあとで、マニフェストを作る
 * @param onDisplayScaleChange 表示の大きさを選んだときに、選んだ段を渡す
 * @param onTabLoadingChange タブの読み込み方の欄に正しい値を入れたときに、変えたあとの読み込み方を渡す
 * @param onOpenRevisions 以前の状態に戻す画面を開く
 */
@Composable
internal fun SettingsScreen(
    settings: SettingsScreenState,
    displayScale: DisplayScale,
    tabLoading: TabLoading?,
    canRestore: Boolean,
    onEdit: (SettingsForm) -> Unit,
    onSave: () -> Unit,
    onCreateManifest: () -> Unit,
    onDisplayScaleChange: (DisplayScale) -> Unit,
    onTabLoadingChange: (TabLoading) -> Unit,
    onOpenRevisions: () -> Unit,
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
            HorizontalDivider()
            DisplayScaleField(displayScale, onDisplayScaleChange)
            tabLoading?.let { loading ->
                HorizontalDivider()
                Text(OPEN_IN_BROWSER_SECTION_TITLE, style = MaterialTheme.typography.titleSmall)
                TabCountField(
                    label = LOAD_AT_ONCE_LABEL,
                    value = loading.loadAtOnce,
                    parse = ::parseTabCount,
                    invalidNotice = TAB_COUNT_INVALID_NOTICE,
                    hint = null,
                ) { onTabLoadingChange(loading.copy(loadAtOnce = it)) }
                TabCountField(
                    label = LOAD_AHEAD_LABEL,
                    value = loading.loadAhead,
                    parse = ::parseLoadAhead,
                    invalidNotice = LOAD_AHEAD_INVALID_NOTICE,
                    hint = LOAD_AHEAD_HINT,
                ) { onTabLoadingChange(loading.copy(loadAhead = it)) }
            }
            HorizontalDivider()
            Button(onClick = onOpenRevisions, enabled = canRestore) { Text(REVISIONS_TITLE) }
            Text(
                text = REVISIONS_DESCRIPTION,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * タブの数の欄。題は [label]、初めはいまの値 [value] を出す。
 *
 * 入れるたびに [parse] で読み、数として読めれば [onChange] に渡す。読めなければ渡さずに、誤りの色にして
 * [invalidNotice] を添え、入れたものをそのまま出し続ける（いまの値は、最後に渡した正しい値のまま）。
 * 入力が正しいときは、[hint] があれば欄の下に添える。
 * [value] が欄の値と違う値に替わったら、欄をその値にする。
 *
 * @param parse 入力を数として読む。読めなければ null
 */
@Composable
private fun TabCountField(
    label: String,
    value: Int,
    parse: (String) -> Int?,
    invalidNotice: String,
    hint: String?,
    onChange: (Int) -> Unit,
) {
    var text by remember { mutableStateOf(value.toString()) }
    LaunchedEffect(value) {
        if (parse(text) != value) text = value.toString()
    }
    val invalid = parse(text) == null
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input
            parse(input)?.let(onChange)
        },
        label = { Text(label) },
        supportingText = (if (invalid) invalidNotice else hint)?.let { { Text(it) } },
        isError = invalid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * 表示の大きさを選ぶ欄。題は [DISPLAY_SCALE_LABEL]、いまの段を `80%` のように出し（[displayScaleLabel]）、押すと段の一覧を開く。
 * 段を選ぶと一覧を閉じ、[onChange] に選んだ段を渡す。一覧の行は、画面全体と同じ表示の大きさで描く（[ScaledPopupContent]）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DisplayScaleField(scale: DisplayScale, onChange: (DisplayScale) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = displayScaleLabel(scale),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(DISPLAY_SCALE_LABEL) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ScaledPopupContent {
                DisplayScale.STEPS.forEach { step ->
                    DropdownMenuItem(
                        text = { Text(displayScaleLabel(step)) },
                        onClick = {
                            expanded = false
                            onChange(step)
                        },
                    )
                }
            }
        }
    }
}

/**
 * 設定の 1 行の入力欄。入れた（貼り付けたものを含む）改行とその後ろは受け付けず、欄にも出さない（[firstLineOf]）。
 *
 * @param onValueChange 入力を変えたときに、改行とその後ろを除いた値を渡す
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
        onValueChange = { input -> onValueChange(firstLineOf(input)) },
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
        status == SettingsStatus.ProtectionUnavailable ||
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
