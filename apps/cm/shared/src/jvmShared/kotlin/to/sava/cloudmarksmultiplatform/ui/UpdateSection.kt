package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import to.sava.cloudmarksmultiplatform.update.UpdateCheck
import to.sava.cloudmarksmultiplatform.update.UpdateInstall
import to.sava.cloudmarksmultiplatform.update.UpdateState

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
 * インストールもできないこと（[DEV_BUILD_UPDATE_NOTICE]）、更新を確認するボタン、確認の結果、ダウンロードからインストーラへ渡すまでの
 * 段階を、呼んだ側の並びにそのまま足す。
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
    UpdateCheckResult(update, actions)
    update.install?.let { install -> UpdateInstallStage(install, distributed = update.version.distributed, actions) }
}

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
