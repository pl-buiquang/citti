# citti

A fast, good-looking note app for Android (iOS later) that syncs with the [Nextcloud Notes](https://github.com/nextcloud/notes) API and WebDAV. Built with Kotlin Multiplatform and Compose Multiplatform (Ktor, SQLDelight, Glance widgets).

citti is a new app, not a fork of notes-android, and is not affiliated with Nextcloud.

## Status

Early development. Milestone M1 (project scaffold and theme) is in progress; sync, the editor and the widgets come next.

## Build

Requires JDK 17 and the Android SDK (`sdk.dir` in `local.properties` or `ANDROID_HOME`).

```sh
./gradlew spotlessCheck check assembleDebug
```

The debug APK lands in `androidApp/build/outputs/apk/debug/`.

## Layout

- `core`: shared Kotlin (models, Ktor, SQLDelight, sync)
- `ui`: Compose Multiplatform theme and components
- `androidApp`: the Android application
- `docs/adr`: architecture decision records; `GLOSSARY.md`: the project's terms

## License

[Apache-2.0](LICENSE). The bundled fonts are under the SIL Open Font License, see `licenses/fonts/`.
