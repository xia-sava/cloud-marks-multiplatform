# :sharedにtestタスクは無く、gradlew testでは何も走らない。checkがjvmTestとtestAndroidHostTestを含む。
cm-test:
    cd apps/cm && ./gradlew check

ext-build:
    cd apps/extension && npm run build

ext-test:
    cd apps/extension && npm run typecheck && npm test

test: ext-test cm-test
