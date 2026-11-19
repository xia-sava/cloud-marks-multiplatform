; WindowsのインストーラをInno Setupでビルドする。Gradleの`:desktopApp:packageInnoSetup`が呼ぶ。
;
;   ISCC /DAppName=<配布物の名前> /DAppVersion=<versionName> /DFileVersion=<major.minor.patch>
;        /DSourceDir=<stageInstallerFilesの出力> /DHostName=<配布版のNative Messagingのホストの名前>
;        /DOutputDir=<出力先> cloudmarks.iss
;
; このファイルはBOM付きのUTF-8で保存する。BOMが無いと、ISCCは日本語をシステムのコードページとして読む。

#ifndef AppName
  #error AppName is required
#endif
#ifndef AppVersion
  #error AppVersion is required
#endif
#ifndef FileVersion
  #error FileVersion is required
#endif
#ifndef SourceDir
  #error SourceDir is required
#endif
#ifndef HostName
  #error HostName is required
#endif
#ifndef OutputDir
  #error OutputDir is required
#endif

; ランチャーのexeは、jpackageが配布物の名前で作る。
#define AppExe AppName + ".exe"
; インストーラ、スタートメニュー、アプリの一覧に出す名前。インストール先とexeの名前は配布物の名前を使う。
#define AppDisplayName "Cloud Marks"

[Setup]
; インストール済みのものを同じアプリとして見分ける値。変えると新旧が並存する。
AppId={{0E03F7AD-1A9E-4B40-B390-ACEDABC030F4}
AppName={#AppDisplayName}
AppVersion={#AppVersion}
AppPublisher=sava.to
VersionInfoVersion={#FileVersion}
UninstallDisplayName={#AppDisplayName}
UninstallDisplayIcon={app}\{#AppExe}
; ISCCは相対パスを、このスクリプトのあるディレクトリから解決する。
SetupIconFile=..\icons\cloudmarks.ico
; 設定も認証情報も端末ごとに持つ単一ユーザー向けのアプリなので、インストールと更新に管理者権限を要求しない。
; `{autopf}`はこの指定のもとで`%LOCALAPPDATA%\Programs`になる。
PrivilegesRequired=lowest
DefaultDirName={autopf}\{#AppName}
; インストール先を選ばせない。
DisableDirPage=yes
DisableProgramGroupPage=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
; 動いているアプリは実行ファイルを開いたままで、置き換えられない。閉じてから入れる。
CloseApplications=yes
RestartApplications=no
SetupLogging=yes
OutputDir={#OutputDir}
OutputBaseFilename=cloudmarks-setup
Compression=lzma2
SolidCompression=yes
WizardStyle=modern

[Languages]
Name: "ja"; MessagesFile: "compiler:Languages\Japanese.isl"

; 前のバージョンにあって新しいバージョンに無いファイルを残さない。jarの名前にはバージョンが入るため、
; 残すと新旧のjarが両方クラスパスに載る。拡張のフォルダも同じ理由で入れ直す。
; ユーザーのデータは`%LOCALAPPDATA%\CloudMarks`にあり、ここには無い。
[InstallDelete]
Type: filesandordirs; Name: "{app}\app"
Type: filesandordirs; Name: "{app}\runtime"
Type: filesandordirs; Name: "{app}\extension"

; `SourceDir`は`stageInstallerFiles`の出力で、アプリの配布物にNative Messagingのホストの起動口と、
; ホストを登録するJSONと、拡張（`extension\chrome`と、ビルドに渡したときは`extension\firefox`）と、
; リポジトリのライセンス（`LICENSE`）と第三者のライブラリの表記（`THIRD-PARTY-NOTICES.md`）を足したもの。
[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

; ChromeとFirefoxにNative Messagingのホストを登録する。既定の値が、ホストを登録するJSONのパス。
; アンインストールでキーを消す。開発版のホスト（名前が`.dev`で終わる）のキーには触れない。
[Registry]
Root: HKCU; Subkey: "Software\Google\Chrome\NativeMessagingHosts\{#HostName}"; ValueType: string; ValueName: ""; ValueData: "{app}\cloud-marks-host.chrome.json"; Flags: uninsdeletekey
Root: HKCU; Subkey: "Software\Mozilla\NativeMessagingHosts\{#HostName}"; ValueType: string; ValueName: ""; ValueData: "{app}\cloud-marks-host.firefox.json"; Flags: uninsdeletekey

[Icons]
Name: "{userprograms}\{#AppDisplayName}"; Filename: "{app}\{#AppExe}"; WorkingDir: "{app}"

; 人が操作してインストールしたときだけ、終わりに起動するかを尋ねる。人手なしで走らせたときは、呼んだ側が起動する。
[Run]
Filename: "{app}\{#AppExe}"; Description: "{cm:LaunchProgram,{#AppDisplayName}}"; Flags: nowait postinstall skipifsilent
