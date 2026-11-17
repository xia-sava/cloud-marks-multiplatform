# :sharedにtestタスクは無く、gradlew testでは何も走らない。checkがjvmTestとtestAndroidHostTestを含む。
cm-test:
    cd apps/cm && ./gradlew check

# 開発用のNative Messagingのホスト（起動するバッチと、ChromeとFirefoxに登録するJSON）を
# apps/cm/desktopApp/build/native-messaging-host/に書き出す。レジストリには登録しない。
cm-dev-host:
    cd apps/cm && ./gradlew :desktopApp:writeDevNativeMessagingHost

ext-build:
    cd apps/extension && npm run build

ext-test:
    cd apps/extension && npm run typecheck && npm test

test: ext-test cm-test
