package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import to.sava.cloudmarksmultiplatform.update.UpdateCheck
import to.sava.cloudmarksmultiplatform.update.UpdateInstall
import to.sava.cloudmarksmultiplatform.update.UpdateState
import kotlin.reflect.KClass

/**
 * 設定の画面の「アプリの更新」の節から、更新の操作（[to.sava.cloudmarksmultiplatform.update.AppUpdater]）へ渡す入口。
 *
 * @property canApply インストールを受け付けるか。データを変える操作を書いている最中
 * （[to.sava.cloudmarksmultiplatform.app.WriteState.writing]）は偽。インストールするとアプリをいったん終了するので、書き終えるのを待つ
 * @property onCheck 更新を確認する
 * @property onDownload 確認で分かった新しいバージョンをダウンロードする
 * @property onApply ダウンロードして確かめた新しいバージョンをインストールする
 * @property onCancel インストールしてよいかの返事を待つのをやめるか、止まった理由を閉じる
 */
internal class UpdateActions(
    val canApply: Boolean,
    val onCheck: () -> Unit,
    val onDownload: () -> Unit,
    val onApply: () -> Unit,
    val onCancel: () -> Unit,
)

/**
 * 設定の画面の「アプリの更新」の節。見出し（[UPDATE_SECTION_TITLE]）、動いているアプリのバージョン、開発版ならダウンロードも
 * インストールもできないこと（[DEV_BUILD_UPDATE_NOTICE]）、更新を確認するボタン、確認の結果とダウンロードからインストーラへ渡すまでの
 * 段階（[UpdateCheckResultAndInstallStage]）を、呼んだ側の並びにそのまま足す。
 *
 * 確認するボタンは、確認を受け付けないあいだ（[UpdateState.canCheck]）は押せない。開発版でも押せて、結果も出す。
 * 新しいバージョンがあれば、ダウンロードのボタンを出す。ダウンロードからインストーラへ渡すまでの途中は出さず、開発版では押せない。
 * 止まった理由は、誤りの色で出し、詳細の行は付けない。
 */
@Composable
internal fun UpdateSection(update: UpdateState, actions: UpdateActions) {
    Text(UPDATE_SECTION_TITLE, style = MaterialTheme.typography.titleSmall)
    Text(currentVersionNotice(update.version.displayName), style = MaterialTheme.typography.bodyMedium)
    if (!update.version.distributed) UpdateNote(DEV_BUILD_UPDATE_NOTICE)
    Button(onClick = actions.onCheck, enabled = update.canCheck) { Text(CHECK_UPDATE_LABEL) }
    UpdateCheckResultAndInstallStage(update, actions)
}

/**
 * 確認の結果と、ダウンロードからインストーラへ渡すまでの段階。出すものが無ければ（まだ確認していなければ）何も足さない。
 *
 * 出すものの種類（[shownKindsOf]）が替わったら、外側のスクロールする枠（設定の画面）をスクロールして、これを画面の中に入れる。
 * すでに画面の中にあれば動かさない。設定の画面を開いたときの状態では動かさず、そこから替わったときだけ動かす。節の状態が替わるのは、
 * 利用者がこの節で押した操作の続きだけで、起動したときに確かめることはない（[to.sava.cloudmarksmultiplatform.update.AppUpdater]）。
 * ダウンロードの進み具合が進んだだけでは動かさないので、ダウンロードの最中に利用者がスクロールしても、引き戻さない。
 */
@Composable
private fun UpdateCheckResultAndInstallStage(update: UpdateState, actions: UpdateActions) {
    val requester = remember { BringIntoViewRequester() }
    val kinds = shownKindsOf(update)
    var shownKinds by remember { mutableStateOf(kinds) }
    LaunchedEffect(kinds) {
        if (kinds != shownKinds) {
            shownKinds = kinds
            requester.bringIntoView()
        }
    }
    if (update.check == UpdateCheck.NotChecked && update.install == null) return
    Column(
        modifier = Modifier.fillMaxWidth().bringIntoViewRequester(requester),
        verticalArrangement = Arrangement.spacedBy(SETTINGS_SPACING),
    ) {
        UpdateCheckResult(update, actions)
        update.install?.let { install -> UpdateInstallStage(install, distributed = update.version.distributed, actions) }
    }
}

