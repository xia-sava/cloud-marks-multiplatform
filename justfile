# :sharedにtestタスクは無く、gradlew testでは何も走らない。checkがjvmTestとtestAndroidHostTestを含む。
cm-test:
    cd apps/cm && ./gradlew check

# 開発版のNative Messagingのホスト（起動するバッチと、ChromeとFirefoxに登録するJSON）を
# apps/cm/desktopApp/build/native-messaging-host/に書き出す。ホストの名前は開発版のもの。レジストリには登録しない。
cm-dev-host:
    cd apps/cm && ./gradlew :desktopApp:writeDevNativeMessagingHost

# 配布版のWindowsのインストーラ（apps/cm/desktopApp/build/compose/binaries/main/inno/cloudmarks-setup.exe）を組む。
# 先に拡張を作り、配布版のChromeの拡張（apps/extension/dist/chrome）を入れる。Firefoxの拡張（署名済みの.xpi）は入れない。
# JetBrains Runtimeが無ければ失敗する。
cm-installer: ext-build
    cd apps/cm && ./gradlew :desktopApp:packageInnoSetup -Pcloudmarks.requireJetBrainsRuntime

# 配布版（apps/extension/dist/chrome・firefox）と開発版（apps/extension/dist/dev/chrome・firefox）の拡張を作る。
ext-build:
    cd apps/extension && npm run build

ext-test:
    cd apps/extension && npm run typecheck && npm test

test: ext-test cm-test
