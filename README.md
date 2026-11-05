# Граммовка

Personal Android calorie tracker: Kotlin, Jetpack Compose, Room. Fully local; Claude, Open Food Facts and USDA are optional add-ons with keys entered in the app.

## Install

1. On the phone, open the [android release](https://github.com/glebanisimow/grammovka/releases/tag/android) and download `grammovka-latest.apk`.
2. Allow installing from the browser when Android asks, then install.
3. Later updates: **Настройки → Обновление** inside the app. Data is kept.

Every push to `main` builds a signed APK via GitHub Actions (`.github/workflows/build-apk.yml`) and publishes it there.

## Develop

See [CLAUDE.md](CLAUDE.md) for the spec, architecture and current state.

```
gradlew.bat test
gradlew.bat :app:assembleDebug
```