/**
 * 確認の結果と、ダウンロードからインストーラへ渡すまでの段階の、それぞれの種類。どちらかが替われば、節に出す文やボタンが替わる。
 * ダウンロードの進み具合や、止まった理由では替わらない。
 */
private fun shownKindsOf(update: UpdateState): Pair<KClass<out UpdateCheck>, KClass<out UpdateInstall>?> =
    update.check::class to update.install?.let { it::class }

/** 更新を確認している最中か、確認の結果。新しいバージョンがあれば、ダウンロードのボタンを添える。 */
@Composable
private fun UpdateCheckResult(update: UpdateState, actions: UpdateActions) {
    when (val check = update.check) {
        UpdateCheck.NotChecked -> Unit
        UpdateCheck.Checking -> UpdateProgressNote(CHECKING_UPDATE_NOTICE)
        UpdateCheck.UpToDate -> UpdateNote(UP_TO_DATE_NOTICE)
        is UpdateCheck.Available -> {
            UpdateNote(updateAvailableNotice(check.versionName))
            if (update.install?.inProgress != true) {
                Button(onClick = actions.onDownload, enabled = update.canDownload) { Text(DOWNLOAD_UPDATE_LABEL) }
            }
        }
        is UpdateCheck.Failed ->
            SettingsStatusLine(describeUpdateCheckFailure(check.failure), busy = false, color = MaterialTheme.colorScheme.error)
    }
}

/**
 * ダウンロードからインストーラへ渡すまでの段階 [install]。
 *
 * ダウンロードしている最中は進み具合のバーを出す（全体の大きさが分からなければ、終わりの見えない形で動かす）。数字は出さない。
 * インストールしてよいかの返事を待つ段階（インストールするとアプリをいったん終了するプラットフォームだけが通る）では、
 * そのことを伝えて、インストールするボタンとやめるボタンを出す。インストールするボタンは、開発版と、インストールを受け付けないあいだ
 * （[UpdateActions.canApply]）は押せない。押せない理由は出さない。止まったら、理由と閉じるボタンを出す。
 */
@Composable
private fun UpdateInstallStage(install: UpdateInstall, distributed: Boolean, actions: UpdateActions) {
    when (install) {
        is UpdateInstall.Downloading -> {
            UpdateNote(DOWNLOADING_UPDATE_NOTICE)
            val fraction = install.fraction
            if (fraction == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            }
        }
        UpdateInstall.Verifying -> UpdateProgressNote(VERIFYING_UPDATE_NOTICE)
        UpdateInstall.ReadyToApply -> {
            UpdateNote(RESTART_TO_APPLY_NOTICE)
            Row(horizontalArrangement = Arrangement.spacedBy(SETTINGS_SPACING)) {
                Button(onClick = actions.onApply, enabled = distributed && actions.canApply) { Text(APPLY_UPDATE_LABEL) }
                TextButton(onClick = actions.onCancel) { Text(CANCEL_LABEL) }
            }
        }
        UpdateInstall.Launching -> UpdateNote(LAUNCHING_INSTALLER_NOTICE)
        is UpdateInstall.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
            MessageText(
                message = describeUpdateInstallFailure(install.failure),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = actions.onCancel) { Text(CLOSE_LABEL) }
        }
    }
}

/** 「アプリの更新」の節の、様子を伝える 1 行。 */
@Composable
private fun UpdateNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** 「アプリの更新」の節の、最中であることを伝える 1 行。左にインジケーターを添える（[SettingsStatusLine]）。 */
@Composable
private fun UpdateProgressNote(text: String) {
    SettingsStatusLine(UiMessage(text), busy = true, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
